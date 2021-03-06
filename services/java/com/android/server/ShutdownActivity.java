/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.server;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Slog;
import com.android.internal.app.ShutdownThread;

public class ShutdownActivity extends Activity {

    private static final String TAG = "ShutdownActivity";
    private boolean mConfirm;
    private String mShutdownReason;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mConfirm = getIntent().getBooleanExtra(Intent.EXTRA_KEY_CONFIRM, false);
        Slog.i(TAG, "onCreate(): confirm=" + mConfirm);
        mShutdownReason = getIntent().getStringExtra(Intent.EXTRA_SHUTDOWN_REASON);
        Slog.i(TAG, "onCreate(): reason=" + mShutdownReason);

        Handler h = new Handler();
        h.post(new Runnable() {
            public void run() {
                ShutdownThread.shutdown(ShutdownActivity.this, mConfirm, mShutdownReason);
            }
        });
    }
}
