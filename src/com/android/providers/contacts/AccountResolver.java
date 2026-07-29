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

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.ContactsContract.RawContacts.DefaultAccount.DefaultAccountAndState;
import android.provider.ContactsContract.SimAccount;
import android.text.TextUtils;
import android.util.Log;

import com.android.providers.contacts.InsertAccountValidator.ValidationResultWithDetails;

import java.util.List;

public class AccountResolver {
    public static final String UNABLE_TO_WRITE_TO_LOCAL_OR_SIM_EXCEPTION_MESSAGE =
            "Cannot add contacts to local or SIM accounts when default account is set to cloud";
    private static final String TAG = "AccountResolver";

    private final ContactsDatabaseHelper mDbHelper;
    private final DefaultAccountManager mDefaultAccountManager;
    private final AccountManager mAccountManager;

    public AccountResolver(ContactsDatabaseHelper dbHelper,
            DefaultAccountManager defaultAccountManager, AccountManager accountManager) {
        mDbHelper = dbHelper;
        mDefaultAccountManager = defaultAccountManager;
        mAccountManager = accountManager;
    }

    /**
     * Resolves the account to use for a contact (or group) creation operation based on the provided
     * validationResult.
     *
     * @param validationResult                        The result of validating the account
     *                                                specified
     *                                                for the operation.
     *                                                See
     *                                                {@link
     *
     *
     *
     *
     *                                            #getAccountValidationResultForContactAddition(Uri,
     *                                                ContentValues, boolean)}
     * @param applyDefaultAccount                     Whether to use the default account if no
     *                                                account was specified.
     * @param shouldValidateAccountForContactAddition Whether to validate the account accepts new
     *                                                contacts.
     * @throws IllegalArgumentException if the validation result indicates a failure and the failure
     *                                  is enforced.
     */
    public AccountWithDataSet resolveAccountWithDataSet(
            ValidationResultWithDetails validationResult, boolean applyDefaultAccount,
            boolean shouldValidateAccountForContactAddition) {
        return switch (validationResult.getValidationResult()) {
            case PASS -> {
                AccountWithDataSet account = validationResult.getRequestedAccount();
                if (account != null && account.isLocalAccount()) {
                    // This is a little confusing but the existing ContactProvider2 logic
                    // represents the
                    // local account with a null value until the last step and then converts it to
                    // AccountWithDataSet.LOCAL. It should be equivalent to convert to
                    // AccountWithDataSet.LOCAL eagerly but for now we'll do it this way.
                    yield null;
                } else {
                    yield account;
                }
            }
            case ACCOUNT_NOT_SPECIFIED -> {
                int defaultAccountState = validationResult.getDefaultAccountAndState().getState();

                if (!applyDefaultAccount || defaultAccountState
                        == DefaultAccountAndState.DEFAULT_ACCOUNT_STATE_NOT_SET
                        || defaultAccountState
                        == DefaultAccountAndState.DEFAULT_ACCOUNT_STATE_LOCAL) {
                    // See comment in above PASS case; null means use the local account in
                    // ContactsProvider2.
                    yield null;
                } else {
                    yield AccountWithDataSet.get(
                            validationResult.getDefaultAccountAndState().getAccount(), null);
                }
            }
            case FAILURE_INVALID_ACCOUNT_ARGS -> throw new IllegalArgumentException(
                    mDbHelper.exceptionMessage(
                            "Must specify both or neither of ACCOUNT_NAME and ACCOUNT_TYPE",
                            validationResult.getUri()));
            case FAILURE_ACCOUNT_NOT_MATCHING -> throw new IllegalArgumentException(
                    mDbHelper.exceptionMessage(
                            "When both specified, ACCOUNT_NAME and ACCOUNT_TYPE must match",
                            validationResult.getUri()));
            case FAILURE_DEFAULT_ACCOUNT_CLOUD_RESTRICTION -> {
                AccountWithDataSet account = validationResult.getRequestedAccount();
                if (shouldValidateAccountForContactAddition) {
                    throw new IllegalArgumentException(
                            UNABLE_TO_WRITE_TO_LOCAL_OR_SIM_EXCEPTION_MESSAGE);
                } else if (account != null && account.isLocalAccount()) {
                    yield null;
                } else {
                    yield account;
                }
            }
            // Note: not enforcing account existence yet.
            case FAILURE_ACCOUNT_NOT_FOUND -> validationResult.getRequestedAccount();
        };
    }

    /**
     * Checks that the provided validationResult is valid and throws an appropriate exception if
     * needed.
     */
    public void requireValidAccount(ValidationResultWithDetails validationResult,
            boolean shouldValidateAccountForContactAddition) {
        // Attempting to resolve the account that was specified will throw if it was invalid.
        AccountWithDataSet unused = resolveAccountWithDataSet(validationResult,
                /** applyDefaultAccount=*/false, shouldValidateAccountForContactAddition);
    }

    /**
     * Checks that the account specified by the provided Uri and values is valid for creating
     * contacts.
     *
     * @return the validation result which indicates whether the account is valid and details
     * about the account validity.
     */
    public ValidationResultWithDetails getAccountValidationResultForContactAddition(Uri uri,
            ContentValues values, boolean allowSimWriteOnCloudDcaBypassEnabled) {
        InsertAccountValidator validator = new InsertAccountValidator(
                mDefaultAccountManager.pullDefaultAccount(), mDbHelper.getAllSimAccounts(),
                mAccountManager.getAccounts());
        validator.setAllowSimWriteOnCloudDcaBypassEnabled(allowSimWriteOnCloudDcaBypassEnabled);
        return validator.getValidationResult(uri, values);
    }

    /**
     * Checks that the account is valid for creating contacts.
     *
     * @return the validation result which indicates whether the account is valid and details
     * about the account validity.
     */
    public ValidationResultWithDetails getAccountValidationResultForContactAddition(
            String accountName, String accountType, String dataSet,
            boolean allowSimWriteOnCloudDcaBypassEnabled) {
        InsertAccountValidator validator = new InsertAccountValidator(
                mDefaultAccountManager.pullDefaultAccount(), mDbHelper.getAllSimAccounts(),
                mAccountManager.getAccounts());
        validator.setAllowSimWriteOnCloudDcaBypassEnabled(allowSimWriteOnCloudDcaBypassEnabled);
        return validator.getValidationResult(accountName, accountType, dataSet);
    }

    /**
     * Checks if new contacts in specified account is accepted.
     *
     * <p>This method checks if contacts can be written to the given account based on the
     * current default account settings. It throws an {@link IllegalArgumentException} if
     * the contacts cannot be created in the given account .</p>
     *
     * @param accountName The name of the account to check.
     * @param accountType The type of the account to check.
     * @throws IllegalArgumentException if either of the following conditions are met:
     *                                  <ul>
     *                                      <li>Only one of <code>accountName</code> or
     *                                      <code>accountType</code> is
     *                                          specified.</li>
     *                                      <li>The default account is set to cloud and the
     *                                      specified account is a local
     *                                          (device or SIM) account.</li>
     *                                  </ul>
     */
    public void validateAccountForContactAddition(String accountName, String accountType,
            boolean shouldValidateAccountForContactAddition,
            boolean allowSimWriteOnCloudDcaBypassEnabled) {
        if (shouldValidateAccountForContactAddition) {
            if (TextUtils.isEmpty(accountName) ^ TextUtils.isEmpty(accountType)) {
                throw new IllegalArgumentException(
                        "Must specify both or neither of ACCOUNT_NAME and ACCOUNT_TYPE");
            }
        }

        if (TextUtils.isEmpty(accountName)) {
            validateAccountForContactAdditionInternal(/*account=*/null,
                    shouldValidateAccountForContactAddition, allowSimWriteOnCloudDcaBypassEnabled);
        } else {
            validateAccountForContactAdditionInternal(new Account(accountName, accountType),
                    shouldValidateAccountForContactAddition, allowSimWriteOnCloudDcaBypassEnabled);
        }
    }


    private void validateAccountForContactAdditionInternal(Account account,
            boolean enforceCloudDefaultAccountRestriction,
            boolean allowSimWriteOnCloudDcaBypassEnabled) throws IllegalArgumentException {
        DefaultAccountAndState defaultAccount = mDefaultAccountManager.pullDefaultAccount();

        if (defaultAccount.getState() == DefaultAccountAndState.DEFAULT_ACCOUNT_STATE_CLOUD) {
            if (allowSimWriteOnCloudDcaBypassEnabled ? isDeviceAccount(account)
                    : isDeviceOrSimAccount(account)) {
                if (enforceCloudDefaultAccountRestriction) {
                    throw new IllegalArgumentException(
                            UNABLE_TO_WRITE_TO_LOCAL_OR_SIM_EXCEPTION_MESSAGE);
                } else {
                    Log.w(TAG, "Cloud default account: Local/SIM contact creation allowed (target "
                            + "SDK <36), but restricted in target SDK 36+. Avoid "
                            + "local/SIM writes in target SDK 36+.");
                }
            }
        }
    }

    private boolean isDeviceOrSimAccount(Account account) {
        AccountWithDataSet accountWithDataSet = account == null ? new AccountWithDataSet(null, null,
                null) : new AccountWithDataSet(account.name, account.type, null);

        List<SimAccount> simAccounts = mDbHelper.getAllSimAccounts();
        return accountWithDataSet.isLocalAccount() || accountWithDataSet.inSimAccounts(simAccounts);
    }

    private boolean isDeviceAccount(Account account) {
        AccountWithDataSet accountWithDataSet = account == null ? new AccountWithDataSet(null, null,
                null) : new AccountWithDataSet(account.name, account.type, null);

        return accountWithDataSet.isLocalAccount();
    }
}
