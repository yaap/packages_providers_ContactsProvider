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

import android.app.privatecompute.flags.Flags;
import android.content.Context;
import android.os.Process;
import android.os.UserHandle;

/** Utility for comparing UIDs with awareness of Private Compute Core (PCC) isolated processes. */
public final class PccAwareUidComparator {

    private PccAwareUidComparator() {}

    /**
     * Returns true if the two UIDs belong to the same application, taking into account isolated PCC
     * processes.
     */
    public static boolean isSameApp(Context context, int uid1, int uid2) {
        int appUid1 = uid1;
        if (Flags.enablePccFrameworkSupport() && Process.isPrivateComputeCoreUid(uid1)) {
            try {
                appUid1 = context.getPackageManager().getAppUidForPrivateComputeCoreUid(uid1);
            } catch (RuntimeException e) {
                // Default to the original UID if the system server is unreachable
            }
        }
        int appUid2 = uid2;
        if (Flags.enablePccFrameworkSupport() && Process.isPrivateComputeCoreUid(uid2)) {
            try {
                appUid2 = context.getPackageManager().getAppUidForPrivateComputeCoreUid(uid2);
            } catch (RuntimeException e) {
                // Default to the original UID if the system server is unreachable
            }
        }
        return UserHandle.isSameApp(appUid1, appUid2);
    }
}
