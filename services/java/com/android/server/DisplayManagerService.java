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
/* Copyright 2012 Freescale Semiconductor, Inc. */

package com.android.server;

import static android.provider.Settings.System.PLUGGED_DISPLAY_1_MODE;
import static android.provider.Settings.System.PLUGGED_DISPLAY_1_ENABLE;
import static android.provider.Settings.System.PLUGGED_DISPLAY_1_MIRROR;
import static android.provider.Settings.System.PLUGGED_DISPLAY_1_ROTATION;
import static android.provider.Settings.System.PLUGGED_DISPLAY_1_OVERSCAN;
import static android.provider.Settings.System.PLUGGED_DISPLAY_1_COLORDEPTH;
import static android.provider.Settings.System.PLUGGED_DISPLAY_2_MODE;
import static android.provider.Settings.System.PLUGGED_DISPLAY_2_ENABLE;
import static android.provider.Settings.System.PLUGGED_DISPLAY_2_MIRROR;
import static android.provider.Settings.System.PLUGGED_DISPLAY_2_ROTATION;
import static android.provider.Settings.System.PLUGGED_DISPLAY_2_OVERSCAN;
import static android.provider.Settings.System.PLUGGED_DISPLAY_2_COLORDEPTH;
import static android.provider.Settings.System.PLUGGED_DISPLAY_3_MODE;
import static android.provider.Settings.System.PLUGGED_DISPLAY_3_ENABLE;
import static android.provider.Settings.System.PLUGGED_DISPLAY_3_MIRROR;
import static android.provider.Settings.System.PLUGGED_DISPLAY_3_ROTATION;
import static android.provider.Settings.System.PLUGGED_DISPLAY_3_OVERSCAN;
import static android.provider.Settings.System.PLUGGED_DISPLAY_3_COLORDEPTH;
import static android.provider.Settings.System.PLUGGED_DISPLAY_4_MODE;
import static android.provider.Settings.System.PLUGGED_DISPLAY_4_ENABLE;
import static android.provider.Settings.System.PLUGGED_DISPLAY_4_MIRROR;
import static android.provider.Settings.System.PLUGGED_DISPLAY_4_ROTATION;
import static android.provider.Settings.System.PLUGGED_DISPLAY_4_OVERSCAN;
import static android.provider.Settings.System.PLUGGED_DISPLAY_4_COLORDEPTH;


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
/**
 * DisplayManagerService manages second display related state. it will communicate
 * with the dispd.
 * The main work of this service is to adjust the resolution of the second display
 */
class DisplayManagerService extends IDisplayManager.Stub {
    private static final String TAG = "DisplayManagerService";
    private static final boolean DBG = false;
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

    private static final int MAX_FB_DEVICE      = 10;
    private static final int MAX_DISPLAY_DEVICE = 4;

    private static final int DISPLAY_ENABLE_MSG = 1;
    private static final int DISPLAY_MIRROR_MSG = 2;
    private static final int DISPLAY_ROTATION_MSG = 3;
    private static final int DISPLAY_OVERSCAN_MSG = 4;
    private static final int DISPLAY_MODE_MSG = 5;
    private static final int DISPLAY_COLORDEPTH_MSG = 6;

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

    private String[]   mSettings_enable    = {PLUGGED_DISPLAY_1_ENABLE, PLUGGED_DISPLAY_2_ENABLE, PLUGGED_DISPLAY_3_ENABLE, PLUGGED_DISPLAY_4_ENABLE };
    private String[]   mSettings_mirror    = {PLUGGED_DISPLAY_1_MIRROR, PLUGGED_DISPLAY_2_MIRROR, PLUGGED_DISPLAY_3_MIRROR, PLUGGED_DISPLAY_4_MIRROR };
    private String[]   mSettings_rotation  = {PLUGGED_DISPLAY_1_ROTATION, PLUGGED_DISPLAY_2_ROTATION, PLUGGED_DISPLAY_3_ROTATION, PLUGGED_DISPLAY_4_ROTATION };
    private String[]   mSettings_overscan    = {PLUGGED_DISPLAY_1_OVERSCAN, PLUGGED_DISPLAY_2_OVERSCAN, PLUGGED_DISPLAY_3_OVERSCAN, PLUGGED_DISPLAY_4_OVERSCAN };
    private String[]   mSettings_mode        = {PLUGGED_DISPLAY_1_MODE, PLUGGED_DISPLAY_2_MODE, PLUGGED_DISPLAY_3_MODE, PLUGGED_DISPLAY_4_MODE };
    private String[]   mSettings_colordepth  = {PLUGGED_DISPLAY_1_COLORDEPTH, PLUGGED_DISPLAY_2_COLORDEPTH, PLUGGED_DISPLAY_3_COLORDEPTH, PLUGGED_DISPLAY_4_COLORDEPTH };

    private int[]    mDisplay_enable       = new int[MAX_DISPLAY_DEVICE];
    private int[]    mDisplay_mirror       = new int[MAX_DISPLAY_DEVICE];
    private int[]    mDisplay_rotation     = new int[MAX_DISPLAY_DEVICE];
    private int[]    mDisplay_overscan     = new int[MAX_DISPLAY_DEVICE];
    private String[] mDisplay_mode         = new String[MAX_DISPLAY_DEVICE];
    private int[]    mDisplay_colordepth   = new int[MAX_DISPLAY_DEVICE];

    private String[][] mDisplay_modes = new String[MAX_DISPLAY_DEVICE][];

    private DisplayCommand mDispCommand;
    /*
      display_state : record the state of the display and fb
      The display id is 0~3, max support 4 display
      The fb id is 0~9, max support 9 fb
      if one fb is used, there will be a new display setting in the Settigng.apk
    */
    class display_state {
        //dispid correspond to fb
        int[] display_fb=new int[MAX_FB_DEVICE];
        //fb state, connected or not;
        int[] state_fb=new int[MAX_FB_DEVICE];
        // display ui id state, enabled or not;
        int[] state_display=new int[MAX_DISPLAY_DEVICE];

        int connect_count;

        // reserve the display 0 for fb0, main screen;
        display_state()
        {
            for(int i=0;i<MAX_FB_DEVICE;i++)
            {
                state_fb[i]   =  0;
                display_fb[i] = -1;
            }
            connect_count = 0;

            for(int j=0; j<MAX_DISPLAY_DEVICE; j++)
            {
                state_display[j] = 0;
            }
        }

        void connect(int fbid){
            state_fb[fbid]   = 1;
            for(int j=0; j<MAX_DISPLAY_DEVICE; j++)
            {
                if(state_display[j] == 0) {
                    display_fb[fbid] = j;
                    state_display[j] = 1;
                    break;
                }
            }
            connect_count ++ ;
        }

        void remove(int fbid){
            if(state_fb[fbid] == 1)
            {
                state_fb[fbid]           = 0;
                state_display[display_fb[fbid]] = 0 ;
                display_fb[fbid]         = -1;
                connect_count --;
            }
        }

        boolean isconnect(int fbid) {
            return state_fb[fbid]==1? true:false;
        }
        // get the count, not count the fb0;
        int getcount() {
            return connect_count-1;
        }

        int getfbid(int dispid){
            for(int i=0;i<MAX_FB_DEVICE;i++)
            {
                if(display_fb[i] == dispid)  return i;
            }
            return -1;
        }

        int getdispid(int fbid){
            return display_fb[fbid];
        }

    }
    display_state mdispstate = new display_state();
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
        getDefaultValue();
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

    private void getDefaultValue() {

        for(int i=0; i<MAX_DISPLAY_DEVICE; i++)
        {
            try {
                mDisplay_enable[i] = Settings.System.getInt(mContext.getContentResolver(), mSettings_enable[i]);
            } catch (Settings.SettingNotFoundException e) {
                Log.w(TAG,"read setting init error");
            }
            try {
                mDisplay_mirror[i] = Settings.System.getInt(mContext.getContentResolver(), mSettings_mirror[i]);
            } catch (Settings.SettingNotFoundException e) {
                Log.w(TAG,"read setting init error");
            }
            try {
                mDisplay_rotation[i] = Settings.System.getInt(mContext.getContentResolver(), mSettings_rotation[i]);
            } catch (Settings.SettingNotFoundException e) {
                Log.w(TAG,"read setting init error");
            }
            try {
                mDisplay_overscan[i] = Settings.System.getInt(mContext.getContentResolver(), mSettings_overscan[i]);
            } catch (Settings.SettingNotFoundException e) {
                Log.w(TAG,"read setting init error");
            }

            mDisplay_mode[i] = Settings.System.getString(mContext.getContentResolver(), mSettings_mode[i]);

            try {
                mDisplay_colordepth[i] = Settings.System.getInt(mContext.getContentResolver(), mSettings_colordepth[i]);
            } catch (Settings.SettingNotFoundException e) {
                Log.w(TAG,"read setting init error");
            }
            Log.w(TAG,"mDisplay_mode " + i + " " +mDisplay_mode[i]);
        }

        // read config file for disp 0;
        readConfigFile();
    }

    //read config for display 0
    private void readConfigFile() {

        File file1 = new File("/data/misc/display.conf");
        char[] buffer = new char[1024];

        if(file1.exists()) {
            try {
                FileReader file = new FileReader(file1);
                int len = file.read(buffer, 0 , 1024);
                file.close();
            } catch (FileNotFoundException e) {
                Log.w(TAG, "file not find");
            } catch (Exception e) {
                Log.e(TAG, "" , e);
            }
        }

        if(file1.exists()) {
            String config = new String(buffer);
            String[] tokens = config.split("\n");
            Log.w(TAG,"tokens[0] " + tokens[0] + " " + tokens[0].length() +" tokens[1] " + tokens[1]+ " " + tokens[1].length());
            if(tokens[0].length() > 5) {
                String mode  = tokens[0].substring(5, tokens[0].length());
                setDisplayDefaultMode(0, mode);
            }
            if(tokens[1].length() > 11 ) {
                String depth = tokens[1].substring(11,tokens[1].length());
                int colordepth = Integer.parseInt(depth);
                setDisplayDefaultdepth(0,colordepth);
            }
        }
    }

    //set config for display 0
    private void writeConfigFile(String mode, int colordepth) {

        String config = String.format("mode=%s\ncolordepth=%d\n", mode, colordepth);

        try {
            FileWriter out = new FileWriter("/data/misc/display.conf");
            out.write(config);
            out.close();
        } catch (Exception e) {
            Log.e(TAG, "" , e);
        } finally {

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
            if (DBG) Slog.d(TAG, "Dispdcallback OnEvent "+ cooked[0] + " " +cooked[1] +" "+cooked[2]);
            boolean ret =  false;
            int     fbid = Integer.parseInt(cooked[2]);
            switch (code) {
            case DispdResponseCode.InterfaceConnected:
                    // need to remove, when the driver add event for each fb
                    mdispstate.connect(fbid);
                    if(fbid != 0) mHandler.updateState("CONNECTED");
                    setDisplayConnectState(fbid, true);
                    ret = true;
                    break;
            case DispdResponseCode.InterfaceDisconnected:
                    // need to remove, when the driver add event for each fb
                    if(mdispstate.isconnect(fbid)) {
                        setDisplayConnectState(fbid, false);
                        mdispstate.remove(fbid);
                        if(mdispstate.getcount() <= 1) mHandler.updateState("DISCONNECTED");
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
        boolean found = false;
        int[] request_resolution  = getResolutionInString(currentDisplayMode);
        int[] actual_resolution;

        display_modes = getDisplayModeListFromDispd(dispid);
        mDisplay_modes[dispid] = display_modes;

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
        mDisplay_mode[dispid] = mode;
    /*
        Message msg = Message.obtain();
        msg.what = DISPLAY_MODE_MSG;
        msg.arg1 = dispid;
        msg.obj  = mode;
        mDatabaseHandler.sendMessageDelayed(msg, 10);
            */
    }

    private void setDisplayDefaultdepth(int dispid, int colordepth) {
        mDisplay_colordepth[dispid] = colordepth;
    /*
        Message msg = Message.obtain();
        msg.what = DISPLAY_COLORDEPTH_MSG;
        msg.arg1 = dispid;
        msg.arg2 = colordepth;
        mDatabaseHandler.sendMessageDelayed(msg, 10);
            */
    }

    private void setDisplayConnectState(int fbid, boolean connectState) {
        int dispid = mdispstate.getdispid(fbid);

        if(connectState) selectDisplayDefaultMode(dispid);

            if(dispid == 0) {
                final Intent intent = new Intent(DisplayManager.ACTION_DISPLAY_DEVICE_1_ATTACHED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_DEVICE,  dispid);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_CONNECT, connectState);
                mContext.sendStickyBroadcast(intent);
            }

            if(dispid == 1) {
                final Intent intent = new Intent(DisplayManager.ACTION_DISPLAY_DEVICE_2_ATTACHED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_DEVICE,  dispid);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_CONNECT, connectState);
                mContext.sendStickyBroadcast(intent);
            }

            if(dispid == 2) {
                final Intent intent = new Intent(DisplayManager.ACTION_DISPLAY_DEVICE_3_ATTACHED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_DEVICE,  dispid);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_CONNECT, connectState);
                mContext.sendStickyBroadcast(intent);
            }

            if(dispid == 3) {
                final Intent intent = new Intent(DisplayManager.ACTION_DISPLAY_DEVICE_4_ATTACHED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_DEVICE,  dispid);
                intent.putExtra(DisplayManager.EXTRA_DISPLAY_CONNECT, connectState);
                mContext.sendStickyBroadcast(intent);
            }

        if(dispid > 0 && mDisplay_enable[dispid] == 1) {
            if(connectState) commandDisplayEnable(dispid, 0, true);
            else             commandDisplayEnable(dispid, 0, false);
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
                    int fbid = msg.arg1;
                    int dispid = mdispstate.getdispid(msg.arg1);
                    Intent intent;
                    if(Integer.parseInt(msg.obj.toString()) == 1) {
                        mDispCommand.enable(fbid, mDisplay_mode[dispid], mDisplay_rotation[dispid], mDisplay_overscan[dispid], mDisplay_mirror[dispid], mDisplay_colordepth[dispid]);

                        intent = new Intent(Intent.ACTION_HDMI_AUDIO_PLUG);
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                        intent.putExtra("state", 1);
                        intent.putExtra("name", "hdmi");
                        ActivityManagerNative.broadcastStickyIntent(intent, null);
                    }
                    else {
                        intent = new Intent(Intent.ACTION_HDMI_AUDIO_PLUG);
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                        intent.putExtra("state", 0);
                        intent.putExtra("name", "hdmi");
                        ActivityManagerNative.broadcastStickyIntent(intent, null);
                        mDispCommand.disable(fbid);
                    }
                    if(msg.arg2 == 1){
                        Settings.System.putInt(mContext.getContentResolver(), mSettings_enable[dispid], Integer.parseInt(msg.obj.toString()));
                        Settings.System.putInt(mContext.getContentResolver(), mSettings_mirror[dispid], mDisplay_mirror[dispid]);
                        Settings.System.putInt(mContext.getContentResolver(), mSettings_rotation[dispid], mDisplay_rotation[dispid]);
                        Settings.System.putInt(mContext.getContentResolver(), mSettings_overscan[dispid], mDisplay_overscan[dispid]);
                        Settings.System.putString(mContext.getContentResolver(), mSettings_mode[dispid], mDisplay_mode[dispid]);
                        Settings.System.putInt(mContext.getContentResolver(), mSettings_colordepth[dispid], mDisplay_colordepth[dispid]);
                    }
                    break;
                }
                case DISPLAY_MIRROR_MSG: {
                    int fbid = msg.arg1;
                    int dispid = mdispstate.getdispid(msg.arg1);
                    if(mDisplay_enable[dispid] == 1) mDispCommand.setMirror(fbid, Integer.parseInt(msg.obj.toString()));
                    Settings.System.putInt(mContext.getContentResolver(), mSettings_mirror[dispid], Integer.parseInt(msg.obj.toString()));
                    break;
                }
                case DISPLAY_ROTATION_MSG: {
                    int fbid = msg.arg1;
                    int dispid = mdispstate.getdispid(msg.arg1);
                    if(mDisplay_enable[dispid] == 1) mDispCommand.setRotation(fbid, Integer.parseInt(msg.obj.toString()));
                    Settings.System.putInt(mContext.getContentResolver(), mSettings_rotation[dispid], Integer.parseInt(msg.obj.toString()));
                    break;
                }
                case DISPLAY_OVERSCAN_MSG: {
                    int fbid = msg.arg1;
                    int dispid = mdispstate.getdispid(msg.arg1);
                    if(mDisplay_enable[dispid] == 1) mDispCommand.setOverScan(fbid, Integer.parseInt(msg.obj.toString()));
                    Settings.System.putInt(mContext.getContentResolver(), mSettings_overscan[dispid], Integer.parseInt(msg.obj.toString()));
                    break;
                }
                case DISPLAY_MODE_MSG: {
                    int fbid = msg.arg1;
                    int dispid = mdispstate.getdispid(msg.arg1);
                    if(mDisplay_enable[dispid] == 1) mDispCommand.setResolution(fbid, (String)msg.obj);
                    Settings.System.putString(mContext.getContentResolver(), mSettings_mode[dispid], (String)msg.obj);
                    break;
                }
                case DISPLAY_COLORDEPTH_MSG: {
                    int fbid = msg.arg1;
                    int dispid = mdispstate.getdispid(msg.arg1);
                    if(mDisplay_enable[dispid] == 1) mDispCommand.setColorDepth(fbid, Integer.parseInt(msg.obj.toString()));
                    Settings.System.putInt(mContext.getContentResolver(), mSettings_colordepth[dispid], Integer.parseInt(msg.obj.toString()));
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
        int fbid = mdispstate.getfbid(dispid);
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
        return mDisplay_modes[dispid];
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

    public boolean setDisplayOverScan(int dispid, int overscan){
        commandDisplayOverScan(dispid, 1, overscan);
        return true;
    }

    public boolean setDisplayColorDepth(int dispid, int colordepth) {
        commandDisplayColorDepth(dispid, 1, colordepth);
        return true;
    }


    //set the resolution
    public boolean commandDisplayMode(int dispid, int save, String disp_mode) throws IllegalStateException {
        int fbid = mdispstate.getfbid(dispid);
        if (DBG) Slog.d(TAG, " dispid " + dispid +" fbid "+ fbid +"setDisplayMode "+ disp_mode );
        if(disp_mode.equals(mDisplay_mode[dispid])) return true;

        mDisplay_mode[dispid] = disp_mode;
        if(dispid==0)  writeConfigFile(mDisplay_mode[dispid], mDisplay_colordepth[dispid]);

        Message msg = Message.obtain();
        msg.what = DISPLAY_MODE_MSG;
        msg.arg1 = fbid;
        msg.arg2 = save;
        msg.obj  = disp_mode;
        mDatabaseHandler.sendMessageDelayed(msg, 10);


        return true;
    }

    public boolean commandDisplayEnable(int dispid, int save, boolean enable) {
        int fbid = mdispstate.getfbid(dispid);
        if (DBG) Slog.d(TAG, " dispid " + dispid +" fbid "+ fbid +"setDisplayEnable "+ enable );
        if(save==1) mDisplay_enable[dispid] = enable?1:0;

        Message msg = Message.obtain();
        msg.what = DISPLAY_ENABLE_MSG;
        msg.arg1 = fbid;
        msg.arg2 = save;
        msg.obj  = enable?1:0;
        mDatabaseHandler.sendMessageDelayed(msg, 10);

        return true;
    }


    public boolean commandDisplayMirror(int dispid, int save, boolean enable) {
        int fbid = mdispstate.getfbid(dispid);
        if (DBG) Slog.d(TAG, " dispid " + dispid +" fbid "+ fbid +"setDisplayMirror "+ enable );
        mDisplay_mirror[dispid] = enable?1:0;

        Message msg = Message.obtain();
        msg.what = DISPLAY_MIRROR_MSG;
        msg.arg1 = fbid;
        msg.arg2 = save;
        msg.obj  = enable?1:0;
        mDatabaseHandler.sendMessageDelayed(msg, 10);


        return true;
    }

    public boolean commandDisplayRotation(int dispid, int save, boolean enable) {
        int fbid = mdispstate.getfbid(dispid);
        if (DBG) Slog.d(TAG, " dispid " + dispid +" fbid "+ fbid +"setDisplayRotation "+ enable );

        mDisplay_rotation[dispid] = enable?1:0;

        Message msg = Message.obtain();
        msg.what = DISPLAY_ROTATION_MSG;
        msg.arg1 = fbid;
        msg.arg2 = save;
        msg.obj  = enable?1:0;
        mDatabaseHandler.sendMessageDelayed(msg, 10);

        return true;
    }

    public boolean commandDisplayOverScan(int dispid, int save, int overscan){
        int fbid = mdispstate.getfbid(dispid);
        if (DBG) Slog.d(TAG, " dispid " + dispid +" fbid "+ fbid +"setDisplayOverScan "+ overscan );
        if(overscan == mDisplay_overscan[dispid]) return true;

        mDisplay_overscan[dispid] = overscan;

        Message msg = Message.obtain();
        msg.what = DISPLAY_OVERSCAN_MSG;
        msg.arg1 = fbid;
        msg.arg2 = save;
        msg.obj  = overscan;
        mDatabaseHandler.sendMessageDelayed(msg, 10);

        return true;
    }

    public boolean commandDisplayColorDepth(int dispid, int save, int colordepth) {
        int fbid = mdispstate.getfbid(dispid);
        if (DBG) Slog.d(TAG, " dispid " + dispid +" fbid "+ fbid +"setDisplayColorDepth "+ colordepth );
        if(colordepth == mDisplay_colordepth[dispid]) return true;


        mDisplay_colordepth[dispid] = colordepth;

        if(dispid==0)  writeConfigFile(mDisplay_mode[dispid], mDisplay_colordepth[dispid]);

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
        return mDisplay_enable[dispid]==1?true:false;
    }

    public boolean getDisplayMirror(int dispid) {
        return mDisplay_mirror[dispid]==1?true:false;
    }

    public boolean getDisplayRotation(int dispid) {
        return mDisplay_rotation[dispid]==1?true:false;
    }

    public String getDisplayMode(int dispid) {
        return mDisplay_mode[dispid];
    }

    public int getDisplayOverScan(int dispid) {
        return mDisplay_overscan[dispid];
    }

    public int getDisplayColorDepth(int dispid) {
        return mDisplay_colordepth[dispid];
    }
}
