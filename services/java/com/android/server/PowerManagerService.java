/*
 * Copyright (C) 2007 The Android Open Source Project
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

import com.android.internal.app.IBatteryStats;
import com.android.internal.app.ShutdownThread;
import com.android.server.am.BatteryStatsService;

import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentQueryMap;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.LocalPowerManager;
import android.os.Parcel;
import android.os.Power;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.WorkSource;
import android.os.storage.StorageManager;
import android.provider.Settings.SettingNotFoundException;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.view.WindowManagerPolicy;
import static android.provider.Settings.System.DIM_SCREEN;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
import static android.provider.Settings.System.SCREEN_OFF_TIMEOUT;
import static android.provider.Settings.System.STAY_ON_WHILE_PLUGGED_IN;
import static android.provider.Settings.System.WINDOW_ANIMATION_SCALE;
import static android.provider.Settings.System.TRANSITION_ANIMATION_SCALE;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Observable;
import java.util.Observer;

class PowerManagerService extends IPowerManager.Stub
        implements LocalPowerManager, Watchdog.Monitor {

    private static final String TAG = "PowerManagerService";
    static final String PARTIAL_NAME = "PowerManagerService";

    private static final boolean LOG_PARTIAL_WL = true;

    // Indicates whether touch-down cycles should be logged as part of the
    // LOG_POWER_SCREEN_STATE log events
    private static final boolean LOG_TOUCH_DOWNS = true;

    private static final int LOCK_MASK = PowerManager.PARTIAL_WAKE_LOCK
                                        | PowerManager.FULL_WAKE_LOCK;

    // Default timeout for screen off, if not found in settings database = 15 seconds.
    private static final int DEFAULT_SCREEN_OFF_TIMEOUT = 15000;
    private static final int DEFAULT_SCREEN_BRIGHTNESS = 102;

    // flags for setPowerState
    private static final int SCREEN_ON_BIT          = 0x00000001;
    private static final int BATTERY_LOW_BIT        = 0x00000010;

    // values for setPowerState

    // SCREEN_OFF == everything off
    private static final int SCREEN_OFF         = 0x00000000;
    private static final int SCREEN_ON          = 0x00000001;

    private final int MY_UID;
    private final int MY_PID;

    private int mEpdFullTimeout = -1;
    private Runnable mEpdFullRunnable = new Runnable() {
        public void run() {
            updateEpdFullRunnable();
            if (!isScreenOn())
                return;
            try {
                IBinder surfaceFlinger = ServiceManager.getService("SurfaceFlinger");
                if (surfaceFlinger != null) {
                    Parcel data = Parcel.obtain();
                    data.writeInterfaceToken("android.ui.ISurfaceComposer");
                    data.writeInt(-1);
                    surfaceFlinger.transact(1020, data, null, 0);
                    data.recycle();
                }
            } catch (RemoteException ex) {
            }
        }
    };
    private void updateEpdFullRunnable() {
        mHandler.removeCallbacks(mEpdFullRunnable);
        if (mEpdFullTimeout > 0) {
            mHandler.postDelayed(mEpdFullRunnable, mEpdFullTimeout);
        }
    }

    private boolean mDoneBooting = false;
    private boolean mBootCompleted = false;
    private int mStayOnConditions = 0;
    private final int[] mBroadcastQueue = new int[] { -1, -1, -1 };
    private final int[] mBroadcastWhy = new int[3];
    private int mPartialCount = 0;
    private int mPowerState;
    // mScreenOffReason can be WindowManagerPolicy.OFF_BECAUSE_OF_USER,
    // WindowManagerPolicy.OFF_BECAUSE_OF_TIMEOUT or WindowManagerPolicy.OFF_BECAUSE_OF_PROX_SENSOR
    private int mScreenOffReason;
    private int mUserState;
    private boolean mUserActivityAllowed = true;
    private int mMaximumScreenOffTimeout = Integer.MAX_VALUE;
    private int mWakeLockState;
    private long mLastEventTime = 0;
    private long mScreenOffTime;
    private volatile WindowManagerPolicy mPolicy;
    private final LockList mLocks = new LockList();
    private Intent mScreenOffIntent;
    private Intent mScreenOnIntent;
    private Context mContext;
    private UnsynchronizedWakeLock mBroadcastWakeLock;
    private UnsynchronizedWakeLock mStayOnWhilePluggedInScreenLock;
    private UnsynchronizedWakeLock mStayOnWhilePluggedInPartialLock;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private final TimeoutTask mTimeoutTask = new TimeoutTask();
    private boolean mStillNeedSleepNotification;
    private boolean mIsPowered = false;
    private boolean mIsUsbPlugin = false;
    private boolean mIsWifiEnabled = false;
    private IActivityManager mActivityService;
    private IBatteryStats mBatteryStats;
    private BatteryService mBatteryService;
    private long mNextTimeout;
    private volatile int mPokey = 0;
    private volatile boolean mPokeAwakeOnSet = false;
    private volatile boolean mInitComplete = false;
    private final HashMap<IBinder,PokeLock> mPokeLocks = new HashMap<IBinder,PokeLock>();
    // mLastScreenOnTime is the time the screen was last turned on
    private long mLastScreenOnTime;
    boolean mUnplugTurnsOnScreen;
    private int mWarningSpewThrottleCount;
    private long mWarningSpewThrottleTime;

    // Used when logging number and duration of touch-down cycles
    private long mTotalTouchDownTime;
    private long mLastTouchDown;
    private int mTouchCycles;

    // could be either static or controllable at runtime
    private static boolean mSpew;
    
    private native void nativeInit();
    private native void nativeSetPowerState(boolean screenOn, boolean screenBright);
    private native void nativeStartSurfaceFlingerAnimation(int mode);
    private native void nativeStopSurfaceFlingerAnimation();

    private BroadcastReceiver mRtcReceiver;

    private AlarmManager mAlarmManager;
    private PowerManager.WakeLock mAlarmHelperWakeLock;
    private RtcAlarmHelper mIdleWakeAlarmHelper;
    private RtcAlarmHelper mTimeoutTaskAlarmHelper;
    class RtcAlarmHelper {
        private String mAction;
        private Runnable mCancelRunnable;
        private PendingIntent mPendingIntent;
        private Runnable mSetRunnable;
        private long mWakeTime;

        public RtcAlarmHelper(String action) {
            mPendingIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(action), PendingIntent.FLAG_UPDATE_CURRENT);
            mAction = action;
            mSetRunnable = new Runnable() {
                public void run() {
                    if (mSpew) Slog.d(TAG, "set alarm:" + mAction +
                                     ", now:" + System.currentTimeMillis() +
                                     ", wake:" + mWakeTime);
                    mAlarmManager.set(0, mWakeTime, mPendingIntent);
                    if (mAlarmHelperWakeLock.isHeld()) {
                        mAlarmHelperWakeLock.release();
                    }
                }
            };
            mCancelRunnable = new Runnable() {
                public void run() {
                    try {
                        if (mSpew) Slog.d(TAG, "cancel alarm:" + mAction);
                        mAlarmManager.cancel(mPendingIntent);
                    } catch (Exception e) {
                    }
                }
            };
        }

        public void cancel() {
            mHandler.removeCallbacks(mCancelRunnable);
            mHandler.post(mCancelRunnable);
        }

        public boolean isValide(long wakeTime) {
            return (wakeTime > mWakeTime);
        }

        public void setAlarm(long wakeTime) {
            if (!mAlarmHelperWakeLock.isHeld()) {
                mAlarmHelperWakeLock.acquire();
            }
            cancel();
            mWakeTime = wakeTime;
            if (SystemProperties.getBoolean("sys.hw.alarm.align", true)) {
                mWakeTime = mWakeTime;
            }
            mHandler.removeCallbacks(mSetRunnable);
            mHandler.post(mSetRunnable);
        }
    }

    final private String IDLE_WAKE_ACTION = "idle_wake_action";
    final private String TIME_TASK_ACTION = "time_task_action";
    private int mStandbyDelay;
    private int mStandbyTimeoutSetting = 120000;
    private int mScreenBrightnessSetting = DEFAULT_SCREEN_BRIGHTNESS;
    private LightsService mLightsService;
    private LightsService.Light mLcdLight;
    private long mStandbyTime = 0;
    private long mIdleTime = 0;
    private StorageManager mStorageManager = null;
    private Boolean mNeedNativeWake = Boolean.valueOf(true);
    private Runnable mIdleTimer = new Runnable() {
        public void run() {
            if (!isScreenOn()) {
                nativeStartSurfaceFlingerAnimation(1);
                standby();
                return;
            }
            if (mIdleWakeUp) {
                long wakeTime = System.currentTimeMillis() + 60000;
                mIdleWakeAlarmHelper.setAlarm(wakeTime);
            }
            idle();
        }
    };

    /*
    static PrintStream mLog;
    static {
        try {
            mLog = new PrintStream("/data/power.log");
        }
        catch (FileNotFoundException e) {
            android.util.Slog.e(TAG, "Life is hard", e);
        }
    }
    static class Log {
        static void d(String tag, String s) {
            mLog.println(s);
            android.util.Slog.d(tag, s);
        }
        static void i(String tag, String s) {
            mLog.println(s);
            android.util.Slog.i(tag, s);
        }
        static void w(String tag, String s) {
            mLog.println(s);
            android.util.Slog.w(tag, s);
        }
        static void e(String tag, String s) {
            mLog.println(s);
            android.util.Slog.e(tag, s);
        }
    }
    */

    private int mIdleDelay;

    public void pokeSystem() {
        if (mSpew) Slog.d(TAG, "pokeSystem");
        wake();
    }

    private void resetIdle(boolean reset) {
        if (mSpew) Slog.d(TAG, "resetIdle:" + reset);
        mHandler.removeCallbacks(mIdleTimer);
        mIdleWakeAlarmHelper.cancel();
        mIdleTime = 0;
        if (reset) {
            mIdleTime = System.currentTimeMillis() + mIdleDelay;
            mHandler.postDelayed(mIdleTimer, mIdleDelay);
        }
    }

    public void cancelGoToAutoShutdown() {
        Log.w("", "cancelGoToAutoShutdown!");
        Intent intent = new Intent("COM.CARATION.AUTO_SHUTDOWN");
        intent.putExtra("IS_SHUTDOWN", false);
        mContext.startService(intent);
    }

    private void cancelAlarms() {
        mIdleWakeAlarmHelper.cancel();
        cancelTimeoutTask();
    }

    public int getIdleDelay() {
        return mIdleDelay;
    }

    public void setIdleDelay(int delay) {
        mIdleDelay = delay;
        pokeSystem();
    }

    /**
     * This class works around a deadlock between the lock in PowerManager.WakeLock
     * and our synchronizing on mLocks.  PowerManager.WakeLock synchronizes on its
     * mToken object so it can be accessed from any thread, but it calls into here
     * with its lock held.  This class is essentially a reimplementation of
     * PowerManager.WakeLock, but without that extra synchronized block, because we'll
     * only call it with our own locks held.
     */
    private class UnsynchronizedWakeLock {
        int mFlags;
        String mTag;
        IBinder mToken;
        int mCount = 0;
        boolean mRefCounted;
        boolean mHeld;

        UnsynchronizedWakeLock(int flags, String tag, boolean refCounted) {
            mFlags = flags;
            mTag = tag;
            mToken = new Binder();
            mRefCounted = refCounted;
        }

        public void acquire() {
            if (!mRefCounted || mCount++ == 0) {
                long ident = Binder.clearCallingIdentity();
                try {
                    PowerManagerService.this.acquireWakeLockLocked(mFlags, mToken,
                            MY_UID, MY_PID, mTag, null);
                    mHeld = true;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        public void release() {
            if (!mRefCounted || --mCount == 0) {
                PowerManagerService.this.releaseWakeLockLocked(mToken, 0, false);
                mHeld = false;
            }
            if (mCount < 0) {
                throw new RuntimeException("WakeLock under-locked " + mTag);
            }
        }

        public boolean isHeld()
        {
            return mHeld;
        }

        public String toString() {
            return "UnsynchronizedWakeLock(mFlags=0x" + Integer.toHexString(mFlags)
                    + " mCount=" + mCount + " mHeld=" + mHeld + ")";
        }
    }

    private final class BatteryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLocks) {
                boolean wasUsbPlugin = mIsUsbPlugin;
                mIsUsbPlugin = mBatteryService.isPowered(BatteryManager.BATTERY_PLUGGED_USB);
                if (mSpew) Log.d(TAG, "wasUsbPlugin =" + wasUsbPlugin + " mIsUsbPlugin = " + mIsUsbPlugin);
                if (mIsUsbPlugin != wasUsbPlugin) {
                    if (mIsUsbPlugin) {
                        resetIdle(false);
                    } else {
                        resetIdle(true);
                    }
                }

                boolean wasPowered = mIsPowered;
                mIsPowered = mBatteryService.isPowered();
                if (mSpew) Log.d(TAG, "wasPowered =" + wasPowered + " mIsPowered = " + mIsPowered);
                if (mIsPowered != wasPowered) {
                    // update mStayOnWhilePluggedIn wake lock
                    updateWakeLockLocked();

                    // treat plugging and unplugging the devices as a user activity.
                    // users find it disconcerting when they unplug the device
                    // and it shuts off right away.
                    // to avoid turning on the screen when unplugging, we only trigger
                    // user activity when screen was already on.
                    // temporarily set mUserActivityAllowed to true so this will work
                    // even when the keyguard is on.
                    // However, you can also set config_unplugTurnsOnScreen to have it
                    // turn on.  Some devices want this because they don't have a
                    // charging LED.
                    synchronized (mLocks) {
                        if (!wasPowered || (mPowerState & SCREEN_ON_BIT) != 0 ||
                                mUnplugTurnsOnScreen) {
                            forceUserActivityLocked();
                        }
                    }
                }
            }
        }
    }

    private final class BootCompletedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            bootCompleted();
        }
    }

    private final class WifiReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                boolean wasWifiEnabled = mIsWifiEnabled;
                int wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                                   WifiManager.WIFI_STATE_UNKNOWN);
                mIsWifiEnabled = (wifiState == WifiManager.WIFI_STATE_ENABLED);
                if (mIsWifiEnabled != wasWifiEnabled) {
                    if (mIsWifiEnabled) {
                        resetIdle(false);
                        if (mSpew) Log.d(TAG, "Disabled idle due to wifi enabled");
                    } else {
                        if (mSpew) Log.d(TAG, "Enabled idle due to wifi disabled");
                        resetIdle(true);
                    }
                }
            }
        }
    }

    /**
     * Set the setting that determines whether the device stays on when plugged in.
     * The argument is a bit string, with each bit specifying a power source that,
     * when the device is connected to that source, causes the device to stay on.
     * See {@link android.os.BatteryManager} for the list of power sources that
     * can be specified. Current values include {@link android.os.BatteryManager#BATTERY_PLUGGED_AC}
     * and {@link android.os.BatteryManager#BATTERY_PLUGGED_USB}
     * @param val an {@code int} containing the bits that specify which power sources
     * should cause the device to stay on.
     */
    public void setStayOnSetting(int val) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WRITE_SETTINGS, null);
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.STAY_ON_WHILE_PLUGGED_IN, val);
    }

    public void setMaximumScreenOffTimeount(int timeMs) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS, null);
    }

    private class SettingsObserver implements Observer {
        private int getInt(String name, int defValue) {
            ContentValues values = mSettings.getValues(name);
            Integer iVal = values != null ? values.getAsInteger(Settings.System.VALUE) : null;
            return iVal != null ? iVal : defValue;
        }

        private float getFloat(String name, float defValue) {
            ContentValues values = mSettings.getValues(name);
            Float fVal = values != null ? values.getAsFloat(Settings.System.VALUE) : null;
            return fVal != null ? fVal : defValue;
        }

        public void update(Observable o, Object arg) {
            synchronized (mLocks) {
                // STAY_ON_WHILE_PLUGGED_IN, default to when plugged into AC
                mStayOnConditions = getInt(STAY_ON_WHILE_PLUGGED_IN,
                        BatteryManager.BATTERY_PLUGGED_AC);
                updateWakeLockLocked();

                mStandbyTimeoutSetting = getInt(Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_SCREEN_OFF_TIMEOUT);
                final int epdFullTimeoutSetting = getInt(Settings.System.EPD_FULL_TIMEOUT, -1);
                if (epdFullTimeoutSetting != mEpdFullTimeout) {
                    mEpdFullTimeout = epdFullTimeoutSetting;
                    updateEpdFullRunnable();
                }
                mScreenBrightnessSetting = getInt("screen_brightness", DEFAULT_SCREEN_BRIGHTNESS);
                updateTimeoutSettingsLocked();
                updateLightsLocked();
            }
        }
    }

    PowerManagerService() {
        // Hack to get our uid...  should have a func for this.
        long token = Binder.clearCallingIdentity();
        MY_UID = Process.myUid();
        MY_PID = Process.myPid();
        Binder.restoreCallingIdentity(token);

        // XXX remove this when the kernel doesn't timeout wake locks
        Power.setLastUserActivityTimeout(7*24*3600*1000); // one week

        // assume nothing is on yet
        mUserState = mPowerState = 0;

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);
    }

    private ContentQueryMap mSettings;

    void init(Context context, LightsService lights, IActivityManager activity,
            BatteryService battery) {
        mContext = context;
        mActivityService = activity;
        mBatteryStats = BatteryStatsService.getService();
        mBatteryService = battery;
        mLightsService = lights;
        mLcdLight = lights.getLight(0);

        nativeInit();
        synchronized (mLocks) {
            updateNativePowerStateLocked();
        }

        mHandlerThread = new HandlerThread("PowerManagerService") {
            @Override
            protected void onLooperPrepared() {
                super.onLooperPrepared();
                initInThread();
            }
        };
        mHandlerThread.start();

        synchronized (mHandlerThread) {
            while (!mInitComplete) {
                try {
                    mHandlerThread.wait();
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }
        
        nativeInit();
        synchronized (mLocks) {
            updateNativePowerStateLocked();
        }
    }

    void initInThread() {
        mHandler = new Handler();

        mBroadcastWakeLock = new UnsynchronizedWakeLock(
                                PowerManager.PARTIAL_WAKE_LOCK, "sleep_broadcast", true);
        mStayOnWhilePluggedInScreenLock = new UnsynchronizedWakeLock(
                                PowerManager.FULL_WAKE_LOCK, "StayOnWhilePluggedIn Screen On", false);
        mStayOnWhilePluggedInPartialLock = new UnsynchronizedWakeLock(
                                PowerManager.PARTIAL_WAKE_LOCK, "StayOnWhilePluggedIn Partial", false);

        mScreenOnIntent = new Intent(Intent.ACTION_SCREEN_ON);
        mScreenOnIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mScreenOffIntent = new Intent(Intent.ACTION_SCREEN_OFF);
        mScreenOffIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);

        Resources resources = mContext.getResources();

        mUnplugTurnsOnScreen = resources.getBoolean(
                com.android.internal.R.bool.config_unplugTurnsOnScreen);

       ContentResolver resolver = mContext.getContentResolver();
        Cursor settingsCursor = resolver.query(Settings.System.CONTENT_URI, null,
                "(" + Settings.System.NAME + "=?) or ("
                        + Settings.System.NAME + "=?) or ("
                        + Settings.System.NAME + "=?)",
                new String[]{STAY_ON_WHILE_PLUGGED_IN, SCREEN_OFF_TIMEOUT,
                        Settings.System.EPD_FULL_TIMEOUT},
                null);
        mSettings = new ContentQueryMap(settingsCursor, Settings.System.NAME, true, mHandler);
        SettingsObserver settingsObserver = new SettingsObserver();
        mSettings.addObserver(settingsObserver);

        mIdleDelay = SystemProperties.getInt("persist.sys.idle-delay", 10000);
        mSpew = SystemProperties.getBoolean("debug.pm.print", false);

        // pretend that the settings changed so we will get their initial state
        settingsObserver.update(mSettings, null);

        // register for the battery changed notifications
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        mContext.registerReceiver(new BatteryReceiver(), filter);
        filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mContext.registerReceiver(new WifiReceiver(), filter);
        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        mContext.registerReceiver(new BootCompletedReceiver(), filter);

        synchronized (mHandlerThread) {
            mInitComplete = true;
            mHandlerThread.notifyAll();
        }
    }

    private class WakeLock implements IBinder.DeathRecipient
    {
        WakeLock(int f, IBinder b, String t, int u, int p) {
            super();
            flags = f;
            binder = b;
            tag = t;
            uid = u == MY_UID ? Process.SYSTEM_UID : u;
            pid = p;
            if (u != MY_UID || (
                    !"KEEP_SCREEN_ON_FLAG".equals(tag)
                    && !"KeyInputQueue".equals(tag))) {
                monitorType = (f & LOCK_MASK) == PowerManager.PARTIAL_WAKE_LOCK
                        ? BatteryStats.WAKE_TYPE_PARTIAL
                        : BatteryStats.WAKE_TYPE_FULL;
            } else {
                monitorType = -1;
            }
            try {
                b.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }
        public void binderDied() {
            synchronized (mLocks) {
                releaseWakeLockLocked(this.binder, 0, true);
            }
        }
        final int flags;
        final IBinder binder;
        final String tag;
        final int uid;
        final int pid;
        final int monitorType;
        WorkSource ws;
        boolean activated = true;
        int minState;
    }

    private void updateWakeLockLocked() {
        if (mStayOnConditions != 0 && mBatteryService.isPowered(mStayOnConditions)) {
            // keep the device on if we're plugged in and mStayOnWhilePluggedIn is set.
            mStayOnWhilePluggedInScreenLock.acquire();
            mStayOnWhilePluggedInPartialLock.acquire();
        } else {
            mStayOnWhilePluggedInScreenLock.release();
            mStayOnWhilePluggedInPartialLock.release();
        }
    }

    private boolean isScreenLock(int flags)
    {
        int n = flags & LOCK_MASK;
        return n == PowerManager.FULL_WAKE_LOCK;
    }

    void enforceWakeSourcePermission(int uid, int pid) {
        if (uid == Process.myUid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_DEVICE_STATS,
                pid, uid, null);
    }

    public void acquireWakeLock(int flags, IBinder lock, String tag, WorkSource ws) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        if (uid != Process.myUid()) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        }
        if (ws != null) {
            enforceWakeSourcePermission(uid, pid);
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mLocks) {
                acquireWakeLockLocked(flags, lock, uid, pid, tag, ws);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    void noteStartWakeLocked(WakeLock wl, WorkSource ws) {
        if (wl.monitorType >= 0) {
            long origId = Binder.clearCallingIdentity();
            try {
                if (ws != null) {
                    mBatteryStats.noteStartWakelockFromSource(ws, wl.pid, wl.tag,
                            wl.monitorType);
                } else {
                    mBatteryStats.noteStartWakelock(wl.uid, wl.pid, wl.tag, wl.monitorType);
                }
            } catch (RemoteException e) {
                // Ignore
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    void noteStopWakeLocked(WakeLock wl, WorkSource ws) {
        if (wl.monitorType >= 0) {
            long origId = Binder.clearCallingIdentity();
            try {
                if (ws != null) {
                    mBatteryStats.noteStopWakelockFromSource(ws, wl.pid, wl.tag,
                            wl.monitorType);
                } else {
                    mBatteryStats.noteStopWakelock(wl.uid, wl.pid, wl.tag, wl.monitorType);
                }
            } catch (RemoteException e) {
                // Ignore
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    public void acquireWakeLockLocked(int flags, IBinder lock, int uid, int pid, String tag,
            WorkSource ws) {
        if (mSpew) {
            Slog.d(TAG, "acquireWakeLock flags=0x" + Integer.toHexString(flags) + " tag=" + tag);
        }

        if (flags == PowerManager.SCREEN_BRIGHT_WAKE_LOCK ||
            flags == PowerManager.SCREEN_DIM_WAKE_LOCK) {
            flags = PowerManager.FULL_WAKE_LOCK;
        }
        if (ws != null && ws.size() == 0) {
            ws = null;
        }

        int index = mLocks.getIndex(lock);
        WakeLock wl;
        boolean newlock;
        boolean diffsource;
        WorkSource oldsource;
        if (index < 0) {
            wl = new WakeLock(flags, lock, tag, uid, pid);
            switch (wl.flags & LOCK_MASK)
            {
                case PowerManager.FULL_WAKE_LOCK:
                    wl.minState = SCREEN_ON;
                    break;
                case PowerManager.PARTIAL_WAKE_LOCK:
                    break;
                default:
                    // just log and bail.  we're in the server, so don't
                    // throw an exception.
                    Slog.e(TAG, "bad wakelock type for lock '" + tag + "' "
                            + " flags=" + flags);
                    return;
            }
            mLocks.addLock(wl);
            if (ws != null) {
                wl.ws = new WorkSource(ws);
            }
            newlock = true;
            diffsource = false;
            oldsource = null;
        } else {
            wl = mLocks.get(index);
            newlock = false;
            oldsource = wl.ws;
            if (oldsource != null) {
                if (ws == null) {
                    wl.ws = null;
                    diffsource = true;
                } else {
                    diffsource = oldsource.diff(ws);
                }
            } else if (ws != null) {
                diffsource = true;
            } else {
                diffsource = false;
            }
            if (diffsource) {
                wl.ws = new WorkSource(ws);
            }
        }
        if (isScreenLock(flags)) {
            // if this causes a wakeup, we reactivate all of the locks and
            // set it to whatever they want.  otherwise, we modulate that
            // by the current state so we never turn it more on than
            // it already is.
                if ((wl.flags & PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0) {
                    int oldWakeLockState = mWakeLockState;
                    mWakeLockState = mLocks.reactivateScreenLocksLocked();
                    if (mSpew) {
                        Slog.d(TAG, "wakeup here mUserState=0x" + Integer.toHexString(mUserState)
                                + " mWakeLockState=0x"
                                + Integer.toHexString(mWakeLockState)
                                + " previous wakeLockState=0x"
                                + Integer.toHexString(oldWakeLockState));
                    }
                } else {
                    if (mSpew) {
                        Slog.d(TAG, "here mUserState=0x" + Integer.toHexString(mUserState)
                                + " mLocks.gatherState()=0x"
                                + Integer.toHexString(mLocks.gatherState())
                                + " mWakeLockState=0x" + Integer.toHexString(mWakeLockState));
                    }
                    mWakeLockState = (mUserState | mWakeLockState) & mLocks.gatherState();
                }
                setPowerState(mWakeLockState | mUserState);
        }
        else if ((flags & LOCK_MASK) == PowerManager.PARTIAL_WAKE_LOCK) {
            if (newlock) {
                mPartialCount++;
                if (mPartialCount == 1) {
                    if (LOG_PARTIAL_WL) EventLog.writeEvent(EventLogTags.POWER_PARTIAL_WAKE_STATE, 1, tag);
                }
            }
            Power.acquireWakeLock(Power.PARTIAL_WAKE_LOCK,PARTIAL_NAME);
        }

        if (diffsource) {
            // If the lock sources have changed, need to first release the
            // old ones.
            noteStopWakeLocked(wl, oldsource);
        }
        if (newlock || diffsource) {
            noteStartWakeLocked(wl, ws);
        }
    }

    public void updateWakeLockWorkSource(IBinder lock, WorkSource ws) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        if (ws != null && ws.size() == 0) {
            ws = null;
        }
        if (ws != null) {
            enforceWakeSourcePermission(uid, pid);
        }
        synchronized (mLocks) {
            int index = mLocks.getIndex(lock);
            if (index < 0) {
                throw new IllegalArgumentException("Wake lock not active");
            }
            WakeLock wl = mLocks.get(index);
            WorkSource oldsource = wl.ws;
            wl.ws = ws != null ? new WorkSource(ws) : null;
            noteStopWakeLocked(wl, oldsource);
            noteStartWakeLocked(wl, ws);
        }
    }

    public void releaseWakeLock(IBinder lock, int flags) {
        int uid = Binder.getCallingUid();
        if (uid != Process.myUid()) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        }

        synchronized (mLocks) {
            releaseWakeLockLocked(lock, flags, false);
        }
    }

    private void releaseWakeLockLocked(IBinder lock, int flags, boolean death) {
        WakeLock wl = mLocks.removeLock(lock);
        if (wl == null) {
            return;
        }

        if (mSpew) {
            Slog.d(TAG, "releaseWakeLock flags=0x"
                    + Integer.toHexString(wl.flags) + " tag=" + wl.tag);
        }

        if (isScreenLock(wl.flags)) {
                mWakeLockState = mLocks.gatherState();
                // goes in the middle to reduce flicker
                if ((wl.flags & PowerManager.ON_AFTER_RELEASE) != 0) {
                    userActivity(SystemClock.uptimeMillis(), false);
                }
                setPowerState(mWakeLockState | mUserState);
        }
        else if ((wl.flags & LOCK_MASK) == PowerManager.PARTIAL_WAKE_LOCK) {
            mPartialCount--;
            if (mPartialCount == 0) {
                if (LOG_PARTIAL_WL) EventLog.writeEvent(EventLogTags.POWER_PARTIAL_WAKE_STATE, 0, wl.tag);
                Power.releaseWakeLock(PARTIAL_NAME);
            }
        }
        // Unlink the lock from the binder.
        wl.binder.unlinkToDeath(wl, 0);

        noteStopWakeLocked(wl, wl.ws);
    }

    private class PokeLock implements IBinder.DeathRecipient
    {
        PokeLock(int p, IBinder b, String t) {
            super();
            this.pokey = p;
            this.binder = b;
            this.tag = t;
            try {
                b.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }
        public void binderDied() {
            setPokeLock(0, this.binder, this.tag);
        }
        int pokey;
        IBinder binder;
        String tag;
        boolean awakeOnSet;
    }

    public void setPokeLock(int pokey, IBinder token, String tag) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        if (token == null) {
            Slog.e(TAG, "setPokeLock got null token for tag='" + tag + "'");
            return;
        }

        if ((pokey & POKE_LOCK_TIMEOUT_MASK) == POKE_LOCK_TIMEOUT_MASK) {
            throw new IllegalArgumentException("setPokeLock can't have both POKE_LOCK_SHORT_TIMEOUT"
                    + " and POKE_LOCK_MEDIUM_TIMEOUT");
        }

        synchronized (mLocks) {
            if (pokey != 0) {
                PokeLock p = mPokeLocks.get(token);
                int oldPokey = 0;
                if (p != null) {
                    oldPokey = p.pokey;
                    p.pokey = pokey;
                } else {
                    p = new PokeLock(pokey, token, tag);
                    mPokeLocks.put(token, p);
                }
                int oldTimeout = oldPokey & POKE_LOCK_TIMEOUT_MASK;
                int newTimeout = pokey & POKE_LOCK_TIMEOUT_MASK;
                if (((mPowerState & SCREEN_ON_BIT) == 0) && (oldTimeout != newTimeout)) {
                    p.awakeOnSet = true;
                }
            } else {
                PokeLock rLock = mPokeLocks.remove(token);
                if (rLock != null) {
                    token.unlinkToDeath(rLock, 0);
                }
            }

            int oldPokey = mPokey;
            int cumulative = 0;
            boolean oldAwakeOnSet = mPokeAwakeOnSet;
            boolean awakeOnSet = false;
            for (PokeLock p: mPokeLocks.values()) {
                cumulative |= p.pokey;
                if (p.awakeOnSet) {
                    awakeOnSet = true;
                }
            }
            mPokey = cumulative;
            mPokeAwakeOnSet = awakeOnSet;

            int oldCumulativeTimeout = oldPokey & POKE_LOCK_TIMEOUT_MASK;
            int newCumulativeTimeout = pokey & POKE_LOCK_TIMEOUT_MASK;

            if (oldCumulativeTimeout != newCumulativeTimeout) {
                updateTimeoutSettingsLocked();
                // reset the countdown timer, but use the existing nextState so it doesn't
                // change anything
                setTimeoutLocked(SystemClock.uptimeMillis(), mTimeoutTask.nextState);
            }
        }
    }

    private static String lockType(int type)
    {
        switch (type)
        {
            case PowerManager.FULL_WAKE_LOCK:
                return "FULL_WAKE_LOCK                ";
            case PowerManager.PARTIAL_WAKE_LOCK:
                return "PARTIAL_WAKE_LOCK             ";
            default:
                return "???                           ";
        }
    }

    private static String dumpPowerState(int state) {
        return (((state & SCREEN_ON_BIT) != 0)
                        ? "SCREEN_ON_BIT " : "")
                + (((state & BATTERY_LOW_BIT) != 0)
                        ? "BATTERY_LOW_BIT " : "");
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump PowerManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        long now = SystemClock.uptimeMillis();

        synchronized (mLocks) {
            pw.println("Power Manager State:");
            pw.println("  mIsPowered=" + mIsPowered
                    + " mPowerState=" + mPowerState
                    + " mScreenOffTime=" + (SystemClock.elapsedRealtime()-mScreenOffTime)
                    + " ms");
            pw.println("  mPartialCount=" + mPartialCount);
            pw.println("  mWakeLockState=" + dumpPowerState(mWakeLockState));
            pw.println("  mUserState=" + dumpPowerState(mUserState));
            pw.println("  mPowerState=" + dumpPowerState(mPowerState));
            pw.println("  mLocks.gather=" + dumpPowerState(mLocks.gatherState()));
            pw.println("  mNextTimeout=" + mNextTimeout + " now=" + now
                    + " " + ((mNextTimeout-now)/1000) + "s from now");
            pw.println(" mStayOnConditions=" + mStayOnConditions);
            pw.println("  mScreenOffReason=" + mScreenOffReason
                    + " mUserState=" + mUserState);
            pw.println("  mBroadcastQueue={" + mBroadcastQueue[0] + ',' + mBroadcastQueue[1]
                    + ',' + mBroadcastQueue[2] + "}");
            pw.println("  mBroadcastWhy={" + mBroadcastWhy[0] + ',' + mBroadcastWhy[1]
                    + ',' + mBroadcastWhy[2] + "}");
            pw.println("  mPokey=" + mPokey + " mPokeAwakeonSet=" + mPokeAwakeOnSet);
            pw.println(" mUserActivityAllowed=" + mUserActivityAllowed);
            pw.println(" mIdleDelay=" + mIdleDelay);
            pw.println(" mStandbyDelay=" + mStandbyDelay);
            pw.println("from now:  mStandbyTime=" + (mStandbyTime - System.currentTimeMillis())
                    + " mIdleTime=" + (mIdleTime - System.currentTimeMillis()));
            pw.println("  mLastScreenOnTime=" + mLastScreenOnTime);
            pw.println("  mBroadcastWakeLock=" + mBroadcastWakeLock);
            pw.println("  mStayOnWhilePluggedInScreenLock=" + mStayOnWhilePluggedInScreenLock);
            pw.println("  mStayOnWhilePluggedInPartialLock=" + mStayOnWhilePluggedInPartialLock);

            int N = mLocks.size();
            pw.println();
            pw.println("mLocks.size=" + N + ":");
            for (int i=0; i<N; i++) {
                WakeLock wl = mLocks.get(i);
                String type = lockType(wl.flags & LOCK_MASK);
                String acquireCausesWakeup = "";
                if ((wl.flags & PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0) {
                    acquireCausesWakeup = "ACQUIRE_CAUSES_WAKEUP ";
                }
                String activated = "";
                if (wl.activated) {
                   activated = " activated";
                }
                pw.println("  " + type + " '" + wl.tag + "'" + acquireCausesWakeup
                        + activated + " (minState=" + wl.minState + ", uid=" + wl.uid
                        + ", pid=" + wl.pid + ")");
            }

            pw.println();
            pw.println("mPokeLocks.size=" + mPokeLocks.size() + ":");
            for (PokeLock p: mPokeLocks.values()) {
                pw.println("    poke lock '" + p.tag + "':"
                        + ((p.pokey & POKE_LOCK_IGNORE_CHEEK_EVENTS) != 0
                                ? " POKE_LOCK_IGNORE_CHEEK_EVENTS" : "")
                        + ((p.pokey & POKE_LOCK_IGNORE_TOUCH_AND_CHEEK_EVENTS) != 0
                                ? " POKE_LOCK_IGNORE_TOUCH_AND_CHEEK_EVENTS" : "")
                        + ((p.pokey & POKE_LOCK_SHORT_TIMEOUT) != 0
                                ? " POKE_LOCK_SHORT_TIMEOUT" : "")
                        + ((p.pokey & POKE_LOCK_MEDIUM_TIMEOUT) != 0
                                ? " POKE_LOCK_MEDIUM_TIMEOUT" : ""));
            }

            pw.println();
        }
    }

    private void setTimeoutLocked(long now, int nextState) {
        setTimeoutLocked(now, -1, nextState);
    }

    // If they gave a timeoutOverride it is the number of seconds
    // to screen-off.  Figure out where in the countdown cycle we
    // should jump to.
    private void setTimeoutLocked(long now, final long originalTimeoutOverride, int nextState) {
        long timeoutOverride = originalTimeoutOverride;
        if (mBootCompleted) {
            synchronized (mLocks) {
                mTimeoutTaskAlarmHelper.cancel();
                mHandler.removeCallbacks(mTimeoutTask);
                long when = 0;
                if (timeoutOverride <= 0) {
                    switch (nextState)
                    {
                       case SCREEN_OFF:
                            synchronized (mLocks) {
                                when = now + mStandbyDelay;
                            }
                            break;
                        default:
                            when = now;
                            break;
                    }
                } else {
                    override: {
                        when = now + timeoutOverride;
                        nextState = SCREEN_OFF;
                        break override;
                    }
                }
                if (mSpew) {
                    Slog.d(TAG, "setTimeoutLocked now=" + now
                            + " timeoutOverride=" + timeoutOverride
                            + " nextState=" + nextState + " when=" + when);
                }

                mTimeoutTask.nextState = nextState;
                mTimeoutTask.remainingTimeoutOverride = timeoutOverride > 0
                        ? (originalTimeoutOverride - timeoutOverride)
                        : -1;
                if (now >= when) {
                    mHandler.removeCallbacks(mTimeoutTask);
                    mHandler.post(mTimeoutTask);
                } else {
                    mStandbyTime = System.currentTimeMillis() - SystemClock.uptimeMillis() + when;
                    if (SystemProperties.getBoolean("sys.hw.alarm.align", true)) {
                    }
                    mTimeoutTaskAlarmHelper.setAlarm(mStandbyTime);
                }
                mNextTimeout = when; // for debugging
            }
        }
    }

    private class TimeoutTask implements Runnable
    {
        int nextState; // access should be synchronized on mLocks
        long remainingTimeoutOverride;
        public void run()
        {
            synchronized (mLocks) {
                if (mSpew) {
                    Slog.d(TAG, "user activity timeout timed out nextState=" + this.nextState);
                }

                if (nextState == -1) {
                    return;
                }

                mUserState = this.nextState;
                setPowerState(this.nextState | mWakeLockState);

                long now = SystemClock.uptimeMillis();

                switch (this.nextState)
                {
                    case SCREEN_ON:
                        setTimeoutLocked(now, remainingTimeoutOverride, SCREEN_OFF);
                        break;
                }

                int shutdownTime = -1;
                try {
                    shutdownTime = Settings.System.getInt(mContext.getContentResolver(),
                        "auto_shutdown_timeout");
                } catch (Exception e) {
                }
                if (shutdownTime > -1) {
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                Thread.sleep(500);
                            } catch (Exception e) {
                            }
                            Intent intent = new Intent("COM.CARATION.AUTO_SHUTDOWN");
                            intent.putExtra("IS_SHUTDOWN", true);
                            mContext.startService(intent);
                        }
                    }).start();
                }
            }
        }
    }

    private void cancelTimeoutTask() {
        mTimeoutTaskAlarmHelper.cancel();
        synchronized (mLocks) {
            mTimeoutTask.nextState = -1;
        }
    }

    private void sendNotificationLocked(boolean on, int why)
    {
        if (!on) {
            mStillNeedSleepNotification = false;
        }

        // Add to the queue.
        int index = 0;
        while (mBroadcastQueue[index] != -1) {
            index++;
        }
        mBroadcastQueue[index] = on ? 1 : 0;
        mBroadcastWhy[index] = why;

        // If we added it position 2, then there is a pair that can be stripped.
        // If we added it position 1 and we're turning the screen off, we can strip
        // the pair and do nothing, because the screen is already off, and therefore
        // keyguard has already been enabled.
        // However, if we added it at position 1 and we're turning it on, then position
        // 0 was to turn it off, and we can't strip that, because keyguard needs to come
        // on, so have to run the queue then.
        if (index == 2) {
            // While we're collapsing them, if it's going off, and the new reason
            // is more significant than the first, then use the new one.
            if (!on && mBroadcastWhy[0] > why) {
                mBroadcastWhy[0] = why;
            }
            mBroadcastQueue[0] = on ? 1 : 0;
            mBroadcastQueue[1] = -1;
            mBroadcastQueue[2] = -1;
            mBroadcastWakeLock.release();
            mBroadcastWakeLock.release();
            index = 0;
        }
        if (index == 1 && !on) {
            mBroadcastQueue[0] = -1;
            mBroadcastQueue[1] = -1;
            index = -1;
            // The wake lock was being held, but we're not actually going to do any
            // broadcasts, so release the wake lock.
            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_STOP, 1, mBroadcastWakeLock.mCount);
            mBroadcastWakeLock.release();
        }

        // Now send the message.
        if (index >= 0) {
            // Acquire the broadcast wake lock before changing the power
            // state. It will be release after the broadcast is sent.
            // We always increment the ref count for each notification in the queue
            // and always decrement when that notification is handled.
            mBroadcastWakeLock.acquire();
            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_SEND, mBroadcastWakeLock.mCount);
            mHandler.post(mNotificationTask);
        }
    }

    private Runnable mNotificationTask = new Runnable()
    {
        public void run()
        {
            while (true) {
                int value;
                int why;
                WindowManagerPolicy policy;
                synchronized (mLocks) {
                    value = mBroadcastQueue[0];
                    why = mBroadcastWhy[0];
                    for (int i=0; i<2; i++) {
                        mBroadcastQueue[i] = mBroadcastQueue[i+1];
                        mBroadcastWhy[i] = mBroadcastWhy[i+1];
                    }
                    policy = getPolicyLocked();
                }
                if (value == 1) {
                    mScreenOnStart = SystemClock.uptimeMillis();

                    policy.screenTurnedOn();
                    try {
                        ActivityManagerNative.getDefault().wakingUp();
                    } catch (RemoteException e) {
                        // ignore it
                    }

                    if (mSpew) {
                        Slog.d(TAG, "mBroadcastWakeLock=" + mBroadcastWakeLock);
                    }
                    if (mContext != null && ActivityManagerNative.isSystemReady()) {
                        mContext.sendOrderedBroadcast(mScreenOnIntent, null,
                                mScreenOnBroadcastDone, mHandler, 0, null, null);
                    } else {
                        synchronized (mLocks) {
                            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_STOP, 2,
                                    mBroadcastWakeLock.mCount);
                            mBroadcastWakeLock.release();
                        }
                    }
                }
                else if (value == 0) {
                    mScreenOffStart = SystemClock.uptimeMillis();

                    policy.screenTurnedOff(why);
                    try {
                        ActivityManagerNative.getDefault().goingToSleep();
                    } catch (RemoteException e) {
                        // ignore it.
                    }

                    if (mContext != null && ActivityManagerNative.isSystemReady()) {
                        mContext.sendOrderedBroadcast(mScreenOffIntent, null,
                                mScreenOffBroadcastDone, mHandler, 0, null, null);
                    } else {
                        synchronized (mLocks) {
                            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_STOP, 3,
                                    mBroadcastWakeLock.mCount);
                            mBroadcastWakeLock.release();
                        }
                    }
                }
                else {
                    // If we're in this case, then this handler is running for a previous
                    // paired transaction.  mBroadcastWakeLock will already have been released.
                    break;
                }
            }
        }
    };

    long mScreenOnStart;
    private BroadcastReceiver mScreenOnBroadcastDone = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            synchronized (mLocks) {
                EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_DONE, 1,
                        SystemClock.uptimeMillis() - mScreenOnStart, mBroadcastWakeLock.mCount);
                mBroadcastWakeLock.release();
            }
        }
    };

    long mScreenOffStart;
    private BroadcastReceiver mScreenOffBroadcastDone = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            synchronized (mLocks) {
                EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_DONE, 0,
                        SystemClock.uptimeMillis() - mScreenOffStart, mBroadcastWakeLock.mCount);
                mBroadcastWakeLock.release();
            }
        }
    };

    private int idle() {
        synchronized (mNeedNativeWake) {
            if (mNeedNativeWake.booleanValue()) {
                Power.wake();
                SystemClock.sleep(200);
            }
            mNeedNativeWake = Boolean.valueOf(true);
            return isScreenOn() ? Power.idle() : Power.standby();
        }
    }

    private int wake() {
        resetIdle(true);
        synchronized (mNeedNativeWake) {
            if (mNeedNativeWake.booleanValue()) {
                mNeedNativeWake = Boolean.valueOf(false);
                return Power.wake();
            }
            return 0;
        }
    }

    private int standby() {
        synchronized (mNeedNativeWake) {
            if (mNeedNativeWake.booleanValue()) {
                Power.wake();
                SystemClock.sleep(200);
            }
            mNeedNativeWake = Boolean.valueOf(true);
            return Power.standby();
        }
    }

    void logPointerUpEvent() {
        if (LOG_TOUCH_DOWNS) {
            mTotalTouchDownTime += SystemClock.elapsedRealtime() - mLastTouchDown;
            mLastTouchDown = 0;
        }
    }

    void logPointerDownEvent() {
        if (LOG_TOUCH_DOWNS) {
            // If we are not already timing a down/up sequence
            if (mLastTouchDown == 0) {
                mLastTouchDown = SystemClock.elapsedRealtime();
                mTouchCycles++;
            }
        }
    }

    /**
     * Prevents the screen from turning on even if it *should* turn on due
     * to a subsequent full wake lock being acquired.
     * <p>
     * This is a temporary hack that allows an activity to "cover up" any
     * display glitches that happen during the activity's startup
     * sequence.  (Specifically, this API was added to work around a
     * cosmetic bug in the "incoming call" sequence, where the lock screen
     * would flicker briefly before the incoming call UI became visible.)
     * TODO: There ought to be a more elegant way of doing this,
     * probably by having the PowerManager and ActivityManager
     * work together to let apps specify that the screen on/off
     * state should be synchronized with the Activity lifecycle.
     * <p>
     * Note that calling preventScreenOn(true) will NOT turn the screen
     * off if it's currently on.  (This API only affects *future*
     * acquisitions of full wake locks.)
     * But calling preventScreenOn(false) WILL turn the screen on if
     * it's currently off because of a prior preventScreenOn(true) call.
     * <p>
     * Any call to preventScreenOn(true) MUST be followed promptly by a call
     * to preventScreenOn(false).  In fact, if the preventScreenOn(false)
     * call doesn't occur within 5 seconds, we'll turn the screen back on
     * ourselves (and log a warning about it); this prevents a buggy app
     * from disabling the screen forever.)
     * <p>
     * TODO: this feature should really be controlled by a new type of poke
     * lock (rather than an IPowerManager call).
     */
    public void preventScreenOn(boolean prevent) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
    }

    public void setScreenBrightnessOverride(int brightness) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        if (mSpew) Slog.d(TAG, "setScreenBrightnessOverride " + brightness);
        synchronized (mLocks) {
            if (isScreenOn()) {
                updateLightsLocked();
            }
        }
    }

    public void setButtonBrightnessOverride(int brightness) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
    }

    private void updateLightsLocked() {
        if (mSpew) Slog.d(TAG, "updateLightsLocked:mScreenBrightnessSetting = " + mScreenBrightnessSetting);
        long identity = Binder.clearCallingIdentity();
        try {
            mBatteryStats.noteScreenBrightness(this.mScreenBrightnessSetting);
            mBatteryStats.noteScreenOn();
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException calling noteScreenOn on BatteryStatsService", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private int setScreenStateLocked(boolean on) {
        if (mSpew) Slog.d(TAG, "setScreenStateLocked:" + on);
        int err = 0;
        if (on) {
            err = wake();
            nativeStopSurfaceFlingerAnimation();
        } else {
            nativeStartSurfaceFlingerAnimation(1);
            err = standby();
        }
        if (err == 0) {
            mLastScreenOnTime = (on ? SystemClock.elapsedRealtime() : 0);
        }
        return err;
    }

    public void startSurfaceFlingerAnimation(int mode)  {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        nativeStartSurfaceFlingerAnimation(mode);
    }

    private boolean mIdleWakeUp = false;

    public void enableIdleWakeUp(boolean enable) {
        mIdleWakeUp = enable;
    }

    private void setPowerState(int state)
    {
        if (state == 0 && Environment.getFlashStorageState().equals("shared"))
            return;
        setPowerState(state, WindowManagerPolicy.OFF_BECAUSE_OF_TIMEOUT);
    }

    private void setPowerState(int newState, int reason)
    {
        synchronized (mLocks) {
            int err;

            if (mSpew) {
                Slog.d(TAG, "setPowerState: mPowerState=0x" + Integer.toHexString(mPowerState)
                        + " newState=0x" + Integer.toHexString(newState)
                        + " reason=" + reason);
            }

            if (batteryIsLow()) {
                newState |= BATTERY_LOW_BIT;
            } else {
                newState &= ~BATTERY_LOW_BIT;
            }
            if (newState == mPowerState) {
                return;
            }

            boolean oldScreenOn = (mPowerState & SCREEN_ON_BIT) != 0;
            boolean newScreenOn = (newState & SCREEN_ON_BIT) != 0;

            if (mSpew) {
                Slog.d(TAG, "setPowerState: mPowerState=" + mPowerState
                        + " newState=" + newState);
                Slog.d(TAG, "  oldScreenOn=" + oldScreenOn
                         + " newScreenOn=" + newScreenOn);
                Slog.d(TAG, "  oldBatteryLow=" + ((mPowerState & BATTERY_LOW_BIT) != 0)
                         + " newBatteryLow=" + ((newState & BATTERY_LOW_BIT) != 0));
            }

            if (oldScreenOn != newScreenOn) {
                Slog.d(TAG, "setPowerState: screen state change, on:" + newScreenOn);
                if (newScreenOn) {

                    err = setScreenStateLocked(true);
                    updateLightsLocked();

                    mLastTouchDown = 0;
                    mTotalTouchDownTime = 0;
                    mTouchCycles = 0;
                    EventLog.writeEvent(EventLogTags.POWER_SCREEN_STATE, 1, reason,
                            mTotalTouchDownTime, mTouchCycles);
                    if (err == 0) {
                        mPowerState |= SCREEN_ON_BIT;
                        sendNotificationLocked(true, -1);
                    }
                } else {
                    // cancel light sensor task
                    mScreenOffTime = SystemClock.elapsedRealtime();
                    long identity = Binder.clearCallingIdentity();
                    try {
                        mBatteryStats.noteScreenOff();
                    } catch (RemoteException e) {
                        Slog.w(TAG, "RemoteException calling noteScreenOff on BatteryStatsService", e);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                    mPowerState &= ~SCREEN_ON_BIT;
                    mScreenOffReason = reason;
                    EventLog.writeEvent(EventLogTags.POWER_SCREEN_STATE, 0, reason,
                            mTotalTouchDownTime, mTouchCycles);
                    mLastTouchDown = 0;
                    err = setScreenStateLocked(false);
                    if (err == 0) {
                        mScreenOffReason = reason;
                        sendNotificationLocked(false, reason);
                    }
                }
            }
            
            updateNativePowerStateLocked();
        }
    }
    
    private void updateNativePowerStateLocked() {
        nativeSetPowerState(
                (mPowerState & SCREEN_ON_BIT) != 0,
                (mPowerState & SCREEN_ON_BIT) != 0);
    }

    private boolean batteryIsLow() {
        return (!mIsPowered &&
                mBatteryService.getBatteryLevel() <= Power.LOW_BATTERY_THRESHOLD);
    }

    public boolean isScreenOn() {
        synchronized (mLocks) {
            return (mPowerState & SCREEN_ON_BIT) != 0;
        }
    }

    boolean isScreenBright() {
        return isScreenOn();
    }

    private boolean isScreenTurningOffLocked() {
        return false;
    }

    private boolean shouldLog(long time) {
        synchronized (mLocks) {
            if (time > (mWarningSpewThrottleTime + (60*60*1000))) {
                mWarningSpewThrottleTime = time;
                mWarningSpewThrottleCount = 0;
                return true;
            } else if (mWarningSpewThrottleCount < 30) {
                mWarningSpewThrottleCount++;
                return true;
            } else {
                return false;
            }
        }
    }

    private void forceUserActivityLocked() {
        if (isScreenTurningOffLocked()) {
            // cancel animation so userActivity will succeed
        }
        boolean savedActivityAllowed = mUserActivityAllowed;
        mUserActivityAllowed = true;
        userActivity(SystemClock.uptimeMillis(), false);
        mUserActivityAllowed = savedActivityAllowed;
    }

    public void userActivityWithForce(long time, boolean noChangeLights, boolean force) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        userActivity(time, -1, OTHER_EVENT, force);
    }

    public void userActivity(long time, boolean noChangeLights) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER)
                != PackageManager.PERMISSION_GRANTED) {
            if (shouldLog(time)) {
                Slog.w(TAG, "Caller does not have DEVICE_POWER permission.  pid="
                        + Binder.getCallingPid() + " uid=" + Binder.getCallingUid());
            }
            return;
        }

        userActivity(time, -1, OTHER_EVENT, false);
    }

    public void userActivity(long time, boolean noChangeLights, int eventType) {
        userActivity(time, -1, eventType, false);
    }

    public void userActivity(long time, boolean noChangeLights, int eventType, boolean force) {
        userActivity(time, -1, eventType, force);
    }

    /*
     * Reset the user activity timeout to now + timeout.  This overrides whatever else is going
     * on with user activity.  Don't use this function.
     */
    public void clearUserActivityTimeout(long now, long timeout) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        if (mSpew) Slog.i(TAG, "clearUserActivity for " + timeout + "ms from now");
        userActivity(now, timeout, OTHER_EVENT, false);
    }

    private void userActivity(long time, long timeoutOverride,
            int eventType, boolean force) {

        if (((mPokey & POKE_LOCK_IGNORE_CHEEK_EVENTS) != 0)
                && (eventType == CHEEK_EVENT)) {
            if (false) {
                Slog.d(TAG, "dropping cheek event mPokey=0x" + Integer.toHexString(mPokey));
            }
            return;
        }

        if (((mPokey & POKE_LOCK_IGNORE_TOUCH_AND_CHEEK_EVENTS) != 0)
                && (eventType == TOUCH_EVENT || eventType == TOUCH_UP_EVENT
                    || eventType == LONG_TOUCH_EVENT || eventType == CHEEK_EVENT)) {
            if (false) {
                Slog.d(TAG, "dropping touch mPokey=0x" + Integer.toHexString(mPokey));
            }
            return;
        }

        if (false) {
            if (((mPokey & POKE_LOCK_IGNORE_CHEEK_EVENTS) != 0)) {
                Slog.d(TAG, "userActivity !!!");//, new RuntimeException());
            } else {
                Slog.d(TAG, "mPokey=0x" + Integer.toHexString(mPokey));
            }
        }

        synchronized (mLocks) {
            if (mSpew) {
                Slog.d(TAG, "userActivity mLastEventTime=" + mLastEventTime + " time=" + time
                        + " mUserActivityAllowed=" + mUserActivityAllowed
                        + " mUserState=0x" + Integer.toHexString(mUserState)
                        + " mWakeLockState=0x" + Integer.toHexString(mWakeLockState)
                        + " timeoutOverride=" + timeoutOverride
                        + " force=" + force);
            }
            // ignore user activity if we are in the process of turning off the screen
            if (isScreenTurningOffLocked()) {
                Slog.d(TAG, "ignoring user activity while turning off screen");
                return;
            }
            // Disable proximity sensor if if user presses power key while we are in the
            // "waiting for proximity sensor to go negative" state.
            if (mLastEventTime <= time || force) {
                mLastEventTime = time;
                if (mUserActivityAllowed || force) {
                    mUserState |= SCREEN_ON;

                    int uid = Binder.getCallingUid();
                    long ident = Binder.clearCallingIdentity();
                    try {
                        mBatteryStats.noteUserActivity(uid, eventType);
                    } catch (RemoteException e) {
                        // Ignore
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }

                    mWakeLockState = mLocks.reactivateScreenLocksLocked();
                    setPowerState(mUserState | mWakeLockState,
                            WindowManagerPolicy.OFF_BECAUSE_OF_USER);
                    setTimeoutLocked(time, timeoutOverride, SCREEN_ON);
                    pokeSystem();
                }
            }
        }

        if (mPolicy != null) {
            mPolicy.userActivity();
        }
    }

    /**
     * The user requested that we go to sleep (probably with the power button).
     * This overrides all wake locks that are held.
     */
    public void goToSleep(long time)
    {
        goToSleepWithReason(time, WindowManagerPolicy.OFF_BECAUSE_OF_USER);
    }

    /**
     * The user requested that we go to sleep (probably with the power button).
     * This overrides all wake locks that are held.
     */
    public void goToSleepWithReason(long time, int reason)
    {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        synchronized (mLocks) {
            goToStandbyLocked(time, reason);
        }
    }

    /**
     * Reboot the device immediately, passing 'reason' (may be null)
     * to the underlying __reboot system call.  Should not return.
     */
    public void reboot(String reason)
    {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);

        if (mHandler == null || !ActivityManagerNative.isSystemReady()) {
            throw new IllegalStateException("Too early to call reboot()");
        }

        final String finalReason = reason;
        Runnable runnable = new Runnable() {
            public void run() {
                synchronized (this) {
                    ShutdownThread.reboot(mContext, finalReason, false);
                }
                
            }
        };
        // ShutdownThread must run on a looper capable of displaying the UI.
        mHandler.post(runnable);

        // PowerManager.reboot() is documented not to return so just wait for the inevitable.
        synchronized (runnable) {
            while (true) {
                try {
                    runnable.wait();
                } catch (InterruptedException e) {
                }
            }
        }
    }

    /**
     * Crash the runtime (causing a complete restart of the Android framework).
     * Requires REBOOT permission.  Mostly for testing.  Should not return.
     */
    public void crash(final String message)
    {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);
        Thread t = new Thread("PowerManagerService.crash()") {
            public void run() { throw new RuntimeException(message); }
        };
        try {
            t.start();
            t.join();
        } catch (InterruptedException e) {
            Log.wtf(TAG, e);
        }
    }

    private void goToStandbyLocked(long time, int reason) {
        if (mSpew) Slog.d(TAG, "goToStandbyLocked");

        if (mLastEventTime <= time) {
            mLastEventTime = time;
            // cancel all of the wake locks
            mWakeLockState = SCREEN_OFF;
            int N = mLocks.size();
            int numCleared = 0;
            for (int i=0; i<N; i++) {
                WakeLock wl = mLocks.get(i);
                if (isScreenLock(wl.flags)) {
                        mLocks.get(i).activated = false;
                        numCleared++;
                }
            }
            EventLog.writeEvent(EventLogTags.POWER_SLEEP_REQUESTED, numCleared);
            mStillNeedSleepNotification = true;
            mUserState = SCREEN_OFF;
            setPowerState(SCREEN_OFF, reason);
            resetIdle(false);
            cancelAlarms();
        }
    }

    public long timeSinceScreenOn() {
        synchronized (mLocks) {
            if ((mPowerState & SCREEN_ON_BIT) != 0) {
                return 0;
            }
            return SystemClock.elapsedRealtime() - mScreenOffTime;
        }
    }

    public void setKeyboardVisibility(boolean visible) {
    }

    /**
     * When the keyguard is up, it manages the power state, and userActivity doesn't do anything.
     * When disabling user activity we also reset user power state so the keyguard can reset its
     * short screen timeout when keyguard is unhidden.
     */
    public void enableUserActivity(boolean enabled) {
        if (mSpew) {
            Slog.d(TAG, "enableUserActivity " + enabled);
        }
        synchronized (mLocks) {
            mUserActivityAllowed = enabled;
            if (!enabled) {
                // cancel timeout and clear mUserState so the keyguard can set a short timeout
                setTimeoutLocked(SystemClock.uptimeMillis(), 0);
            }
        }
    }

    /**
     * Refreshes cached secure settings.  Called once on startup, and
     * on subsequent changes to secure settings.
     */
    private void updateTimeoutSettingsLocked() {
        int totalDelay = mStandbyTimeoutSetting;
        if (totalDelay > mMaximumScreenOffTimeout) {
            totalDelay = mMaximumScreenOffTimeout;
        }
        if (totalDelay < 0) {
            mStandbyDelay = 0x7fffffff;
        } else {
            mStandbyDelay = totalDelay;
        }
        if (mSpew) Slog.d(TAG, "setTimeouts mStandbyDelay=" + mStandbyDelay);

        if (mDoneBooting) {
            userActivity(SystemClock.uptimeMillis(), false);
        }
    }

    private class LockList extends ArrayList<WakeLock>
    {
        void addLock(WakeLock wl)
        {
            int index = getIndex(wl.binder);
            if (index < 0) {
                this.add(wl);
            }
        }

        WakeLock removeLock(IBinder binder)
        {
            int index = getIndex(binder);
            if (index >= 0) {
                return this.remove(index);
            } else {
                return null;
            }
        }

        int getIndex(IBinder binder)
        {
            int N = this.size();
            for (int i=0; i<N; i++) {
                if (this.get(i).binder == binder) {
                    return i;
                }
            }
            return -1;
        }

        int gatherState()
        {
            int result = 0;
            int N = this.size();
            for (int i=0; i<N; i++) {
                WakeLock wl = this.get(i);
                if (wl.activated) {
                    if (isScreenLock(wl.flags)) {
                        result |= wl.minState;
                    }
                }
            }
            return result;
        }

        int reactivateScreenLocksLocked()
        {
            int result = 0;
            int N = this.size();
            for (int i=0; i<N; i++) {
                WakeLock wl = this.get(i);
                if (isScreenLock(wl.flags)) {
                    wl.activated = true;
                    result |= wl.minState;
                }
            }
            return result;
        }
    }

    void setPolicy(WindowManagerPolicy p) {
        synchronized (mLocks) {
            mPolicy = p;
            mLocks.notifyAll();
        }
    }

    WindowManagerPolicy getPolicyLocked() {
        while (mPolicy == null || !mDoneBooting) {
            try {
                mLocks.wait();
            } catch (InterruptedException e) {
                // Ignore
            }
        }
        return mPolicy;
    }

    void systemReady() {
        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        mRtcReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (mSpew) Slog.d(TAG, "onReceive:" + action + ", now:" + System.currentTimeMillis());
                if (action.equals(IDLE_WAKE_ACTION)) {
                    pokeSystem();
                } else if (action.equals(TIME_TASK_ACTION)) {
                    if (mSpew) Slog.d(TAG, "mTimeoutTask timeout,now:" + System.currentTimeMillis());
                    if (mTimeoutTaskAlarmHelper.isValide(System.currentTimeMillis())) {
                        mHandler.post(mTimeoutTask);
                    }
                } else if (action.equals(Intent.ACTION_TIME_CHANGED)) {
                    cancelAlarms();
                    userActivity(SystemClock.uptimeMillis(), false);
                }
            }
        };
        IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(IDLE_WAKE_ACTION);
        filter.addAction(TIME_TASK_ACTION);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        mContext.registerReceiver(mRtcReceiver, filter);
        PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        mAlarmHelperWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alarm_helper");
        mIdleWakeAlarmHelper = new RtcAlarmHelper(IDLE_WAKE_ACTION);
        mTimeoutTaskAlarmHelper = new RtcAlarmHelper(TIME_TASK_ACTION);
        setPowerState(SCREEN_ON_BIT);
        mUserState |= SCREEN_ON;

        synchronized (mLocks) {
            Slog.d(TAG, "system ready!");
            mDoneBooting = true;
            updateLightsLocked();
        }
    }

    void bootCompleted() {
        Slog.d(TAG, "bootCompleted");
        synchronized (mLocks) {
            mBootCompleted = true;
            userActivity(SystemClock.uptimeMillis(), false, BUTTON_EVENT, true);
            updateWakeLockLocked();
            mLocks.notifyAll();
        }
    }

    // for watchdog
    public void monitor() {
        synchronized (mLocks) { }
    }

    public int getSupportedWakeLockFlags() {
        int result = PowerManager.PARTIAL_WAKE_LOCK
                   | PowerManager.FULL_WAKE_LOCK;

        return result;
    }

    public void setBacklightBrightness(int brightness) {
        if (mSpew) {
            Slog.d(TAG, "setBacklightBrightness: brightness = " + brightness);
        }
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        synchronized (mLocks) {
            mLcdLight.setBrightness(brightness);
            updateLightsLocked();
        }
    }

    public void setAttentionLight(boolean on, int color) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
    }
}
