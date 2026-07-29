/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.providers.contacts.util;

import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.Identity;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.Relation;
import android.provider.ContactsContract.CommonDataKinds.SipAddress;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.util.Log;

import com.android.providers.contacts.ContactsProvider2;

import com.google.common.collect.Sets;

import java.util.Set;

public class PccUtils {
    public static final String TAG = ContactsProvider2.TAG;

    /**
     * A set of MIME types from {@link android.provider.ContactsContract.CommonDataKinds} that
     * Private Compute Core (PCC) UIDs are explicitly allowed to write to. These are generally the
     * core contact data fields. Writes to MIME types not in this set will be logged as warnings,
     * and writes to specific denylisted types (like Note) will throw a SecurityException.
     */
    private static final Set<String> ALLOWLISTED_STANDARD_MIME_TYPES_FOR_PCC_WRITES =
            Sets.newHashSet(
                    Email.CONTENT_ITEM_TYPE,
                    Phone.CONTENT_ITEM_TYPE,
                    Event.CONTENT_ITEM_TYPE,
                    Im.CONTENT_ITEM_TYPE,
                    Organization.CONTENT_ITEM_TYPE,
                    Relation.CONTENT_ITEM_TYPE,
                    SipAddress.CONTENT_ITEM_TYPE,
                    StructuredPostal.CONTENT_ITEM_TYPE,
                    Website.CONTENT_ITEM_TYPE,
                    StructuredName.CONTENT_ITEM_TYPE,
                    Nickname.CONTENT_ITEM_TYPE,
                    Photo.CONTENT_ITEM_TYPE,
                    GroupMembership.CONTENT_ITEM_TYPE,
                    Identity.CONTENT_ITEM_TYPE);

    private PccUtils() {}

    /** Checks if the given MIME type is in the set of types allowlisted for PCC writes. */
    private static boolean isAllowlistedMimeTypeForPcc(String mimeType) {
        return ALLOWLISTED_STANDARD_MIME_TYPES_FOR_PCC_WRITES.contains(mimeType);
    }

    /**
     * Validates data write operations when the calling UID is identified as a Private Compute Core
     * (PCC) UID. This method enforces restrictions on what data types PCC UIDs are allowed to write
     * to Contacts Database.
     *
     * @param mimeType The MIME type of the data being written.
     * @throws SecurityException if a PCC UID attempts to write to a disallowed mime type (e.g.,
     *     Note).
     */
    public static void validateDataWriteForPcc(String mimeType) {
        if (Note.CONTENT_ITEM_TYPE.equals(mimeType)) {
            throw new SecurityException("PCC UIDs cannot write to " + mimeType);
        }
        if (!isAllowlistedMimeTypeForPcc(mimeType)) {
            Log.w(TAG, "PCC UID is writing to a non-standard mime type: " + mimeType);
        }
    }
}
