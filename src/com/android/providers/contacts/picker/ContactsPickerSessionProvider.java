/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.contacts.picker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.provider.ContactsContract.Data;
import android.provider.ContactsPickerSessionContract;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.providers.contacts.picker.ContactsPickerDatabaseHelper.SessionColumns;
import com.android.providers.contacts.picker.ContactsPickerDatabaseHelper.Tables;
import com.android.providers.contacts.util.UserUtils;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ContentProvider} for creating and managing contacts picker sessions.
 *
 * <p>This provider allows callers to create a session by inserting a set of data row IDs. A unique
 * session URI is returned, which can then be used to query the corresponding contact data.
 *
 * <p>Sessions older than 24 hours are automatically cleaned up by a daily job.
 */
public class ContactsPickerSessionProvider extends ContentProvider {

    private static final String TAG = "ContactsPickerSession";
    private static final boolean VERBOSE_LOGGING = Log.isLoggable(TAG, Log.VERBOSE);
    private static final int MAX_SESSION_COUNT = 5000;

    // Relays that the call is being forwarded to CP2 from sessions provider.
    public static final ThreadLocal<Boolean> sIsForwardedFromSessionsProvider =
            ThreadLocal.withInitial(() -> false);

    @VisibleForTesting
    protected int getMaxSessionCount() {
        return MAX_SESSION_COUNT;
    }

    private static final int URI_MATCH_SESSIONS_BASE = 1;
    private static final int URI_MATCH_SESSION_ID = 2;

    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        sUriMatcher.addURI(
                ContactsPickerSessionContract.AUTHORITY, "sessions", URI_MATCH_SESSIONS_BASE);
        sUriMatcher.addURI(
                ContactsPickerSessionContract.AUTHORITY, "sessions/*", URI_MATCH_SESSION_ID);
    }

    private ContactsPickerDatabaseHelper mDatabaseHelper;

    @Override
    public boolean onCreate() {
        mDatabaseHelper = ContactsPickerDatabaseHelper.getInstance(getContext());
        scheduleCleanupJob();
        return true;
    }

    @VisibleForTesting
    protected void scheduleCleanupJob() {
        ContactsPickerJobScheduler.scheduleCleanupJob(getContext());
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (VERBOSE_LOGGING) {
            Log.v(TAG, "insert: uri=" + uri);
        }

        final int match = sUriMatcher.match(uri);
        if (match != URI_MATCH_SESSIONS_BASE) {
            throw new IllegalArgumentException("Unknown/Invalid URI: " + uri);
        }

        validateContentValues(values);

        final String sessionUid = UUID.randomUUID().toString();

        // App A (Picker) inserts the session on behalf of App B (Requester).
        // We trust the value in KEY_SESSION_REQUESTER_UID because this method
        // is protected by a signature permission in the Manifest.
        ContentValues valuesToInsert = new ContentValues();
        valuesToInsert.put(
                SessionColumns.DATA_ROW_IDS,
                values.getAsString(ContactsPickerSessionContract.Session.CONTACT_DATA_IDS));
        valuesToInsert.put(SessionColumns.SESSION_UID, sessionUid);
        valuesToInsert.put(
                SessionColumns.CALLER_UID,
                values.getAsInteger(ContactsPickerSessionContract.Session.SESSION_REQUESTER_UID));
        valuesToInsert.put(SessionColumns.CREATED_AT, System.currentTimeMillis());

        final SQLiteDatabase db = mDatabaseHelper.getWritableDatabase();
        long rowId;
        db.beginTransaction();
        try {
            pruneOldSessionsIfLimitReached(db);
            rowId = db.insert(Tables.SESSIONS, null, valuesToInsert);
            if (rowId > 0) {
                db.setTransactionSuccessful();
            }
        } finally {
            db.endTransaction();
        }

        if (rowId <= 0) {
            Log.e(TAG, "Failed to insert session into database.");
            return null;
        }

        final Uri sessionUri =
                Uri.withAppendedPath(ContactsPickerSessionContract.Session.CONTENT_URI, sessionUid);

        if (VERBOSE_LOGGING) {
            Log.d(TAG, "Successfully inserted session: " + sessionUri);
        }

        return sessionUri;
    }

    private void pruneOldSessionsIfLimitReached(SQLiteDatabase db) {
        int maxSessionCount = getMaxSessionCount();
        long count = DatabaseUtils.queryNumEntries(db, Tables.SESSIONS);
        if (count >= maxSessionCount) {
            long numToDelete = count - maxSessionCount + 1;
            String whereClause =
                    SessionColumns._ID
                            + " IN (SELECT "
                            + SessionColumns._ID
                            + " FROM "
                            + Tables.SESSIONS
                            + " ORDER BY "
                            + SessionColumns.CREATED_AT
                            + " ASC, "
                            + SessionColumns._ID
                            + " ASC LIMIT ?)";
            int prunedCount =
                    db.delete(
                            Tables.SESSIONS,
                            whereClause,
                            new String[] {String.valueOf(numToDelete)});
            if (VERBOSE_LOGGING) {
                Log.d(TAG, "Pruned " + prunedCount + " old session(s) to maintain limit.");
            }
        }
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        if (VERBOSE_LOGGING) {
            Log.v(TAG, "query: uri=" + uri + "  projection=" + Arrays.toString(projection)
                    + "  selection=[" + selection + "]  args=" + Arrays.toString(selectionArgs)
                    + "  order=[" + sortOrder + "] CPID=" + Binder.getCallingPid()
                    + " CUID=" + Binder.getCallingUid()
                    + " User=" + UserUtils.getCurrentUserHandle(getContext()));
        }
        sIsForwardedFromSessionsProvider.set(true);

        try {
            return switch (sUriMatcher.match(uri)) {
                case URI_MATCH_SESSIONS_BASE -> {
                    if (VERBOSE_LOGGING) {
                        Log.w(TAG,
                                "Query on base URI not supported, no session ID provided: " + uri);
                    }
                    yield null; // No session ID provided
                }
                case URI_MATCH_SESSION_ID -> handleSessionIdQuery(uri, projection, selection,
                        selectionArgs, sortOrder);
                default -> throw new IllegalArgumentException("Unknown URI: " + uri);
            };
        } finally {
            sIsForwardedFromSessionsProvider.remove();
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(Uri uri) {
        final int match = sUriMatcher.match(uri);
        return switch (match) {
            case URI_MATCH_SESSION_ID -> ContactsPickerSessionContract.Session.CONTENT_TYPE;
            case URI_MATCH_SESSIONS_BASE -> null; // Base URI not supported for type
            default -> null;
        };
    }

    /**
     * The call() method is exposed for invoking provider-defined methods. Currently, the only
     * supported method is "cleanupStaleSessions".
     *
     * <p>The "cleanupStaleSessions" method is used to trigger the cleanup of stale sessions. This
     * method can only be called by the same process that is running this provider. Calls from other
     * processes will result in a {@link SecurityException}.
     *
     * @param method The name of the method to call. Currently only "cleanupStaleSessions" is
     *     supported.
     * @param arg Optional string argument.
     * @param extras Optional Bundle of extra data.
     * @return Always returns null.
     * @throws SecurityException if "cleanupStaleSessions" is called from a different process.
     */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("cleanupStaleSessions".equals(method)) {
            if (Binder.getCallingUid() == android.os.Process.myUid()) {
                cleanupStaleSessions();
            } else {
                throw new SecurityException(
                        "cleanupStaleSessions can only be called by the provider's own process.");
            }
        }
        return null;
    }

    private record SessionData(String dataRowIds, int callerUid) {}

    private SessionData getSessionData(String sessionUid) {
        final SQLiteDatabase db = mDatabaseHelper.getReadableDatabase();

        try (Cursor sessionCursor =
                db.query(
                        Tables.SESSIONS,
                        new String[] {SessionColumns.DATA_ROW_IDS, SessionColumns.CALLER_UID},
                        SessionColumns.SESSION_UID + " = ?",
                        new String[] {sessionUid},
                        null,
                        null,
                        null)) {
            if (!sessionCursor.moveToFirst()) {
                return null; // Session not found
            }

            final String dataRowIdsStr = sessionCursor.getString(0);
            final int callerUid = sessionCursor.getInt(1);
            return new SessionData(dataRowIdsStr, callerUid);
        }
    }

    private Cursor handleSessionIdQuery(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        final String sessionUid = uri.getLastPathSegment();
        final SessionData sessionData = getSessionData(sessionUid);
        if (sessionData == null) {
            if (VERBOSE_LOGGING) {
                Log.w(TAG, "No session found for UID: " + sessionUid);
            }
            return null;
        }

        // Verify that the caller (App B) matches the UID stored during insert.
        final int callingUid = Binder.getCallingUid();
        if (sessionData.callerUid != callingUid) {
            throw new SecurityException(
                    "Calling UID "
                            + callingUid
                            + " does not match session owner "
                            + sessionData.callerUid
                            + " for session UID: "
                            + sessionUid);
        }

        if (selection != null || selectionArgs != null) {
            throw new UnsupportedOperationException(
                    "Querying Session Uri with selection or selection arguments is not allowed.");
        }

        // The only way to add dataRowIds in sessions table is via the insert() method. The latter
        // ensures that dataRowIds are non-empty and formatted correctly, so we don't do any further
        // massaging on same here.
        String dataSelection = Data._ID + " IN (" + sessionData.dataRowIds + ")";

        // Calling identity must be cleared to query ContactsContract.Data using this provider's
        // identity.
        //
        // This is necessary for two reasons:
        // 1. Package Mismatch: ContactsProvider verifies that the Calling UID owns the Calling
        //    Package. Without clearing, it sees the Client's UID (App B) but this Provider's
        //    Package, causing a SecurityException ("Package ... does not belong to ...").
        //
        // 2. Permissions: The Client app does not need to have READ_CONTACTS permission. This
        //    provider acts as a privileged proxy, fetching the data on the client's behalf only
        //    after verifying that the client owns this specific session.
        if (VERBOSE_LOGGING) {
            Log.v(
                    TAG,
                    "query: uri="
                            + uri
                            + "  projection="
                            + Arrays.toString(projection)
                            + "  selection=["
                            + dataSelection
                            + "] "
                            + "  args=null"
                            + "  order=["
                            + sortOrder
                            + "] CPID="
                            + Binder.getCallingPid()
                            + " CUID="
                            + Binder.getCallingUid()
                            + " User="
                            + UserUtils.getCurrentUserHandle(getContext()));
        }
        final long token = Binder.clearCallingIdentity();
        try {
            return getContext()
                    .getContentResolver()
                    .query(Data.CONTENT_URI, projection, dataSelection, null, sortOrder);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void validateContentValues(ContentValues values) {
        if (values == null) {
            throw new IllegalArgumentException("Insert operation failed: ContentValues is null.");
        }

        String contactDataIds =
                values.getAsString(ContactsPickerSessionContract.Session.CONTACT_DATA_IDS);
        if (TextUtils.isEmpty(contactDataIds)) {
            throw new IllegalArgumentException(
                    "Insert operation failed: "
                            + ContactsPickerSessionContract.Session.CONTACT_DATA_IDS
                            + " is empty.");
        }

        // Validate that contact data ids are comma-separated long integers
        String[] ids = contactDataIds.split(",");
        if (ids.length == 0) {
            throw new IllegalArgumentException(
                    "Insert operation failed: "
                            + ContactsPickerSessionContract.Session.CONTACT_DATA_IDS
                            + " is empty.");
        }
        for (String id : ids) {
            String trimmedId = id.trim();
            if (!trimmedId.isEmpty()) {
                try {
                    Long.parseLong(trimmedId);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Insert operation failed:"
                                    + ContactsPickerSessionContract.Session.CONTACT_DATA_IDS
                                    + " must be comma-separated longs.",
                            e);
                }
            } else {
                throw new IllegalArgumentException(
                        "Insert operation failed: In "
                                + ContactsPickerSessionContract.Session.CONTACT_DATA_IDS
                                + ", empty string between commas is not allowed.");
            }
        }

        if (!values.containsKey(ContactsPickerSessionContract.Session.SESSION_REQUESTER_UID)) {
            throw new IllegalArgumentException(
                    "Insert operation failed: "
                            + ContactsPickerSessionContract.Session.SESSION_REQUESTER_UID
                            + " is missing.");
        }

        Integer sessionRequesterUid =
                values.getAsInteger(ContactsPickerSessionContract.Session.SESSION_REQUESTER_UID);
        if (sessionRequesterUid == null) {
            throw new IllegalArgumentException(
                    "Insert operation failed: "
                            + ContactsPickerSessionContract.Session.SESSION_REQUESTER_UID
                            + " cannot be null.");
        }
    }

    /** Deletes sessions that are older than 24 hours from the database. */
    private void cleanupStaleSessions() {
        long expirationTimestamp =
                System.currentTimeMillis()
                        - TimeUnit.DAYS.toMillis(ContactsPickerJobScheduler.CLEANUP_INTERVAL_DAYS);
        String selection = SessionColumns.CREATED_AT + " <= ?";
        String[] selectionArgs = {String.valueOf(expirationTimestamp)};
        SQLiteDatabase db = mDatabaseHelper.getWritableDatabase();
        int rowsDeleted = db.delete(Tables.SESSIONS, selection, selectionArgs);
        Log.i(TAG, "Cleaned up " + rowsDeleted + " stale session(s).");
    }
}
