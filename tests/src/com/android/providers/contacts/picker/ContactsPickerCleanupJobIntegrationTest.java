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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.job.JobScheduler;
import android.content.ContentValues;
import android.content.Context;
import android.content.flags.Flags;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Process;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.PollingCheck;
import com.android.compatibility.common.util.SystemUtil;
import com.android.providers.contacts.picker.ContactsPickerDatabaseHelper.SessionColumns;
import com.android.providers.contacts.picker.ContactsPickerDatabaseHelper.Tables;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
public class ContactsPickerCleanupJobIntegrationTest {

    private Context mContext;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ContactsPickerJobScheduler.scheduleCleanupJob(mContext);
    }

    @After
    public void tearDown() {
        JobScheduler jobScheduler = mContext.getSystemService(JobScheduler.class);
        if (jobScheduler != null) {
            jobScheduler.cancel(ContactsPickerJobScheduler.CLEANUP_JOB_ID);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SYSTEM_CONTACTS_PICKER)
    public void testJobScheduler_executesCleanup_endToEnd() throws Exception {
        ContactsPickerDatabaseHelper dbHelper = ContactsPickerDatabaseHelper.getInstance(mContext);
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        // Insert a stale session (older than 24 hours)
        String staleSessionId =
                insertSession(db, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2));

        // Insert a recent session (newer than 24 hours)
        String recentSessionId = insertSession(db, System.currentTimeMillis());

        try {
            final int jobId = ContactsPickerJobScheduler.CLEANUP_JOB_ID;
            String targetPackage = mContext.getPackageName();
            runJob(jobId, targetPackage);

            verifySessionDeleted(db, staleSessionId);

            // Verify the recent session still exists
            try (Cursor cursor =
                    db.query(
                            Tables.SESSIONS,
                            null,
                            SessionColumns.SESSION_UID + "=?",
                            new String[] {recentSessionId},
                            null,
                            null,
                            null)) {
                assertEquals("Recent session should still exist", 1, cursor.getCount());
            }
        } finally {
            db.delete(
                    Tables.SESSIONS,
                    SessionColumns.SESSION_UID + "=?",
                    new String[] {recentSessionId});
            db.delete(
                    Tables.SESSIONS,
                    SessionColumns.SESSION_UID + "=?",
                    new String[] {staleSessionId});
        }
    }

    private String insertSession(SQLiteDatabase db, long createdAt) {
        String sessionId = UUID.randomUUID().toString();
        ContentValues values = new ContentValues();
        values.put(SessionColumns.SESSION_UID, sessionId);
        values.put(SessionColumns.DATA_ROW_IDS, "1,2,3");
        values.put(SessionColumns.CALLER_UID, 1000);
        values.put(SessionColumns.CREATED_AT, createdAt);
        db.insert(Tables.SESSIONS, null, values);
        return sessionId;
    }

    private void runJob(int jobId, String packageName) throws Exception {
        // Construct the shell command:
        // cmd jobscheduler run -f -u [USER_ID] [PACKAGE_NAME] [JOB_ID]
        String cmd =
                String.format(
                        "cmd jobscheduler run -f -u %d %s %d",
                        Process.myUserHandle().getIdentifier(), packageName, jobId);

        String output =
                SystemUtil.runShellCommand(InstrumentationRegistry.getInstrumentation(), cmd);

        String pattern = String.format("Running job \\[(%d|FORCED)\\]", jobId);
        assertTrue(
                "Job execution output did not match expected pattern '"
                        + pattern
                        + "'. System output: "
                        + output,
                Pattern.compile(pattern).matcher(output).find());
    }

    private void verifySessionDeleted(SQLiteDatabase db, String sessionId) throws Exception {
        // Poll for it, as the job runs on a background thread)
        PollingCheck.check(
                "Session " + sessionId + " should have been deleted",
                5000,
                () -> {
                    try (Cursor cursor =
                            db.query(
                                    Tables.SESSIONS,
                                    null,
                                    SessionColumns.SESSION_UID + "=?",
                                    new String[] {sessionId},
                                    null,
                                    null,
                                    null)) {
                        return cursor.getCount() == 0;
                    }
                });
    }
}
