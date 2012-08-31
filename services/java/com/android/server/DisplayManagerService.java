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
/* Copyright (C) 2012 Freescale Semiconductor, Inc. */

package com.android.server;

import android.app.PendingIntent;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.ActivityManagerNative;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.res.Resources;
import android.content.ContentResolver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.UEventObserver;
import android.os.SystemProperties;
import android.os.IDisplayManager;
import android.os.DisplayManager;
import android.os.Bundle;
import android.os.Process;
import android.util.Slog;
import android.media.AudioManager;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.provider.Settings;
import android.view.DisplayCommand;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FileNotFoundException;
import java.util.concurrent.CountDownLatch;
import java.util.HashSet;
import com.google.android.collect.Sets;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import java.lang.String;
/**
 * DisplayManagerService manages second display related state. it will communicate
 * with the dispd.
 * The main work of this service is to adjust the resolution of the second display
 */
class DisplayManagerService extends IDisplayManager.Stub {
    private static final String TAG = "DisplayManagerService";
    private static final boolean DBG = true;
    private static final String DISPD_TAG = "DispdConnector";
    private static final String DISPLAY_DAEMON = "dispd";

    private static final int ADD = 1;
    private static final int REMOVE = 2;

    private static final String DEFAULT = "default";
    private static final String SECONDARY = "secondary";

    private static final int MSG_UPDATE_STATE = 0;
    private static final int MSG_SYSTEM_READY = 1;
    private static final int MSG_BOOT_COMPLETED = 2;

    private static final int UPDATE_DELAY = 1000;

    private static final int MAX_DISPLAY_DEVICE = 6;

    private static final int DISPLAY_ENABLE_MSG = 1;
    private static final int DISPLAY_MIRROR_MSG = 2;
    private static final int DISPLAY_ROTATION_MSG = 3;
    private static final int DISPLAY_OVERSCAN_MSG = 4;
    private static final int DISPLAY_MODE_MSG = 5;
    private static final int DISPLAY_COLORDEPTH_MSG = 6;

    private static final boolean DISPLAY_ENABLE_DEFAULT = false;
    private static final boolean DISPLAY_MIRROR_DEFAULT = true;
    private static final int DISPLAY_OVERSCAN_DEFAULT = 0;
    private static final String DISPLAY_KEEPRATE_DEFAULT = "1000";
    private static final String DISPLAY_MODE_DEFAULT = "keepHighestMode";
    /**
     * Name representing {@link #setGlobalAlert(long)} limit when delivered to
     * {@link INetworkManagementEventObserver#limitReached(String, String)}.
     */
    public static final String LIMIT_GLOBAL_ALERT = "globalAlert";

    class DispdResponseCode {
        /* Keep in sync with system/dispd/ResponseCode.h */
        public static final int InterfaceListResult       = 110;

        public static final int CommandOkay               = 200;

        public static final int OperationFailed           = 400;

        public static final int InterfaceConnected           = 600;
        public static final int InterfaceDisconnected        = 601;
        public static final int InterfaceEnabled             = 602;
        public static final int InterfaceDisabled            = 603;
    }

    /**
     * Binder context for this service
     */
    private Context mContext;

    /**
     * connector object for communicating with netd
     */
    private NativeDaemonConnector mConnector;

    private Thread mThread = null;
    private final CountDownLatch mConnectedSignal = new CountDownLatch(1);

    private Object mQuotaLock = new Object();
    /** Set of interfaces with active quotas. */
    private HashSet<String> mActiveQuotaIfaces = Sets.newHashSet();
    /** Set of interfaces with active alerts. */
    private HashSet<String> mActiveAlertIfaces = Sets.newHashSet();
    /** Set of UIDs with active reject rules. */
    private SparseBooleanArray mUidRejectOnQuota = new SparseBooleanArray();

    private volatile boolean mBandwidthControlEnabled;

    private NotificationManager mNotificationManager;

    private DisplayHandler mHandler;
    private boolean mBootCompleted;

    private DisplayCommand mDispCommand;
    int mConnectCount = 0;
    /*
      DisplayDevice : record the state of the display and fb
      if one fb is connected, there will be a new display setting in the Settigng.apk
    */
    class DisplayDevice {
        public DisplayDevice(int fbid) {
            mIsConnected = false;
            mDeviceName = null;
            mFbid = fbid;
            mIsPlugable = false;

            mIsEnable = 0;
            mIsMirror = 0;
            mRotation = 0;
            mXOverScan = mYOverScan = 0;
            mColorDepth = 0;
            mCurrentMode = null;
            mSupportModes = null;
        }

        public void connectDevice(int fbid, String name) {
            if(mFbid != fbid) {
                Log.w(TAG,"connectDevice: invalidate fbid=" + fbid + " mFbid=" + mFbid);
                return;
            }

            mDeviceName = name;
            if(name.contains("hdmi")) {
                mIsPlugable = true;
            } else  {
                mIsPlugable = false;
            }
            //mFbid = fbid;
            mIsConnected = true;
            mConnectCount ++;
        }

        public void removeDevice() {
            //mIsPlugable = false;//save state.
            mDeviceName = null;
            mIsConnected = false;
            mConnectCount --;
        }

        //read all default value from setting xml file firstly.
        //then setting mode should read value from DMS everytime.
        public void readDefaultValueFromDatabase() {
            if(mFbid == 0) {
                mIsEnable = 1;
            } else {
                if(mSettings != null)
                    mIsEnable = mSettings.getBoolean(makeDisplayKey("enable", this), DISPLAY_ENABLE_DEFAULT)?1:0;
            }

            if(mSettings == null) {
                Log.w(TAG, "mSettings is null, please check the settings xml path");
                return;
            }
            mIsMirror = mSettings.getBoolean(makeDisplayKey("mirror", this), DISPLAY_MIRROR_DEFAULT)?1:0;

            String keepRate = mSettings.getString(makeDisplayKey("keeprate", this), DISPLAY_KEEPRATE_DEFAULT);
            mKeepRate = Integer.parseInt(keepRate, 16);

            String colorDepth = mSettings.getString(makeDisplayKey("colordepth", this), null);
            if(colorDepth != null) {
                mColorDepth = Integer.parseInt(colorDepth);
            } else {
                String filePath = "/sys/class/graphics/fb" + mFbid + "/bits_per_pixel";
		File colordepthFile = new File(filePath);
		char[] buffer2 = new char[1024];

		if(colordepthFile.exists()) {
		    try {
			FileReader file2 = new FileReader(colordepthFile);
			int len = file2.read(buffer2, 0 , 1024);
			file2.close();
		    } catch (FileNotFoundException e) {
			Log.w(TAG, "file not find");
		    } catch (Exception e) {
			Log.e(TAG, "" , e);
		    }

		    String colorDepth2 = new String(buffer2);
		    String[] itokens = colorDepth2.split("\n");
		    if (DBG) Log.w(TAG, "mColordepth:" + itokens[0]);
		    mColorDepth = Integer.parseInt(itokens[0]);
                }
                else {
                    mColorDepth = 32;
                }
            }

            mCurrentMode = mSettings.getString(makeDisplayKey("mode", this), DISPLAY_MODE_DEFAULT);
            Log.w(TAG,"mCurrentMode " + mFbid + " " + mCurrentMode + " keepRate:" + mKeepRate);
        }

        public boolean isconnect() {
            return mIsConnected;
        }

        public int getCount() {
            //not count fb0
            return mConnectCount - 1;
        }

        public int getFbid() {
            return mFbid;
        }

        public boolean isFbidValide() {
            return (mFbid >= 0 && mFbid < MAX_DISPLAY_DEVICE);
        }

        public String getDisplayName() {
            return mDeviceName;
        }

        public void setPlugable(boolean plug) {
            mIsPlugable = plug;
        }

        public boolean getPlugable() {
            return mIsPlugable;
        }

        public void setDisplayEnable(int enable) {
            mIsEnable = enable;
        }

        public int getDisplayEnable() {
            return mIsEnable;
        }

        public void setDisplayMirror(int mirror) {
            mIsMirror = mirror;
        }

        public int getDisplayMirror() {
            return mIsMirror;
        }

        public void setDisplayRotation(int rotation) {
            mRotation = rotation;
        }

        public int getDisplayRotation() {
            return mRotation;
        }

        public void setDisplayXOverScan(int xOverScan) {
            mXOverScan = xOverScan;
        }

        public int getDisplayXOverScan() {
            return mXOverScan;
        }

        public void setDisplayYOverScan(int yOverScan) {
            mYOverScan = yOverScan;
        }

        public int getDisplayYOverScan() {
            return mYOverScan;
        }

        public void setDisplayColorDepth(int colorDepth) {
            mColorDepth = colorDepth;
        }

        public int getDisplayColorDepth() {
            return mColorDepth;
        }

        public void setDisplayKeepRate(int keepRate) {
            mKeepRate = keepRate;
        }

        public int getDisplayKeepRate() {
            return mKeepRate;
        }

        public void setDisplayCurrentMode(String mode) {
            mCurrentMode = mode;
        }

        public String getDisplayCurrentMode() {
            return mCurrentMode;
        }

        public void setDisplaySupportModes(String[] modes) {
            mSupportModes = modes;
        }

        public String[] getDisplaySupportModes() {
            return mSupportModes;
        }

        public String getHighestMode() {
            if(mSupportModes != null)
                return mSupportModes[0];
            else
                return null;
        }

        boolean mIsConnected;
        String mDeviceName;
        int mFbid;
        boolean mIsPlugable;

        int mIsEnable;
        int mIsMirror;
        int mRotation;
        int mXOverScan;
        int mYOverScan;
        int mColorDepth;
        int mKeepRate;
        String mCurrentMode;
        String[] mSupportModes;
    }

    private String makeDeviceKey(String part, DisplayDevice dispDev) {
        if(dispDev == null || !dispDev.isFbidValide()) {
            Log.w(TAG,"makeDeviceKey: invalidate fbid");
            return null;
        }

        int fbid = dispDev.getFbid();
        return part + "_graphic_fb" + fbid;
    }

    private String makeDisplayKey(String part, DisplayDevice dispDev) {
        if(dispDev == null || !dispDev.isFbidValide()) {
            Log.w(TAG,"makeDisplayKey: invalidate fbid");
            return null;
        }

        int fbid = dispDev.getFbid();
        return "display_" + part + "_" + fbid;
    }

    DisplayDevice[] mDisplayDevice = new DisplayDevice[MAX_DISPLAY_DEVICE];

    final String PREFS_NAME = "com.android.settings_preferences";
    SharedPreferences mSettings = null;
    /**
     * Constructs a new DisplayManagerService instance
     *
     * @param context  Binder context for this service
     */
    private DisplayManagerService(Context context) {
        mContext = context;

        if("1".equals(SystemProperties.get("ro.kernel.qemu", "0"))) {
            return;
        }

        mConnector = new NativeDaemonConnector(
                new DispdCallbackReceiver(), DISPLAY_DAEMON, 10, DISPD_TAG);
        mThread = new Thread(mConnector, DISPD_TAG);

        // create a thread for our Handler
        HandlerThread thread = new HandlerThread("DisplayManagerService",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mHandler = new DisplayHandler(thread.getLooper());
        mDispCommand = new DisplayCommand();

        Context otherContext = null;

        try {
            otherContext = mContext.createPackageContext("com.android.settings", 0);
            if(otherContext != null)
                mSettings = otherContext.getSharedPreferences(PREFS_NAME, 0);
        } catch (NameNotFoundException e) {
            Log.w(TAG, "com.android.settings not find");
        }

        for(int i=0; i<MAX_DISPLAY_DEVICE; i++) {
            mDisplayDevice[i] = new DisplayDevice(i);
            mDisplayDevice[i].readDefaultValueFromDatabase();
        }
        readDefaultValue();
    }

    public static DisplayManagerService create(Context context) throws InterruptedException {
        DisplayManagerService service = new DisplayManagerService(context);
        if (DBG) Slog.d(TAG, "Creating DisplayManagerService");

        if("1".equals(SystemProperties.get("ro.kernel.qemu", "0"))) {
            return service;
        }
        if(service.mThread != null) service.mThread.start();
        if (DBG) Slog.d(TAG, "Awaiting socket connection");
        service.mConnectedSignal.await();
        if (DBG) Slog.d(TAG, "Connected");
        return service;
    }

    public void systemReady() {
        // need enable something when systemReady?
        if (DBG) Slog.d(TAG, "SystemReady");

        mNotificationManager = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        mHandler.sendEmptyMessage(MSG_SYSTEM_READY);
    }

    //read config for display 0
    private void readDefaultValue() {
        File modeFile = new File("/sys/class/graphics/fb0/mode");
        char[] buffer = new char[1024];

        if(modeFile.exists()) {
            try {
                FileReader file = new FileReader(modeFile);
                int len = file.read(buffer, 0 , 1024);
                file.close();
            } catch (FileNotFoundException e) {
                Log.w(TAG, "file not find");
            } catch (Exception e) {
                Log.e(TAG, "" , e);
            }

            String mode = new String(buffer);
            String[] tokens = mode.split("\n");
            if (DBG) Log.w(TAG, "mode:" + tokens[0]);

            String currentDisplayMode = getDisplayMode(0);
            if(DISPLAY_MODE_DEFAULT.equals(currentDisplayMode)) {
                Log.w(TAG, "use the highest resolution by default");
            } else {
                setDisplayDefaultMode(0, tokens[0]);
            }
        } else {
            Log.w(TAG, "/sys/class/graphics/fb0/mode not find");
        }

        File colordepthFile = new File("/sys/class/graphics/fb0/bits_per_pixel");
        char[] buffer2 = new char[1024];

        if(colordepthFile.exists()) {
            try {
                FileReader file2 = new FileReader(colordepthFile);
                int len = file2.read(buffer2, 0 , 1024);
                file2.close();
            } catch (FileNotFoundException e) {
                Log.w(TAG, "file not find");
            } catch (Exception e) {
                Log.e(TAG, "" , e);
            }

            String colorDepth = new String(buffer2);
            String[] itokens = colorDepth.split("\n");
            if (DBG) Log.w(TAG, "colordepth:" + itokens[0]);
            int colordepth = Integer.parseInt(itokens[0]);
            setDisplayDefaultdepth(0,colordepth);
        } else {
            Log.w(TAG, "/sys/class/graphics/fb0/bits_per_pixel not find");
        }
    }

    public boolean rebootSystem() {
        Log.w(TAG, "reboot the system" );
        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        pm.reboot(null);
        return true;
    }

    /**
     * Let us know the daemon is connected
     */
    protected void onDaemonConnected() {
        if (DBG) Slog.d(TAG, "onConnected");
        mConnectedSignal.countDown();
    }

    //
    // Dispd Callback handling
    //
    class DispdCallbackReceiver implements INativeDaemonConnectorCallbacks {
        /** {@inheritDoc} */
        public void onDaemonConnected() {
            DisplayManagerService.this.onDaemonConnected();
        }

        /** {@inheritDoc} */
        public boolean onEvent(int code, String raw, String[] cooked) {
            if (DBG) Slog.d(TAG, "Dispdcallback OnEvent raw " + raw);
            if (DBG) Slog.d(TAG, "Dispdcallback OnEvent "+ cooked[0] + " " +cooked[1] +" "+cooked[2]);
            boolean ret =  false;
            int     fbid = Integer.parseInt(cooked[2]);
            switch (code) {
            case DispdResponseCode.InterfaceConnected:
                    // need to remove, when the driver add event for each fb
                    //mdispstate.connect(fbid, cooked[3]);
                    mDisplayDevice[fbid].connectDevice(fbid, cooked[3]);
                    if(fbid != 0) mHandler.updateState("CONNECTED");
                    setDisplayConnectState(fbid, true);
                    ret = true;
                    break;
            case DispdResponseCode.InterfaceDisconnected:
                    // need to remove, when the driver add event for each fb
                    //if(mdispstate.isconnect(fbid)) 
                    if(mDisplayDevice[fbid].isconnect()){
                        setDisplayConnectState(fbid, false);
                        //mdispstate.remove(fbid);
                        mDisplayDevice[fbid].removeDevice();
                        //if(mdispstate.getcount() <= 1) mHandler.updateState("DISCONNECTED");
                        if(mDisplayDevice[fbid].getCount() <= 1) mHandler.updateState("DISCONNECTED");
                    }
                    ret = true;
                    break;
            default: break;
            }
            return ret;
        }
    }


    private void selectDisplayDefaultMode(int dispid) {
        // read the display mode list
        String[] display_modes;
        String currentDisplayMode = getDisplayMode(dispid);

        display_modes = getDisplayModeListFromDispd(dispid);

        mDisplayDevice[dispid].setDisplaySupportModes(display_modes);

        if(currentDisplayMode == null) {
            currentDisplayMode = display_modes[0];
        }

        if(currentDisplayMode == null) {
            Log.e(TAG, "get display modes failed, please check the display connection");
            return;
        }

        if(DISPLAY_MODE_DEFAULT.equals(currentDisplayMode)) {
            Log.w(TAG, "use the highest resolution by default");
            return;
        }

        boolean found = false;
        int[] request_resolution  = getResolutionInString(currentDisplayMode);
        int[] actual_resolution;
            // compare the default display mode
            // desend sort
            if(DBG) Log.w(TAG,"request " + request_resolution[0] + " " +request_resolution[1] + " "+ request_resolution[2] );

            for(String imode : display_modes)
            {
                if(imode.equals(currentDisplayMode)){
                    found = true;
                    if(DBG) Log.w(TAG,"found the match mode in fb_modes " + currentDisplayMode);
                    break;
                }
            }

            if(found ==  false){
                for(String imode : display_modes)
                {
                    int[] src_resolution = getResolutionInString(imode);

                    if(src_resolution[0] <= request_resolution[0] &&
                        src_resolution[1] <= request_resolution[1] &&
                        src_resolution[2] <= request_resolution[2] ) {
                        // use this resolution as default , set to database
                        actual_resolution = src_resolution;
                        currentDisplayMode = imode;
                        if(DBG) Log.w(TAG,"select " + actual_resolution[0] + " " +actual_resolution[1] + " "+ actual_resolution[2] );
                        found = true;
                        break;
                    }
                }
            }
            if(found ==  false){
                currentDisplayMode = display_modes[0];
            }

        setDisplayDefaultMode(dispid, currentDisplayMode);
    }


    private void setDisplayDefaultMode(int dispid, String mode) {
        mDisplayDevice[dispid].setDisplayCurrentMode(mode);
    }

    private void setDisplayDefaultdepth(int dispid, int colordepth) {
        mDisplayDevice[dispid].setDisplayColorDepth(colordepth);
    }

    private void setDisplayConnectState(int fbid, boolean connectState) {
        int dispid = fbid;

        if(connectState) selectDisplayDefaultMode(dispid);
        if (DBG) Log.w(TAG, "setDisplayConnectState: dispid=" + dispid);

          if(dispid == 1) {
                final Intent intent = new Intent(DisplayManager.ACTION_DISPLAY_DEVICE_0_ATTACHED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_DEVICE,  dispid);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_CONNECT, connectState);
                mContext.sendStickyBroadcast(intent);
            }

            if(dispid == 0) {
                final Intent intent = new Intent(DisplayManager.ACTION_DISPLAY_DEVICE_1_ATTACHED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_DEVICE,  dispid);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_CONNECT, connectState);
                mContext.sendStickyBroadcast(intent);
            }
            if(dispid == 0) {
                Intent intent;
                if(connectState && mDisplayDevice[dispid].getPlugable()) {
                    intent = new Intent(Intent.ACTION_HDMI_AUDIO_PLUG);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    intent.putExtra("state", 1);
                    intent.putExtra("name", "hdmi");
                    ActivityManagerNative.broadcastStickyIntent(intent, null);
                } else if(!connectState && mDisplayDevice[dispid].getPlugable())
                {
                    mContext.sendBroadcast(new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
                    //delay 1000 ms
                    try {
                          Thread.sleep(1000);
                    } catch (InterruptedException e) {
                          Log.w(TAG, "sleep error");
                    }
                    intent = new Intent(Intent.ACTION_HDMI_AUDIO_PLUG);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    intent.putExtra("state", 0);
                    intent.putExtra("name", "hdmi");
                    ActivityManagerNative.broadcastStickyIntent(intent, null);
                }
            }

            if(dispid == 2) {
                final Intent intent = new Intent(DisplayManager.ACTION_DISPLAY_DEVICE_2_ATTACHED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_DEVICE,  dispid);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_CONNECT, connectState);
                mContext.sendStickyBroadcast(intent);
            }

            if(dispid == 3) {
                final Intent intent = new Intent(DisplayManager.ACTION_DISPLAY_DEVICE_3_ATTACHED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_DEVICE,  dispid);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_CONNECT, connectState);
                mContext.sendStickyBroadcast(intent);
            }

            if(dispid == 4) {
                final Intent intent = new Intent(DisplayManager.ACTION_DISPLAY_DEVICE_4_ATTACHED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_DEVICE,  dispid);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_CONNECT, connectState);
                mContext.sendStickyBroadcast(intent);
            }

            if(dispid == 5) {
                final Intent intent = new Intent(DisplayManager.ACTION_DISPLAY_DEVICE_5_ATTACHED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_DEVICE,  dispid);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_CONNECT, connectState);
                mContext.sendStickyBroadcast(intent);
            }

        if(dispid > 0 && mDisplayDevice[dispid].getDisplayEnable() == 1) {
            if(connectState) commandDisplayEnable(dispid, 0, true);
            else             commandDisplayEnable(dispid, 0, false);
        } else if(dispid == 0) {
            //do some additional things like compare different mode;
            /*if(connectState) {
                mDispCommand.setResolution(dispid, mDisplayDevice[dispid].getDisplayCurrentMode());
            } else {
                mDispCommand.setResolution(dispid, "");
            }*/
        }
    }

    private final class DisplayHandler extends Handler {

        // current SecondDisplay state
        private boolean mConnected;
        private boolean mConfigured;
        private int mDisplayNotificationId;

        private final BroadcastReceiver mBootCompletedReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                if (DBG) Slog.d(TAG, "boot completed");
                mHandler.sendEmptyMessage(MSG_BOOT_COMPLETED);
            }
        };

        public DisplayHandler(Looper looper) {
            super(looper);
            try {

                mContext.registerReceiver(mBootCompletedReceiver,
                        new IntentFilter(Intent.ACTION_BOOT_COMPLETED));
            } catch (Exception e) {
                Slog.e(TAG, "Error initializing DisplayHandler", e);
            }
        }

        public void updateState(String state) {
            int connected, configured;

            if ("DISCONNECTED".equals(state)) {
                connected = 0;
                configured = 0;
            } else if ("CONNECTED".equals(state)) {
                connected = 1;
                configured = 0;
            } else if ("CONFIGURED".equals(state)) {
                connected = 1;
                configured = 1;
            } else {
                Slog.e(TAG, "unknown state " + state);
                return;
            }
            removeMessages(MSG_UPDATE_STATE);
            Message msg = Message.obtain(this, MSG_UPDATE_STATE);
            msg.arg1 = connected;
            msg.arg2 = configured;
            // debounce disconnects to avoid problems bringing up USB tethering
            sendMessageDelayed(msg, (connected == 0) ? UPDATE_DELAY : 0);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_STATE:
                    mConnected = (msg.arg1 == 1);
                    mConfigured = (msg.arg2 == 1);
                    updateDisplayNotification();
                    break;
                case MSG_SYSTEM_READY:
                    updateDisplayNotification();
                    break;
                case MSG_BOOT_COMPLETED:
                    mBootCompleted = true;
                    break;
            }
        }

        private void updateDisplayNotification() {
            if (mNotificationManager == null ) return;
            int id = 0;
            Resources r = mContext.getResources();
            if (mConnected) {
                id = com.android.internal.R.string.plugged_display_notification_title;
            }
            Log.w(TAG,"id "+id+" mDisplayNotificationId " +mDisplayNotificationId+ " mConnected "+mConnected);
            if (id != mDisplayNotificationId) {
                // clear notification if title needs changing
                if (mDisplayNotificationId != 0) {
                    mNotificationManager.cancel(mDisplayNotificationId);
                    mDisplayNotificationId = 0;
                }
                if (id != 0) {
                    CharSequence message = r.getText(
                            com.android.internal.R.string.plugged_display_notification_message);
                    CharSequence title = r.getText(id);

                    Notification notification = new Notification();
                    notification.icon = com.android.internal.R.drawable.stat_sys_hdmi_signal;
                    notification.when = 0;
                    notification.flags = Notification.FLAG_ONGOING_EVENT;
                    notification.tickerText = title;
                    notification.defaults = 0; // please be quiet
                    notification.sound = null;
                    notification.vibrate = null;

                    Intent intent = Intent.makeRestartActivityTask(
                            new ComponentName("com.android.settings",
                                    "com.android.settings.PluggableDisplaySettings"));
                    PendingIntent pi = PendingIntent.getActivity(mContext, 0,
                            intent, 0);
                    notification.setLatestEventInfo(mContext, title, message, pi);
                    mNotificationManager.notify(id, notification);
                    mDisplayNotificationId = id;
                }
            }
        }
    }

    private Handler mDatabaseHandler = new Handler() {

        @Override public void handleMessage(Message msg) {
            switch (msg.what){
                case DISPLAY_ENABLE_MSG: {
                    int dispid = msg.arg1;
                    if(dispid == 0) {
                        return;
                    }

                    Intent intent;
                    if(Integer.parseInt(msg.obj.toString()) == 1) {
                        String disp_mode = mDisplayDevice[dispid].getDisplayCurrentMode();
                        if(DISPLAY_MODE_DEFAULT.equals(disp_mode)) disp_mode = mDisplayDevice[dispid].getHighestMode();
                        Log.w(TAG, "disp_mode=" + disp_mode + " high mode=" + mDisplayDevice[dispid].getHighestMode());

                        mDispCommand.enable(dispid, disp_mode, mDisplayDevice[dispid].getDisplayRotation(),
                                             mDisplayDevice[dispid].getDisplayXOverScan(), mDisplayDevice[dispid].getDisplayYOverScan(), 
                                             mDisplayDevice[dispid].getDisplayMirror(), mDisplayDevice[dispid].getDisplayColorDepth(),
                                             mDisplayDevice[dispid].getDisplayKeepRate());
                        if(mDisplayDevice[dispid].getPlugable()) {
                            intent = new Intent(Intent.ACTION_HDMI_AUDIO_PLUG);
                            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                            intent.putExtra("state", 1);
                            intent.putExtra("name", "hdmi");
                            ActivityManagerNative.broadcastStickyIntent(intent, null);
                        }
                    }
                    else {
                        if(mDisplayDevice[dispid].getPlugable()) {
                            mContext.sendBroadcast(new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
                            //delay 1000 ms
                            try {
                                 Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                 Log.w(TAG, "sleep error");
                            }
                            intent = new Intent(Intent.ACTION_HDMI_AUDIO_PLUG);
                            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                            intent.putExtra("state", 0);
                            intent.putExtra("name", "hdmi");
                            ActivityManagerNative.broadcastStickyIntent(intent, null);
                        }
                        mDispCommand.disable(dispid);
                    }
                    break;
                }
                case DISPLAY_MIRROR_MSG: {
                    int dispid = msg.arg1;
                    if(mDisplayDevice[dispid].getDisplayEnable() == 1) mDispCommand.setMirror(dispid, Integer.parseInt(msg.obj.toString()));
                    break;
                }
                case DISPLAY_ROTATION_MSG: {
                    int dispid = msg.arg1;
                    if(mDisplayDevice[dispid].getDisplayEnable() == 1) mDispCommand.setRotation(dispid, Integer.parseInt(msg.obj.toString()));
                    break;
                }
                case DISPLAY_OVERSCAN_MSG: {
                    break;
                }
                case DISPLAY_MODE_MSG: {
                    int dispid = msg.arg1;
                    if(mDisplayDevice[dispid].getDisplayEnable() == 1) mDispCommand.setResolution(dispid, (String)msg.obj);
                    break;
                }
                case DISPLAY_COLORDEPTH_MSG: {
                    int dispid = msg.arg1;
                    if(mDisplayDevice[dispid].getDisplayEnable() == 1) mDispCommand.setColorDepth(dispid, Integer.parseInt(msg.obj.toString()));
                    break;
                }
                default:
                    super.handleMessage(msg);
            }
        }
    };

    public boolean isSecondDisplayConnect() {
        return false;
    }

    private int[] getResolutionInString(String mode)
    {
        int width_startIndex =0;
        int width_endIndex   =0;
        int height_startIndex=0;
        int height_endIndex  =0;
        int freq_startIndex  =0;
        int freq_endIndex    =0;
        int[] resolution = new int[3];
        boolean findwidthstart = true;

        if(DBG) Log.w(TAG, "mode = " + mode);

        for(int i=0; i<mode.length(); i++){

            if(mode.charAt(i) >='0' && mode.charAt(i) <='9' && findwidthstart) {
                findwidthstart = false;
                width_startIndex  = i;
            }
            if(mode.charAt(i) =='x') {
                width_endIndex    = i-1;
                height_startIndex = i+1;
            }
            if(mode.charAt(i) =='p' || mode.charAt(i) =='i')
                height_endIndex = i-1;

            if(mode.charAt(i) =='-'){
                freq_startIndex = i+1;
                freq_endIndex = mode.length()-1;
            }
        }

        resolution[0] = Integer.parseInt(mode.substring(width_startIndex,width_endIndex+1));
        resolution[1] = Integer.parseInt(mode.substring(height_startIndex,height_endIndex+1));
        resolution[2] = Integer.parseInt(mode.substring(freq_startIndex,freq_endIndex+1));
        if(DBG) Log.w(TAG,"width "+resolution[0]+" height "+resolution[1]+" freq "+resolution[2]);
        return resolution;
    }

    public String[] getDisplayModeListFromDispd(int dispid) {
        String[] rsp_modes ;
        int fbid = dispid;
        if (DBG) Slog.d(TAG, "getDisplayModeListFromDispd dispid "+ dispid + " fbid " +fbid);
        try {
            rsp_modes = mConnector.doListCommand(String.format("get_display_modelist %d", fbid), DispdResponseCode.InterfaceListResult);
        } catch (NativeDaemonConnectorException e) {
            throw new IllegalStateException("Error communicating to native daemon dispd", e);
        }
        return rsp_modes;
    }



    /* Returns a list of all currently attached display device mdoe */
    public String[] getDisplayModeList(int dispid) {
        return mDisplayDevice[dispid].getDisplaySupportModes();
    }


    //set the resolution
    public boolean setDisplayMode(int dispid, String disp_mode) throws IllegalStateException {
        commandDisplayMode(dispid, 1, disp_mode);
        return true;
    }

    public boolean setDisplayEnable(int dispid, boolean enable) {
        commandDisplayEnable(dispid, 1, enable);
        return true;
    }

    public boolean setDisplayMirror(int dispid, boolean enable) {
        commandDisplayMirror(dispid, 1, enable);
        return true;
    }

    public boolean setDisplayRotation(int dispid, boolean enable) {
        commandDisplayRotation(dispid, 1, enable);
        return true;
    }

    public boolean setDisplayXOverScan(int dispid, int xOverscan){
        int fbid = dispid;
        if(xOverscan == mDisplayDevice[dispid].getDisplayXOverScan()) return true;

        int yOverscan = mDisplayDevice[dispid].getDisplayYOverScan();
        mDisplayDevice[dispid].setDisplayXOverScan(xOverscan);
        if(mDisplayDevice[dispid].getDisplayEnable() == 1) mDispCommand.setOverScan(fbid, xOverscan, yOverscan);
        commandDisplayOverScan(dispid, 1, xOverscan);
        return true;
    }

    public boolean setDisplayYOverScan(int dispid, int yOverscan){
        int fbid = dispid;
        if(yOverscan == mDisplayDevice[dispid].getDisplayYOverScan()) return true;

        int xOverscan = mDisplayDevice[dispid].getDisplayXOverScan();
        mDisplayDevice[dispid].setDisplayYOverScan(yOverscan);
        if(mDisplayDevice[dispid].getDisplayEnable() == 1) mDispCommand.setOverScan(fbid, xOverscan, yOverscan);
        commandDisplayOverScan(dispid, 1, xOverscan);
        return true;
    }

    public boolean setDisplayColorDepth(int dispid, int colordepth) {
        commandDisplayColorDepth(dispid, 1, colordepth);
        return true;
    }

    public boolean setDisplayKeepRate(int dispid, int keepRate) {
        if(keepRate == mDisplayDevice[dispid].getDisplayKeepRate()) return true;

        mDisplayDevice[dispid].setDisplayKeepRate(keepRate);
        //saveActionMode(keepRate);
        if(mDisplayDevice[dispid].getDisplayEnable() == 1) mDispCommand.setKeepRate(dispid, keepRate);
        return true;
    }

    //set the resolution
    public boolean commandDisplayMode(int dispid, int save, String disp_mode) throws IllegalStateException {
        int fbid = dispid;
        if (DBG) Slog.d(TAG, " dispid " + dispid +" fbid "+ fbid +"setDisplayMode "+ disp_mode );
        if(disp_mode == null || disp_mode.equals(mDisplayDevice[dispid].getDisplayCurrentMode())) return true;

        mDisplayDevice[dispid].setDisplayCurrentMode(disp_mode);
        if(DISPLAY_MODE_DEFAULT.equals(disp_mode)) disp_mode = mDisplayDevice[dispid].getHighestMode();
        //reset overscan and keeprate
        int keepRate = Integer.parseInt(DISPLAY_KEEPRATE_DEFAULT, 16);
        mDisplayDevice[dispid].setDisplayKeepRate(keepRate);
        mDisplayDevice[dispid].setDisplayXOverScan(DISPLAY_OVERSCAN_DEFAULT);
        mDisplayDevice[dispid].setDisplayYOverScan(DISPLAY_OVERSCAN_DEFAULT);

        Message msg = Message.obtain();
        msg.what = DISPLAY_MODE_MSG;
        msg.arg1 = fbid;
        msg.arg2 = save;
        msg.obj  = disp_mode;
        mDatabaseHandler.sendMessageDelayed(msg, 10);


        return true;
    }

    public boolean commandDisplayEnable(int dispid, int save, boolean enable) {
        int fbid = dispid;
        if (DBG) Slog.d(TAG, " dispid " + dispid +" fbid "+ fbid +"setDisplayEnable "+ enable );
        if(save==1) mDisplayDevice[dispid].setDisplayEnable(enable?1:0);

        Message msg = Message.obtain();
        msg.what = DISPLAY_ENABLE_MSG;
        msg.arg1 = fbid;
        msg.arg2 = save;
        msg.obj  = enable?1:0;
        mDatabaseHandler.sendMessageDelayed(msg, 10);

        return true;
    }


    public boolean commandDisplayMirror(int dispid, int save, boolean enable) {
        int fbid = dispid;
        if (DBG) Slog.d(TAG, " dispid " + dispid +" fbid "+ fbid +"setDisplayMirror "+ enable );
        mDisplayDevice[dispid].setDisplayMirror(enable?1:0);

        Message msg = Message.obtain();
        msg.what = DISPLAY_MIRROR_MSG;
        msg.arg1 = fbid;
        msg.arg2 = save;
        msg.obj  = enable?1:0;
        mDatabaseHandler.sendMessageDelayed(msg, 10);


        return true;
    }

    public boolean commandDisplayRotation(int dispid, int save, boolean enable) {
        int fbid = dispid;
        if (DBG) Slog.d(TAG, " dispid " + dispid +" fbid "+ fbid +"setDisplayRotation "+ enable );

        mDisplayDevice[dispid].setDisplayRotation(enable?1:0);

        Message msg = Message.obtain();
        msg.what = DISPLAY_ROTATION_MSG;
        msg.arg1 = fbid;
        msg.arg2 = save;
        msg.obj  = enable?1:0;
        mDatabaseHandler.sendMessageDelayed(msg, 10);

        return true;
    }

    public boolean commandDisplayOverScan(int dispid, int save, int overscan){
        int fbid = dispid;
        if (DBG) Slog.d(TAG, " dispid " + dispid +" fbid "+ fbid +"setDisplayOverScan "+ overscan );

        Message msg = Message.obtain();
        msg.what = DISPLAY_OVERSCAN_MSG;
        msg.arg1 = fbid;
        msg.arg2 = save;
        msg.obj  = overscan;
        mDatabaseHandler.sendMessageDelayed(msg, 10);

        return true;
    }

    public boolean commandDisplayColorDepth(int dispid, int save, int colordepth) {
        int fbid = dispid;
        if (DBG) Slog.d(TAG, " dispid " + dispid +" fbid "+ fbid +"setDisplayColorDepth "+ colordepth );
        if(colordepth == mDisplayDevice[dispid].getDisplayColorDepth()) return true;


        mDisplayDevice[dispid].setDisplayColorDepth(colordepth);

        Message msg = Message.obtain();
        msg.what = DISPLAY_COLORDEPTH_MSG;
        msg.arg1 = fbid;
        msg.arg2 = save;
        msg.obj  = colordepth;
        mDatabaseHandler.sendMessageDelayed(msg, 10);

        return true;
    }

    // interface of getting the parameter,
    public boolean getDisplayEnable(int dispid) {
        return mDisplayDevice[dispid].getDisplayEnable()==1?true:false;
    }

    public boolean getDisplayMirror(int dispid) {
        return mDisplayDevice[dispid].getDisplayMirror()==1?true:false;
    }

    public boolean getDisplayRotation(int dispid) {
        return mDisplayDevice[dispid].getDisplayRotation()==1?true:false;
    }

    public String getDisplayMode(int dispid) {
        return mDisplayDevice[dispid].getDisplayCurrentMode();
    }

    public String getDisplayName(int dispid) {
        return mDisplayDevice[dispid].getDisplayName();
    }

    public int getDisplayXOverScan(int dispid) {
        return mDisplayDevice[dispid].getDisplayXOverScan();
    }

    public int getDisplayYOverScan(int dispid) {
        return mDisplayDevice[dispid].getDisplayYOverScan();
    }

    public int getDisplayColorDepth(int dispid) {
        return mDisplayDevice[dispid].getDisplayColorDepth();
    }

    public int getDisplayKeepRate(int dispid) {
        return mDisplayDevice[dispid].getDisplayKeepRate();
    }
}
