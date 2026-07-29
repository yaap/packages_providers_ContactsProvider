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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class ContactsPickerJobSchedulerTest {

    private static final int CLEANUP_JOB_ID = 1;

    @Rule public MockitoRule rule = MockitoJUnit.rule();

    @Mock private Context mMockContext;
    @Mock private JobScheduler mMockJobScheduler;

    @Captor private ArgumentCaptor<JobInfo> mJobInfoCaptor;

    @Before
    public void setUp() {
        when(mMockContext.getSystemService(Context.JOB_SCHEDULER_SERVICE))
                .thenReturn(mMockJobScheduler);
        when(mMockContext.getSystemServiceName(JobScheduler.class))
                .thenReturn(Context.JOB_SCHEDULER_SERVICE);
        when(mMockContext.getPackageName())
                .thenReturn(ApplicationProvider.getApplicationContext().getPackageName());
    }

    @Test
    public void scheduleCleanupJob_schedulesJobWithCorrectParameters() {
        when(mMockJobScheduler.schedule(any(JobInfo.class)))
                .thenReturn(JobScheduler.RESULT_SUCCESS);

        ContactsPickerJobScheduler.scheduleCleanupJob(mMockContext);

        verify(mMockJobScheduler).schedule(mJobInfoCaptor.capture());
        JobInfo jobInfo = mJobInfoCaptor.getValue();

        assertThat(jobInfo.getId()).isEqualTo(CLEANUP_JOB_ID);
        assertThat(jobInfo.getService().getClassName())
                .isEqualTo(ContactsPickerJobService.class.getName());
        assertThat(jobInfo.getIntervalMillis())
                .isEqualTo(
                        TimeUnit.DAYS.toMillis(ContactsPickerJobScheduler.CLEANUP_INTERVAL_DAYS));
        assertThat(jobInfo.isRequireDeviceIdle()).isTrue();
        assertThat(jobInfo.isPeriodic()).isTrue();
    }

    @Test
    public void scheduleCleanupJob_nullContext_doesNotCrash() {
        ContactsPickerJobScheduler.scheduleCleanupJob(null);
    }

    @Test
    public void scheduleCleanupJob_jobSchedulerNotFound_doesNotCrash() {
        when(mMockContext.getSystemService(JobScheduler.class)).thenReturn(null);
        ContactsPickerJobScheduler.scheduleCleanupJob(mMockContext);
    }
}
