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

import android.app.compat.CompatChanges;
import android.os.UserHandle;

/**
 * Functional interface for the 3 argument overload of {@link CompatChanges.isChangeEnabled}.
 *
 * This is purely for testability; {@link android.compat.testing.PlatformCompatChangeRule} can be
 * used for more straightforward cases but that rule only enables/disables changes for the
 * application under test (i.e. the CP2 package). It won't work for code that checks compatibility
 * of some arbritrary (possibly faked) package name.
 */
interface CompatChangeEnabledFunction3 {
    boolean isChangeEnabled(long changeId, String packageName, UserHandle user);
}
