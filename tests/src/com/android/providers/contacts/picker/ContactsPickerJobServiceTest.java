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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.job.JobParameters;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ExecutorService;

@RunWith(AndroidJUnit4.class)
public class ContactsPickerJobServiceTest {

    private ContactsPickerJobService mService;

    @Before
    public void setUp() {
        ExecutorService mockExecutor = mock(ExecutorService.class);
        doAnswer(
                        invocation -> {
                            ((Runnable) invocation.getArgument(0)).run();
                            return null;
                        })
                .when(mockExecutor)
                .execute(any());

        mService = spy(new ContactsPickerJobService(mockExecutor));
        doNothing().when(mService).cleanupSessions();
        doNothing().when(mService).callJobFinished(any(), anyBoolean());
    }

    @Test
    public void onStartJob_executesCleanup() {
        JobParameters params = mock(JobParameters.class);

        mService.onStartJob(params);

        verify(mService).cleanupSessions();
    }
}
