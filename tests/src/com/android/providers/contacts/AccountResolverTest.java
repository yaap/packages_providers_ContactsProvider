/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.provider.ContactsContract.SimAccount.SDN_EF_TYPE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.provider.ContactsContract;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.RawContacts.DefaultAccount.DefaultAccountAndState;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@SmallTest
@RunWith(JUnit4.class)
public class AccountResolverTest {
    @Mock
    private ContactsDatabaseHelper mDbHelper;
    @Mock
    private DefaultAccountManager mDefaultAccountManager;

    private AccountResolver mAccountResolver;

    private static final Account SIM_ACCOUNT_1 = new Account("simName1", "SIM");

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Context context = ApplicationProvider.getApplicationContext();
        mAccountResolver = new AccountResolver(mDbHelper, mDefaultAccountManager,
                AccountManager.get(context));

        when(mDbHelper.getAllSimAccounts()).thenReturn(List.of(new ContactsContract.SimAccount(
                SIM_ACCOUNT_1.name, SIM_ACCOUNT_1.type, 1, SDN_EF_TYPE
        )));

    }

    @Test
    public void testResolveAccountWithDataSet_validationResultNotSpecifiedAndApplyDefaultAccountTrue_returnsDefaultAccount() {
        AccountWithDataSet resolved = mAccountResolver.resolveAccountWithDataSet(
                new InsertAccountValidator.ValidationResultWithDetails(
                        InsertAccountValidator.ValidationResult.ACCOUNT_NOT_SPECIFIED, null,
                        DefaultAccountAndState.ofCloud(new Account("account_name", "account_type")),
                        true, null, null), true, true);


        assertEquals(new AccountWithDataSet("account_name", "account_type", null), resolved);
    }

    @Test
    public void testResolveAccountWithDataSet_validationResultPass_returnsRequestedAccount() {
        AccountWithDataSet resolved = mAccountResolver.resolveAccountWithDataSet(
                new InsertAccountValidator.ValidationResultWithDetails(
                        InsertAccountValidator.ValidationResult.PASS,
                        new AccountWithDataSet("requested_name", "requested_type", null),
                        DefaultAccountAndState.ofCloud(new Account("dca_name", "dca_type")),
                        true, null, null), true, true);

        assertEquals(new AccountWithDataSet("requested_name", "requested_type", null), resolved);
    }

    @Test
    public void testResolveAccountWithDataSet_validationResultPassLocalAccount_returnsNull() {
        AccountWithDataSet resolved = mAccountResolver.resolveAccountWithDataSet(
                new InsertAccountValidator.ValidationResultWithDetails(
                        InsertAccountValidator.ValidationResult.PASS,
                        AccountWithDataSet.LOCAL,
                        DefaultAccountAndState.ofCloud(new Account("dca_name", "dca_type")),
                        false, null, null), true, true);

        assertNull(resolved);
    }

    @Test
    public void testResolveAccountWithDataSet_validationResultAccountNotSpecifiedAndApplyDefaultAccountFalse_returnsNull() {
        AccountWithDataSet resolved = mAccountResolver.resolveAccountWithDataSet(
                new InsertAccountValidator.ValidationResultWithDetails(
                        InsertAccountValidator.ValidationResult.ACCOUNT_NOT_SPECIFIED, null,
                        DefaultAccountAndState.ofCloud(new Account("account_name", "account_type")),
                        true, null, null), false, true);

        assertNull(resolved);
    }

    @Test
    public void testResolveAccountWithDataSet_validationResultAccountNotSpecifiedAndDefaultAccountNotSet_returnsNull() {
        AccountWithDataSet resolved = mAccountResolver.resolveAccountWithDataSet(
                new InsertAccountValidator.ValidationResultWithDetails(
                        InsertAccountValidator.ValidationResult.ACCOUNT_NOT_SPECIFIED, null,
                        DefaultAccountAndState.ofNotSet(),
                        true, null, null), true, true);

        assertNull(resolved);
    }

    @Test
    public void testResolveAccountWithDataSet_validationResultAccountNotSpecifiedAndDefaultAccountLocal_returnsNull() {
        AccountWithDataSet resolved = mAccountResolver.resolveAccountWithDataSet(
                new InsertAccountValidator.ValidationResultWithDetails(
                        InsertAccountValidator.ValidationResult.ACCOUNT_NOT_SPECIFIED, null,
                        DefaultAccountAndState.ofLocal(),
                        true, null, null), true, true);

        assertNull(resolved);
    }

    @Test
    public void testResolveAccountWithDataSet_validationResultFailureInvalidAccountArgs_throwsException() {
        when(mDbHelper.exceptionMessage(
                "Must specify both or neither of ACCOUNT_NAME and ACCOUNT_TYPE",
                RawContacts.CONTENT_URI))
                .thenReturn("Test Exception Message");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            mAccountResolver.resolveAccountWithDataSet(
                    new InsertAccountValidator.ValidationResultWithDetails(
                            InsertAccountValidator.ValidationResult.FAILURE_INVALID_ACCOUNT_ARGS,
                            null,
                            DefaultAccountAndState.ofNotSet(),
                            true, null, RawContacts.CONTENT_URI), true, true);
        });

        assertEquals("Test Exception Message", exception.getMessage());
    }

    @Test
    public void testResolveAccountWithDataSet_validationResultFailureAccountNotMatching_throwsException() {
        when(mDbHelper.exceptionMessage(
                "When both specified, ACCOUNT_NAME and ACCOUNT_TYPE must match",
                RawContacts.CONTENT_URI))
                .thenReturn("Test Exception Message");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            mAccountResolver.resolveAccountWithDataSet(
                    new InsertAccountValidator.ValidationResultWithDetails(
                            InsertAccountValidator.ValidationResult.FAILURE_ACCOUNT_NOT_MATCHING,
                            null,
                            DefaultAccountAndState.ofNotSet(),
                            true, null, RawContacts.CONTENT_URI), true, true);
        });

        assertEquals("Test Exception Message", exception.getMessage());
    }

    @Test
    public void testResolveAccountWithDataSet_validationResultFailureDefaultAccountCloudRestriction_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            mAccountResolver.resolveAccountWithDataSet(
                    new InsertAccountValidator.ValidationResultWithDetails(
                            InsertAccountValidator.ValidationResult.FAILURE_DEFAULT_ACCOUNT_CLOUD_RESTRICTION,
                            null,
                            DefaultAccountAndState.ofCloud(new Account("dca_name", "dca_type")),
                            true, null, RawContacts.CONTENT_URI), true, true);
        });

        assertEquals(
                "Cannot add contacts to local or SIM accounts when default account is set to cloud",
                exception.getMessage());
    }

    @Test
    public void testResolveAccountWithDataSet_validationResultFailureDefaultAccountCloudRestrictionButSkipAccountValidation_noException() {
        AccountWithDataSet resolved = mAccountResolver.resolveAccountWithDataSet(
                new InsertAccountValidator.ValidationResultWithDetails(
                        InsertAccountValidator.ValidationResult.FAILURE_DEFAULT_ACCOUNT_CLOUD_RESTRICTION,
                        AccountWithDataSet.LOCAL,
                        DefaultAccountAndState.ofCloud(new Account("dca_name", "dca_type")),
                        true, null, RawContacts.CONTENT_URI), true, false);

        // Expect null because the original request was for a local, which is effectively
        // treated as a local account (null AccountWithDataSet) when validation is skipped.
        assertNull(resolved);
    }

    @Test
    public void testResolveAccountWithDataSet_validationResultFailureDefaultAccountCloudRestrictionButSkipAccountValidationAndNotLocal_returnsAccount() {
        AccountWithDataSet simAccount = new AccountWithDataSet("sim", "sim_type", null);
        AccountWithDataSet resolved = mAccountResolver.resolveAccountWithDataSet(
                new InsertAccountValidator.ValidationResultWithDetails(
                        InsertAccountValidator.ValidationResult.FAILURE_DEFAULT_ACCOUNT_CLOUD_RESTRICTION,
                        simAccount,
                        DefaultAccountAndState.ofCloud(new Account("dca_name", "dca_type")),
                        true, null, RawContacts.CONTENT_URI), true, false);

        assertEquals(simAccount, resolved);
    }

    @Test
    public void testResolveAccountWithDataSet_validationResultFailureAccountNotFound_returnsRequestedAccount() {
        AccountWithDataSet resolved = mAccountResolver.resolveAccountWithDataSet(
                new InsertAccountValidator.ValidationResultWithDetails(
                        InsertAccountValidator.ValidationResult.FAILURE_ACCOUNT_NOT_FOUND,
                        new AccountWithDataSet("account_name", "account_type", null),
                        DefaultAccountAndState.ofNotSet(),
                        false, null, null), true, true);

        assertEquals(new AccountWithDataSet("account_name", "account_type", null), resolved);
    }

    @Test
    public void testValidateAccountIsWritable_bothAccountNameAndTypeAreNullOrEmpty_NoException() {
        when(mDefaultAccountManager.pullDefaultAccount()).thenReturn(
                DefaultAccountAndState.ofNotSet());

        mAccountResolver.validateAccountForContactAddition("", "", true, false);
        mAccountResolver.validateAccountForContactAddition(null, "", true, false);
        mAccountResolver.validateAccountForContactAddition("", null, true, false);
        mAccountResolver.validateAccountForContactAddition(null, null, true, false);
        // No exception expected
    }

    @Test
    public void testValidateAccountIsWritable_eitherAccountNameOrTypeEmpty_ThrowsException() {
        when(mDefaultAccountManager.pullDefaultAccount()).thenReturn(
                DefaultAccountAndState.ofNotSet());

        assertThrows(IllegalArgumentException.class, () -> {
            mAccountResolver.validateAccountForContactAddition("accountName", "", true, false);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            mAccountResolver.validateAccountForContactAddition("accountName", null, true, false);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            mAccountResolver.validateAccountForContactAddition("", "accountType", true, false);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            mAccountResolver.validateAccountForContactAddition(null, "accountType", true, false);
        });
    }

    @Test
    public void testValidateAccountIsWritable_defaultAccountIsCloud() {
        when(mDefaultAccountManager.pullDefaultAccount()).thenReturn(
                DefaultAccountAndState.ofCloud(
                        new Account("test_user1", "com.google")));

        mAccountResolver.validateAccountForContactAddition("test_user1", "com.google", true, false);
        mAccountResolver.validateAccountForContactAddition("test_user2", "com.google", true, false);
        mAccountResolver.validateAccountForContactAddition("test_user3", "com.whatsapp", true,
                false);
        assertThrows(IllegalArgumentException.class, () ->
                mAccountResolver.validateAccountForContactAddition("", "", true, false));
        assertThrows(IllegalArgumentException.class, () ->
                mAccountResolver.validateAccountForContactAddition(null, null, true, false));
        // No exception expected
    }

    @Test
    public void testValidateAccountIsWritable_defaultAccountIsDevice() {
        when(mDefaultAccountManager.pullDefaultAccount()).thenReturn(
                DefaultAccountAndState.ofLocal());

        mAccountResolver.validateAccountForContactAddition("test_user1", "com.google", true, false);
        mAccountResolver.validateAccountForContactAddition("test_user2", "com.google", true, false);
        mAccountResolver.validateAccountForContactAddition("test_user3", "com.whatsapp", true,
                false);
        mAccountResolver.validateAccountForContactAddition("", "", true, false);
        mAccountResolver.validateAccountForContactAddition(null, null, true, false);
        // No exception expected
    }


    @Test
    public void testValidateAccountIsWritable_defaultAccountIsNotSet() {
        when(mDefaultAccountManager.pullDefaultAccount()).thenReturn(
                DefaultAccountAndState.ofNotSet());

        mAccountResolver.validateAccountForContactAddition("test_user1", "com.google", true, false);
        mAccountResolver.validateAccountForContactAddition("test_user2", "com.google", true, false);
        mAccountResolver.validateAccountForContactAddition("test_user3", "com.whatsapp", true,
                false);
        mAccountResolver.validateAccountForContactAddition("", "", true, false);
        mAccountResolver.validateAccountForContactAddition(null, null, true, false);
        // No exception expected
    }
}
