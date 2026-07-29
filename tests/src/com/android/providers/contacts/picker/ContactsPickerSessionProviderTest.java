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

import static org.junit.Assert.assertThrows;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Binder;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Data;
import android.provider.ContactsPickerSessionContract;
import android.provider.ContactsPickerSessionContract.Session;
import android.test.ProviderTestCase2;
import android.test.mock.MockContentResolver;

import androidx.test.filters.SmallTest;

import com.android.providers.contacts.picker.ContactsPickerDatabaseHelper.SessionColumns;
import com.android.providers.contacts.picker.ContactsPickerDatabaseHelper.Tables;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@SmallTest
public class ContactsPickerSessionProviderTest
        extends ProviderTestCase2<
                ContactsPickerSessionProviderTest.TestableContactsPickerSessionProvider> {

    private static final int CALLER_UID = Binder.getCallingUid();
    private static final Uri DATA_CONTENT_URI = Data.CONTENT_URI;

    private MockContentResolver mMockContentResolver;
    private ContactsPickerDatabaseHelper mDbHelper;
    private FakeContactsProvider mFakeContactsProvider;

    /**
     * Testable version of {@link ContactsPickerSessionProvider} that stubs out job scheduling to
     * prevent side effects in tests.
     */
    public static class TestableContactsPickerSessionProvider
            extends ContactsPickerSessionProvider {
        private int mMaxSessionCount = super.getMaxSessionCount();

        @Override
        protected void scheduleCleanupJob() {
            // This is a no-op for tests to prevent the JobScheduler from being called.
        }

        @Override
        protected int getMaxSessionCount() {
            return mMaxSessionCount;
        }

        void setMaxSessionCount(int maxSessionCount) {
            mMaxSessionCount = maxSessionCount;
        }
    }

    public ContactsPickerSessionProviderTest() {
        super(TestableContactsPickerSessionProvider.class, ContactsPickerSessionContract.AUTHORITY);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mMockContentResolver = getMockContentResolver();
        mDbHelper = ContactsPickerDatabaseHelper.getInstance(getMockContext());
        mFakeContactsProvider = new FakeContactsProvider(getMockContext());
        mMockContentResolver.addProvider(ContactsContract.AUTHORITY, mFakeContactsProvider);
    }

    @Override
    protected void tearDown() throws Exception {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.delete(Tables.SESSIONS, null, null);
        mFakeContactsProvider.clearData();
        super.tearDown();
    }

    public void testInsert_valid_returnsSessionUri() {
        ContentValues values = createSessionValues("1,2,3", CALLER_UID);
        Uri sessionUri = mMockContentResolver.insert(Session.CONTENT_URI, values);

        assertNotNull(sessionUri);
        assertEquals(ContactsPickerSessionContract.AUTHORITY, sessionUri.getAuthority());
        assertEquals("sessions", sessionUri.getPathSegments().get(0));
        assertNotNull(sessionUri.getLastPathSegment()); // UUID
    }

    public void testInsert_missingDataRowIds_throwsException() {
        ContentValues values = new ContentValues();
        values.put(SessionColumns.CALLER_UID, CALLER_UID);

        assertThrows(
                IllegalArgumentException.class,
                () -> mMockContentResolver.insert(Session.CONTENT_URI, values));
    }

    public void testInsert_emptyDataRowIds_throwsException() {
        ContentValues values = createSessionValues("", CALLER_UID);

        assertThrows(
                IllegalArgumentException.class,
                () -> mMockContentResolver.insert(Session.CONTENT_URI, values));
    }

    public void testInsert_dataRowIdsWithEmptyString_throwsException() {
        ContentValues values = createSessionValues("1,,2", CALLER_UID);

        assertThrows(
                IllegalArgumentException.class,
                () -> mMockContentResolver.insert(Session.CONTENT_URI, values));
    }

    public void testInsert_dataRowIdsWithOnlyComma_throwsException() {
        ContentValues values = createSessionValues(",", CALLER_UID);

        assertThrows(
                IllegalArgumentException.class,
                () -> mMockContentResolver.insert(Session.CONTENT_URI, values));
    }

    public void testInsert_invalidDataRowIdsFormat_throwsException() {
        ContentValues values = createSessionValues("1,2,a", CALLER_UID);

        assertThrows(
                IllegalArgumentException.class,
                () -> mMockContentResolver.insert(Session.CONTENT_URI, values));
    }

    public void testInsert_dataRowIdsWithSpaces_valid() {
        ContentValues values = createSessionValues(" 1 , 2 ", CALLER_UID);
        Uri sessionUri = mMockContentResolver.insert(Session.CONTENT_URI, values);
        assertNotNull(sessionUri);
    }

    public void testInsert_onlyCommas_throwsException() {
        ContentValues values = createSessionValues(",,", CALLER_UID);
        assertThrows(
                IllegalArgumentException.class,
                () -> mMockContentResolver.insert(Session.CONTENT_URI, values));
    }

    public void testInsert_spacesAndCommas_throwsException() {
        ContentValues values = createSessionValues(" , , ", CALLER_UID);
        assertThrows(
                IllegalArgumentException.class,
                () -> mMockContentResolver.insert(Session.CONTENT_URI, values));
    }

    public void testInsert_onlySpace_throwsException() {
        ContentValues values = createSessionValues(" ", CALLER_UID);
        assertThrows(
                IllegalArgumentException.class,
                () -> mMockContentResolver.insert(Session.CONTENT_URI, values));
    }

    public void testInsert_validAndEmpty_throwsException() {
        ContentValues values = createSessionValues("1, ,3", CALLER_UID);
        assertThrows(
                IllegalArgumentException.class,
                () -> mMockContentResolver.insert(Session.CONTENT_URI, values));
    }

    public void testInsert_maxSessionLimitReached_removesOldestSession() {
        getProvider().setMaxSessionCount(5);

        // Insert 5 sessions
        for (int i = 0; i < 5; i++) {
            ContentValues values = createSessionValues(String.valueOf(i), CALLER_UID);
            mMockContentResolver.insert(Session.CONTENT_URI, values);
            // Sleep briefly to ensure timestamps are different
            SystemClock.sleep(20);
        }

        // Verify we have 5 sessions
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        assertEquals(5, android.database.DatabaseUtils.queryNumEntries(db, Tables.SESSIONS));

        // Get the ID of the oldest session (should be the first one inserted)
        Cursor cursor = db.query(Tables.SESSIONS, new String[]{SessionColumns._ID}, null, null,
                null, null, SessionColumns.CREATED_AT + " ASC", " 1");
        assertTrue(cursor.moveToFirst());
        long oldestId = cursor.getLong(0);
        cursor.close();

        // Insert one more session
        ContentValues newValues = createSessionValues("5001", CALLER_UID);
        Uri newSessionUri = mMockContentResolver.insert(Session.CONTENT_URI, newValues);
        assertNotNull(newSessionUri);

        // Verify we still have 5 sessions
        assertEquals(5, android.database.DatabaseUtils.queryNumEntries(db, Tables.SESSIONS));

        // Verify the oldest session was removed
        Cursor oldSessionCursor = db.query(Tables.SESSIONS, new String[]{SessionColumns._ID},
                SessionColumns._ID + " = ?", new String[]{String.valueOf(oldestId)},
                null, null, null);
        assertEquals(0, oldSessionCursor.getCount());
        oldSessionCursor.close();

        // Verify the new session exists
        String newSessionUid = newSessionUri.getLastPathSegment();
        Cursor newSessionCursor = db.query(Tables.SESSIONS, new String[]{SessionColumns._ID},
                SessionColumns.SESSION_UID + " = ?", new String[]{newSessionUid}, null, null, null);
        assertEquals(1, newSessionCursor.getCount());
        newSessionCursor.close();
    }

    public void testInsert_missingCallerUid_throwsException() {
        ContentValues values = new ContentValues();
        values.put(SessionColumns.DATA_ROW_IDS, "1,2,3");

        assertThrows(
                IllegalArgumentException.class,
                () -> mMockContentResolver.insert(Session.CONTENT_URI, values));
    }

    public void testInsert_invalidUri_throwsException() {
        ContentValues values = createSessionValues("1,2,3", CALLER_UID);
        Uri invalidUri =
                Uri.parse("content://" + ContactsPickerSessionContract.AUTHORITY + "/invalid");

        assertThrows(
                IllegalArgumentException.class,
                () -> mMockContentResolver.insert(invalidUri, values));
    }

    public void testQuery_validSession_returnsData() {
        ContentValues sessionValues = createSessionValues("1,2", CALLER_UID);
        Uri sessionUri = mMockContentResolver.insert(Session.CONTENT_URI, sessionValues);

        createContactDataRow(1, "test_mimetype1");
        createContactDataRow(2, "test_mimetype2");
        createContactDataRow(3, "test_mimetype3");

        Cursor cursor =
                mMockContentResolver.query(
                        sessionUri, new String[] {Data._ID}, null, null, Data._ID + " ASC");

        assertNotNull(cursor);
        List<Long> ids = new ArrayList<>();
        while (cursor.moveToNext()) {
            ids.add(cursor.getLong(cursor.getColumnIndexOrThrow(Data._ID)));
        }
        cursor.close();
        assertEquals(2, ids.size());
        assertTrue(ids.contains(1L));
        assertTrue(ids.contains(2L));
    }

    public void testQuery_validSessionWithCallerSelection_throwsException() {
        ContentValues sessionValues = createSessionValues("3,4", CALLER_UID);
        Uri sessionUri = mMockContentResolver.insert(Session.CONTENT_URI, sessionValues);

        final String targetMimetype = "target_mimetype";
        createContactDataRow(3, targetMimetype);
        createContactDataRow(4, "other_mimetype");

        String callerSelection = Data.MIMETYPE + " = ?";
        String[] callerArgs = {targetMimetype};

        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        mMockContentResolver.query(
                                sessionUri,
                                new String[] {Data._ID, Data.MIMETYPE},
                                callerSelection,
                                callerArgs,
                                null));
    }

    public void testQuery_multipleSessionsWithOverlappingData_returnsCorrectData() {
        ContentValues sessionValues1 = createSessionValues("1,2", CALLER_UID);
        Uri sessionUri1 = mMockContentResolver.insert(Session.CONTENT_URI, sessionValues1);
        ContentValues sessionValues2 = createSessionValues("2,3", CALLER_UID);
        Uri sessionUri2 = mMockContentResolver.insert(Session.CONTENT_URI, sessionValues2);
        createContactDataRow(1, "mimetype1");
        createContactDataRow(2, "mimetype2");
        createContactDataRow(3, "mimetype3");

        Cursor cursor1 =
                mMockContentResolver.query(
                        sessionUri1, new String[] {Data._ID}, null, null, Data._ID + " ASC");

        assertNotNull(cursor1);
        List<Long> ids1 = new ArrayList<>();
        while (cursor1.moveToNext()) {
            ids1.add(cursor1.getLong(cursor1.getColumnIndexOrThrow(Data._ID)));
        }
        cursor1.close();
        assertEquals(2, ids1.size());
        assertTrue(ids1.contains(1L));
        assertTrue(ids1.contains(2L));

        Cursor cursor2 =
                mMockContentResolver.query(
                        sessionUri2, new String[] {Data._ID}, null, null, Data._ID + " ASC");

        assertNotNull(cursor2);
        List<Long> ids2 = new ArrayList<>();
        while (cursor2.moveToNext()) {
            ids2.add(cursor2.getLong(cursor2.getColumnIndexOrThrow(Data._ID)));
        }
        cursor2.close();
        assertEquals(2, ids2.size());
        assertTrue(ids2.contains(2L));
        assertTrue(ids2.contains(3L));
    }

    public void testQuery_projectionWithNonExistentColumn_returnsDataForExistingColumns() {
        ContentValues sessionValues = createSessionValues("1", CALLER_UID);
        Uri sessionUri = mMockContentResolver.insert(Session.CONTENT_URI, sessionValues);
        createContactDataRow(1, "mimetype1");

        Cursor cursor =
                mMockContentResolver.query(
                        sessionUri,
                        new String[] {Data._ID, "non_existent_column"},
                        null,
                        null,
                        null);

        assertNotNull(cursor);
        assertTrue(cursor.moveToFirst());
        assertEquals(1, cursor.getLong(cursor.getColumnIndexOrThrow(Data._ID)));
        int nonExistentColumnIndex = cursor.getColumnIndex("non_existent_column");
        assertTrue(nonExistentColumnIndex >= 0); // Column should exist in cursor's schema
        assertNull(cursor.getString(nonExistentColumnIndex)); // Value should be null
        cursor.close();
    }

    public void testQuery_sessionNotFound_returnsNull() {
        Uri nonExistentUri =
                Session.CONTENT_URI.buildUpon().appendPath("non-existent-uuid").build();
        Cursor cursor =
                mMockContentResolver.query(
                        nonExistentUri, new String[] {Data._ID}, null, null, null);
        assertNull(cursor);
    }

    public void testQuery_baseUri_returnsNull() {
        Cursor cursor =
                mMockContentResolver.query(
                        Session.CONTENT_URI, new String[] {Data._ID}, null, null, null);
        assertNull(cursor);
    }

    public void testQuery_invalidUriPath_throwsException() {
        Uri invalidUri =
                Uri.parse(
                        "content://"
                                + ContactsPickerSessionContract.AUTHORITY
                                + "/sessions/invalid/extra");

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mMockContentResolver.query(
                                invalidUri, new String[] {Data._ID}, null, null, null));
    }

    public void testQuery_differentCallerUid_throwsSecurityException() {
        // A UID different from our test process
        final int sessionOwnerUid = CALLER_UID + 1;
        ContentValues values = createSessionValues("1", sessionOwnerUid);
        final Uri sessionUri = mMockContentResolver.insert(Session.CONTENT_URI, values);

        SecurityException exception =
                assertThrows(
                        SecurityException.class,
                        () ->
                                mMockContentResolver.query(
                                        sessionUri, new String[] {Data._ID}, null, null, null));

        assertTrue(
                exception
                        .getMessage()
                        .startsWith("Calling UID " + CALLER_UID + " does not match session owner"));
    }

    public void testUpdate_throwsUnsupportedOperationException() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> mMockContentResolver.update(Session.CONTENT_URI, null, null, null));
    }

    public void testDelete_throwsUnsupportedOperationException() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> mMockContentResolver.delete(Session.CONTENT_URI, null, null));
    }

    public void testCall_cleanupStaleSessions_removesOldSession() {
        ContentValues values = createSessionValues("1", CALLER_UID);
        Uri sessionUri = mMockContentResolver.insert(Session.CONTENT_URI, values);
        String sessionUid = sessionUri.getLastPathSegment();

        // Manually update timestamp to be stale (older than 24 hours)
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        ContentValues oldTimestampValues = new ContentValues();
        long staleTimestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2);
        oldTimestampValues.put(SessionColumns.CREATED_AT, staleTimestamp);
        int updatedRows =
                db.update(
                        Tables.SESSIONS,
                        oldTimestampValues,
                        SessionColumns.SESSION_UID + " = ?",
                        new String[] {sessionUid});
        assertEquals(1, updatedRows);

        assertNull(
                mMockContentResolver.call(
                        ContactsPickerSessionContract.AUTHORITY,
                        "cleanupStaleSessions",
                        null,
                        null));

        Cursor cursor =
                mMockContentResolver.query(sessionUri, new String[] {Data._ID}, null, null, null);
        assertNull(cursor);
    }

    public void testCall_cleanupStaleSessions_doesNotRemoveRecentSession() {
        ContentValues values = createSessionValues("1", CALLER_UID);
        Uri sessionUri = mMockContentResolver.insert(Session.CONTENT_URI, values);

        assertNull(
                mMockContentResolver.call(
                        ContactsPickerSessionContract.AUTHORITY,
                        "cleanupStaleSessions",
                        null,
                        null));

        // Since this session was just created, it should not be stale and should not be removed.
        Cursor cursor =
                mMockContentResolver.query(sessionUri, new String[] {Data._ID}, null, null, null);
        assertNotNull(cursor);
        cursor.close();
    }

    public void testCall_invalidMethod_doesNothing() {
        ContentValues values = createSessionValues("1", CALLER_UID);
        Uri sessionUri = mMockContentResolver.insert(Session.CONTENT_URI, values);

        assertNull(
                mMockContentResolver.call(
                        ContactsPickerSessionContract.AUTHORITY, "invalidMethod", null, null));

        Cursor cursor =
                mMockContentResolver.query(sessionUri, new String[] {Data._ID}, null, null, null);
        assertNotNull(cursor);
        cursor.close();
    }

    public void testGetType_returnsCorrectTypes() {
        ContentValues values = createSessionValues("1", CALLER_UID);
        Uri sessionUri = mMockContentResolver.insert(Session.CONTENT_URI, values);

        assertEquals(Session.CONTENT_TYPE, getProvider().getType(sessionUri));
        assertNull(getProvider().getType(Session.CONTENT_URI));

        Uri invalidUri =
                Uri.parse("content://" + ContactsPickerSessionContract.AUTHORITY + "/invalid");
        assertNull(getProvider().getType(invalidUri));
    }

    private ContentValues createSessionValues(String dataRowIds, int callerUid) {
        ContentValues values = new ContentValues();
        values.put(ContactsPickerSessionContract.Session.CONTACT_DATA_IDS, dataRowIds);
        values.put(ContactsPickerSessionContract.Session.SESSION_REQUESTER_UID, callerUid);
        return values;
    }

    private void createContactDataRow(long id, String mimetype) {
        ContentValues values = new ContentValues();
        values.put(Data._ID, id);
        values.put(Data.MIMETYPE, mimetype);
        mMockContentResolver.insert(DATA_CONTENT_URI, values);
    }
}
