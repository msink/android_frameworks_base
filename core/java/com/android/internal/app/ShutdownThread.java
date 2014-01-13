/*
 * Copyright (C) 2008 The Android Open Source Project
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

 
package com.android.internal.app;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.Instrumentation;
import android.app.ProgressDialog;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetooth;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Power;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.os.storage.IMountService;
import android.os.storage.IMountShutdownObserver;

import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

public final class ShutdownThread extends Thread {
    // constants
    private static final String TAG = "ShutdownThread";
    private static final int MAX_NUM_PHONE_STATE_READS = 16;
    private static final int PHONE_STATE_POLL_SLEEP_MSEC = 500;
    // maximum time we wait for the shutdown broadcast before going on.
    private static final int MAX_BROADCAST_TIME = 6*1000;
    private static final int MAX_SHUTDOWN_WAIT_TIME = 8*1000;

    // state tracking
    private static Object sIsStartedGuard = new Object();
    private static boolean sIsStarted = false;
    
    private static boolean mReboot;
    private static String mRebootReason;

    // Provides shutdown assurance in case the system_server is killed
    public static final String SHUTDOWN_ACTION_PROPERTY = "sys.shutdown.requested";
    public static final String SHUTDOWN_INDICATOR_PROPERTY = "sys.shutting.down";

    // static instance of this thread
    private static final ShutdownThread sInstance = new ShutdownThread();
    
    private final Object mActionDoneSync = new Object();
    private boolean mActionDone;
    private Context mContext;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;
    private Handler mHandler;

    AlertDialog mShutdownDialog;
    private static String mShutdownReason;

    static String defaultImgName = "power_off";
    static AlertDialog dialog_2;
    static View layout;
    static ProgressBar progeBar;
    static RelativeLayout relate_Shutdown;
    static int resourceID;
    static TextView txtProgressMessage;

    public ShutdownThread() {
    }
 
    /**
     * Request a clean shutdown, waiting for subsystems to clean up their
     * state etc.  Must be called from a Looper thread in which its UI
     * is shown.
     *
     * @param context Context used to display the shutdown progress dialog.
     * @param confirm true if user confirmation is needed before shutting down.
     */
    public static void shutdown(final Context context, boolean confirm) {
        // ensure that only one thread is trying to power down.
        // any additional calls are just returned
        synchronized (sIsStartedGuard) {
            if (sIsStarted) {
                Log.d(TAG, "Request to shutdown already running, returning.");
                return;
            }
        }

        Log.d(TAG, "Notifying thread to start radio shutdown");

        if (confirm) {
            sInstance.mShutdownDialog = new AlertDialog.Builder(context)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setTitle(com.android.internal.R.string.power_off)
                    .setMessage(com.android.internal.R.string.shutdown_confirm)
                    .setPositiveButton(com.android.internal.R.string.yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            SystemProperties.set("sys.shutting.down", "1");
                            beginShutdownSequence(context);
                        }
                    })
                    .setNegativeButton(com.android.internal.R.string.no, null)
                    .create();
            sInstance.mShutdownDialog.getWindow()
                    .setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            if (!context.getResources().getBoolean(
                    com.android.internal.R.bool.config_sf_slowBlur)) {
                sInstance.mShutdownDialog.getWindow()
                                  .addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            }
            sInstance.mShutdownDialog.show();
        } else {
            beginShutdownSequence(context);
        }
    }

    public static void shutdown(final Context context, boolean confirm, String reason) {
        mShutdownReason = reason;
        shutdown(context, confirm);
    }

    public static void usbconnect_note(Context context, boolean confirm) {
        if (confirm) {
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setTitle(com.android.internal.R.string.usb_note)
                    .setMessage(com.android.internal.R.string.usbnote_confirm)
                    .setPositiveButton(android.R.string.yes, null)
                    .create();
            dialog.getWindow()
                    .setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            if (!context.getResources().getBoolean(
                    com.android.internal.R.bool.config_sf_slowBlur)) {
                dialog.getWindow()
                    .addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            }
            if (!dialog.isShowing()) {
                dialog.show();
            }
        }
    }


    /**
     * Request a clean shutdown, waiting for subsystems to clean up their
     * state etc.  Must be called from a Looper thread in which its UI
     * is shown.
     *
     * @param context Context used to display the shutdown progress dialog.
     * @param reason code to pass to the kernel (e.g. "recovery"), or null.
     * @param confirm true if user confirmation is needed before shutting down.
     */
    public static void reboot(final Context context, String reason, boolean confirm) {
        mReboot = true;
        mRebootReason = reason;
        shutdown(context, confirm);
    }

    public static void beginShutdownSequence(Context context) {
        synchronized (sIsStartedGuard) {
            if (sIsStarted) {
                Log.d(TAG, "Request to shutdown already running, returning.");
                return;
            }
            sIsStarted = true;
        }

        // throw up an indeterminate system dialog to indicate radio is
        // shutting down.
        LayoutInflater inflater = (LayoutInflater)context.getSystemService("layout_inflater");
        layout = inflater.inflate(com.android.internal.R.layout.power_off_layout_2, null);
        dialog_2 = new AlertDialog.Builder(context).create();
        dialog_2.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        if (!context.getResources().getBoolean(
                com.android.internal.R.bool.config_sf_slowBlur)) {
            dialog_2.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }
        dialog_2.show();
        dialog_2.getWindow().setGravity(Gravity.CENTER);
        dialog_2.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                                       WindowManager.LayoutParams.MATCH_PARENT);
        dialog_2.getWindow().setContentView(layout);
        dialog_2.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        System.out.println("0329  Shutdown  0329 33 systemClock===" + System.currentTimeMillis());

        // start the thread that initiates shutdown
        sInstance.mContext = context;
        sInstance.mPowerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        sInstance.mWakeLock = null;
        if (sInstance.mPowerManager.isScreenOn()) {
            try {
                sInstance.mWakeLock = sInstance.mPowerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "Shutdown");
                sInstance.mWakeLock.acquire();
            } catch (SecurityException e) {
                Log.w(TAG, "No permission to acquire wake lock", e);
                sInstance.mWakeLock = null;
            }
        }
        sInstance.mHandler = new Handler() {
        };
        sInstance.start();
    }

    void actionDone() {
        synchronized (mActionDoneSync) {
            mActionDone = true;
            mActionDoneSync.notifyAll();
        }
    }

    /**
     * Makes sure we handle the shutdown gracefully.
     * Shuts off power regardless of radio and bluetooth state if the alloted time has passed.
     */
    public void run() {
        boolean bluetoothOff;
        boolean radioOff;

        BroadcastReceiver br = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                // We don't allow apps to cancel this, so ignore the result.
                actionDone();
            }
        };

        /*
         * Write a system property in case the system_server reboots before we
         * get to the actual hardware restart. If that happens, we'll retry at
         * the beginning of the SystemServer startup.
         */
        {
            String reason = (mReboot ? "1" : "0") + (mRebootReason != null ? mRebootReason : "");
            SystemProperties.set(SHUTDOWN_ACTION_PROPERTY, reason);
        }

        Log.i(TAG, "Sending shutdown broadcast...");
        
        // First send the high-level shut down broadcast.
        mActionDone = false;
        mContext.sendOrderedBroadcast(new Intent(Intent.ACTION_SHUTDOWN), null,
                br, mHandler, 0, null, null);
        
        final long endTime = SystemClock.elapsedRealtime() + MAX_BROADCAST_TIME;
        synchronized (mActionDoneSync) {
            while (!mActionDone) {
                long delay = endTime - SystemClock.elapsedRealtime();
                if (delay <= 0) {
                    Log.w(TAG, "Shutdown broadcast timed out");
                    break;
                }
                try {
                    mActionDoneSync.wait(delay);
                } catch (InterruptedException e) {
                }
            }
        }
        
        Log.i(TAG, "Shutting down activity manager...");
        
        final IActivityManager am =
            ActivityManagerNative.asInterface(ServiceManager.checkService("activity"));
        if (am != null) {
            try {
                am.shutdown(MAX_BROADCAST_TIME);
            } catch (RemoteException e) {
            }
        }
        
        final IBluetooth bluetooth =
                IBluetooth.Stub.asInterface(ServiceManager.checkService(
                        BluetoothAdapter.BLUETOOTH_SERVICE));

        final IMountService mount =
                IMountService.Stub.asInterface(
                        ServiceManager.checkService("mount"));
        
        Log.i(TAG, "Waiting for Bluetooth...");
        
        // Shutdown MountService to ensure media is in a safe state
        IMountShutdownObserver observer = new IMountShutdownObserver.Stub() {
            public void onShutDownComplete(int statusCode) throws RemoteException {
                Log.w(TAG, "Result code " + statusCode + " from MountService.shutdown");
                actionDone();
            }
        };

        Log.i(TAG, "Shutting down MountService");
        // Set initial variables and time out time.
        mActionDone = false;
        final long endShutTime = SystemClock.elapsedRealtime() + MAX_SHUTDOWN_WAIT_TIME;
        synchronized (mActionDoneSync) {
            try {
                if (mount != null) {
                    mount.shutdown(observer);
                } else {
                    Log.w(TAG, "MountService unavailable for shutdown");
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception during MountService shutdown", e);
            }
            while (!mActionDone) {
                long delay = endShutTime - SystemClock.elapsedRealtime();
                if (delay <= 0) {
                    Log.w(TAG, "Shutdown wait timed out");
                    break;
                }
                try {
                    mActionDoneSync.wait(delay);
                } catch (InterruptedException e) {
                }
            }
        }

        rebootOrShutdown(mReboot, mRebootReason);
    }

    /**
     * Do not call this directly. Use {@link #reboot(Context, String, boolean)}
     * or {@link #shutdown(Context, boolean)} instead.
     *
     * @param reboot true to reboot or false to shutdown
     * @param reason reason for reboot
     */
    public static void rebootOrShutdown(boolean reboot, String reason) {
        if (reboot) {
            Log.i(TAG, "Rebooting, reason: " + reason);
            try {
                Power.reboot(reason);
            } catch (Exception e) {
                Log.e(TAG, "Reboot failed, will attempt shutdown instead", e);
            }
        } else if (mShutdownReason != null && mShutdownReason.equals("nopower")) {
            sInstance.mPowerManager.startSurfaceFlingerAnimation(PowerManager.ANIM_NOPOWER);
        } else {
            sInstance.mPowerManager.startSurfaceFlingerAnimation(PowerManager.ANIM_SHUTDOWN);
        }

        // Shutdown power
        Log.i(TAG, "Performing low-level shutdown...");
        Power.shutdown();
    }
}
