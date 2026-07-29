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

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.TimeUnit;

/** Schedules the periodic cleanup job for the contacts picker sessions. */
public class ContactsPickerJobScheduler {
    private static final String TAG = "ContactsPickerJob";
    private static final boolean VERBOSE_LOGGING = Log.isLoggable(TAG, Log.VERBOSE);

    static final int CLEANUP_JOB_ID = 1;
    static final int CLEANUP_INTERVAL_DAYS = 1;

    /**
     * Schedules a periodic job to clean up stale contacts picker sessions.
     *
     * <p>The job is scheduled to run once a day when the device is idle. If the job is already
     * scheduled, this method will do nothing.
     *
     * @param context The {@link Context} to use for scheduling the job.
     */
    public static void scheduleCleanupJob(Context context) {
        if (context == null) {
            Log.w(TAG, "Context is null, cannot schedule cleanup job.");
            return;
        }
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        if (jobScheduler == null) {
            Log.e(TAG, "JobScheduler service not found.");
            return;
        }
        ComponentName serviceName = new ComponentName(context, ContactsPickerJobService.class);
        JobInfo jobInfo =
                new JobInfo.Builder(CLEANUP_JOB_ID, serviceName)
                        .setPeriodic(TimeUnit.DAYS.toMillis(CLEANUP_INTERVAL_DAYS))
                        .setRequiresDeviceIdle(true)
                        .build();
        int result = jobScheduler.schedule(jobInfo);
        if (result == JobScheduler.RESULT_SUCCESS) {
            if (VERBOSE_LOGGING) {
                Log.d(TAG, "Successfully scheduled cleanup job (ID: " + CLEANUP_JOB_ID + ").");
            }
        } else {
            Log.e(
                    TAG,
                    "Failed to schedule cleanup job (ID: "
                            + CLEANUP_JOB_ID
                            + "). Result: "
                            + result);
        }
    }
}
