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

package com.android.providers.contacts;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AuthenticatorDescription;
import android.content.ContentValues;
import android.content.SyncAdapterType;
import android.net.Uri;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.ContactsContract;
import android.provider.ContactsContract.RawContacts.DefaultAccount.DefaultAccountAndState;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.os.AtomsProto;
import com.android.os.AtomsProto.ContactsProviderStatusReported.AccountSyncMode;
import com.android.os.AtomsProto.ContactsProviderStatusReported.CallerAccountTypeOwnership;
import com.android.providers.contacts.testutil.RawContactUtil;
import com.android.providers.contacts.util.LogUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/** Tests of the StatsLog logging performed in {@link ContactsProvider2}. */
@RunWith(AndroidJUnit4.class)
public class ContactsProvider2LoggingTest extends BaseContactsProvider2Test {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private RecordingContactsProviderStatsLog mContactsProviderStatsLog;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        mActor.addPermissions(Manifest.permission.SET_DEFAULT_ACCOUNT_FOR_CONTACTS);
        mContactsProviderStatsLog = new RecordingContactsProviderStatsLog();
        LogUtils.setContactsProviderStatsLogForTesting(mContactsProviderStatsLog);
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testInsertRawContact_localAccount_logsEventWithAccountTypeAndOrigin() {
        ContentValues values = new ContentValues();
        values.putNull(ContactsContract.RawContacts.ACCOUNT_NAME);
        values.putNull(ContactsContract.RawContacts.ACCOUNT_TYPE);
        Uri unused = mResolver.insert(ContactsContract.RawContacts.CONTENT_URI, values);

        List<AtomsProto.ContactsProviderStatusReported> loggedRawContactInsertEvents =
                mContactsProviderStatsLog.getLoggedEvents(
                        RecordingContactsProviderStatsLog::isRawContactInsertEvent);
        assertEquals(1, loggedRawContactInsertEvents.size());
        AtomsProto.ContactsProviderStatusReported event = loggedRawContactInsertEvents.getFirst();
        assertEquals("", event.getAccountType());
        assertEquals(
                AtomsProto.ContactsProviderStatusReported.AccountDataOrigin.ACCOUNT_DATA_ORIGIN_LOCAL,
                event.getAccountDataOrigin());
    }

    @Test
    public void testInsertRawContact_cloudAccount_logsEventWithAccountTypeAndOrigin() {
        mActor.setAccounts(new Account[]{mAccount});

        long unused = RawContactUtil.createRawContactWithName(mResolver, mAccount);

        List<AtomsProto.ContactsProviderStatusReported> loggedRawContactInsertEvents =
                mContactsProviderStatsLog.getLoggedEvents(
                        RecordingContactsProviderStatsLog::isRawContactInsertEvent);
        assertEquals(1, loggedRawContactInsertEvents.size());
        AtomsProto.ContactsProviderStatusReported event = loggedRawContactInsertEvents.getFirst();
        assertEquals(mAccount.type, event.getAccountType());
        assertEquals(
                AtomsProto.ContactsProviderStatusReported.AccountDataOrigin.ACCOUNT_DATA_ORIGIN_CLOUD,
                event.getAccountDataOrigin());
    }

    @Test
    public void testInsertRawContact_logsEventWithDcaState() {
        mActor.setAccounts(new Account[]{mAccount});
        ContactsContract.RawContacts.DefaultAccount.setDefaultAccountForNewContacts(mResolver,
                DefaultAccountAndState.ofCloud(mAccount));

        long unused = RawContactUtil.createRawContactWithName(mResolver);

        List<AtomsProto.ContactsProviderStatusReported> loggedRawContactInsertEvents =
                mContactsProviderStatsLog.getLoggedEvents(
                        RecordingContactsProviderStatsLog::isRawContactInsertEvent);
        assertEquals(1, loggedRawContactInsertEvents.size());
        AtomsProto.ContactsProviderStatusReported event = loggedRawContactInsertEvents.getFirst();
        assertEquals(DefaultAccountAndState.DEFAULT_ACCOUNT_STATE_CLOUD,
                event.getDefaultAccountState());
    }

    @Test
    public void testInsertRawContact_logsEventWithAccountOwnershipAndSyncMode() {
        mActor.setAccounts(new Account[]{mAccount});
        getContactsProvider().setSyncAdapterTypesForTest(new SyncAdapterType[]{
                new SyncAdapterType(ContactsContract.AUTHORITY, mAccount.type, false, true)});
        // Since there are no authenticators the account will be NOT_OWNED.
        mActor.setAuthenticators(new AuthenticatorDescription[0]);

        RawContactUtil.createRawContactWithName(mResolver, mAccount);

        List<AtomsProto.ContactsProviderStatusReported> loggedRawContactInsertEvents =
                mContactsProviderStatsLog.getLoggedEvents(
                        RecordingContactsProviderStatsLog::isRawContactInsertEvent);
        assertEquals(1, loggedRawContactInsertEvents.size());
        AtomsProto.ContactsProviderStatusReported event = loggedRawContactInsertEvents.getFirst();
        assertEquals(
                CallerAccountTypeOwnership.CALLER_ACCOUNT_TYPE_OWNERSHIP_NOT_OWNED,
                event.getCallerAccountTypeOwnership());
        assertEquals(
                AccountSyncMode.ACCOUNT_SYNC_MODE_BIDIRECTIONAL,
                event.getAccountSyncMode());
    }

}
