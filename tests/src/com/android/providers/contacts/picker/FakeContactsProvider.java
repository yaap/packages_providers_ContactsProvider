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
import android.content.Context;
import android.content.UriMatcher;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Data;
import android.util.Log;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A simple mock provider to handle Data.CONTENT_URI for testing purposes. */
public class FakeContactsProvider extends ContentProvider {
    private static final String TAG = "FakeContactsProvider";
    private static final int DATA = 1;
    private final UriMatcher mUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    private final Map<Long, ContentValues> mData = new HashMap<>();
    private long mNextId = 1;

    public FakeContactsProvider(Context context) {
        mUriMatcher.addURI(ContactsContract.AUTHORITY, "data", DATA);
        ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = ContactsContract.AUTHORITY;
        attachInfo(context, providerInfo);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        if (mUriMatcher.match(uri) != DATA) {
            Log.w(TAG, "FakeContactsProvider query: Unknown URI: " + uri);
            return null;
        }

        MatrixCursor cursor = new MatrixCursor(projection);
        if (selection == null) {
            return cursor; // No IDs to filter by
        }

        Set<String> allowedIds = new HashSet<>();
        String targetMimeType = null;

        // Parse the dataRowIds from the selection string
        // Extract everything between the brackets of "Data._ID + IN (...)"
        int start = selection.indexOf(Data._ID + " IN (") + (Data._ID + " IN (").length();
        int end = selection.indexOf(")", start);
        String idLiteralList = selection.substring(start, end);

        for (String id : idLiteralList.split(",")) {
            allowedIds.add(id.trim());
        }

        // Check for MIME type filter, which would be the last argument if present
        if (selection.contains(Data.MIMETYPE + " = ?") && selectionArgs.length > 0) {
            targetMimeType = selectionArgs[selectionArgs.length - 1];
        }

        for (ContentValues values : mData.values()) {
            String currentId = values.getAsString(Data._ID);
            if (allowedIds.contains(currentId)) {
                if (targetMimeType != null) {
                    if (!targetMimeType.equals(values.getAsString(Data.MIMETYPE))) {
                        continue;
                    }
                }
                Object[] row = new Object[projection.length];
                for (int i = 0; i < projection.length; i++) {
                    if (values.containsKey(projection[i])) {
                        row[i] = values.get(projection[i]);
                    } else {
                        row[i] = null; // Handle non-existent columns in projection
                    }
                }
                cursor.addRow(row);
            }
        }
        Log.d(
                TAG,
                "MockContactsProvider query: uri="
                        + uri
                        + ", selection="
                        + selection
                        + ", selectionArgs="
                        + Arrays.toString(selectionArgs)
                        + ", returning "
                        + cursor.getCount()
                        + " rows");
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (mUriMatcher.match(uri) != DATA) {
            throw new IllegalArgumentException("FakeContactsProvider insert: Unknown URI: " + uri);
        }
        long id = values.getAsLong(Data._ID) != null ? values.getAsLong(Data._ID) : mNextId++;
        values.put(Data._ID, id);
        mData.put(id, new ContentValues(values)); // Store a copy
        Log.d(TAG, "Inserted into MockContactsProvider: " + values);
        return uri.buildUpon().appendPath(String.valueOf(id)).build();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    public void clearData() {
        mData.clear();
        mNextId = 1;
    }
}
