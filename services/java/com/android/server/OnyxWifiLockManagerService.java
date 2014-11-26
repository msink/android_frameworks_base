package com.android.server;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IOnyxWifiLockManagerService;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.util.*;

class OnyxWifiLockManagerService extends IOnyxWifiLockManagerService.Stub {
    final private static String TAG = "OnyxWifiLockManagerService";
    private boolean debug = false;

    private Context mContext;
    private Handler mHandler;
    private SettingsObserver mSettingsObserver;

    private long mDelay = -1;
    private boolean mIsWifiEnabled = false;
    private Timer mTimer;
    private TimerTask mTimerTask = null;
    private Long mOldTransmit = null;
    private Long mNewTransmit = null;
    private int mTimerNum = 0;
    private Long mMiniTransmit = new Long(2000);
    private static final String MINI_TRANSMIT_FILE = "/vendor/mini_transmit_num";

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                int wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                                   WifiManager.WIFI_STATE_UNKNOWN);
                mIsWifiEnabled = (wifiState == WifiManager.WIFI_STATE_ENABLED);
                if (mIsWifiEnabled) {
                    startCheckWlan();
                } else {
                    stopCheckWlan();
                }
            }
        }
    };

    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }
        public void onChange(boolean selfChange, Uri uri) {
            handleSettingsChanged();
        }
    }

    private void handleSettingsChanged() {
        Log.d(TAG, "WIFI_INACTIVITY_TIMEOUT setting changed");
        int inactivityTimeout = Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.WIFI_INACTIVITY_TIMEOUT, 300000, -2);
        Log.d(TAG, "new value is: " + inactivityTimeout);
        stopCheckWlan();
        if (inactivityTimeout > 0) {
            mTimerNum = inactivityTimeout / 60000;
            startCheckWlan();
        } else {
            mTimerNum = Integer.MAX_VALUE;
        }
    }

    public OnyxWifiLockManagerService(Context context) {
        mContext = context;
        mHandler = new Handler();

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mContext.registerReceiver(mReceiver, filter);

        Long mini_num = Long.valueOf(Settings.System.getInt(
            mContext.getContentResolver(), Settings.System.WIFI_DATA_TRANSIMIT_MIN, 2000));
        if (mini_num.longValue() > 0) {
            mMiniTransmit = mini_num;
        }

        mSettingsObserver = new SettingsObserver(null);
        ContentResolver resolver = mContext.getContentResolver();
        resolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.WIFI_INACTIVITY_TIMEOUT),
            false, mSettingsObserver, -1);
    }

    public void setTimeout(long ms) {
        Log.d(TAG, "setTimeout : " + ms);
        mDelay = ms;
    }

    private Runnable mCloseWifiRunnable = new Runnable() {
        public void run() {
            Log.d(TAG, "close wifi!");
            WifiManager wifi_manager = (WifiManager)mContext.getSystemService(Context.WIFI_SERVICE);
            wifi_manager.setWifiEnabled(false);
        }
    };

    private void trigger() {
        Log.d(TAG, "trigger");
        int wifiSleepPolicy = Settings.System.getInt(mContext.getContentResolver(),
            Settings.System.WIFI_SLEEP_POLICY, Settings.System.WIFI_SLEEP_POLICY_DEFAULT);
        Log.d(TAG, "at trigger, wifi sleep policy: " + wifiSleepPolicy);
        mHandler.removeCallbacks(mCloseWifiRunnable);
        if (wifiSleepPolicy != 2) {
            mHandler.post(mCloseWifiRunnable);
        }
    }

    private void startCheckWlan() {
        mTimer = new Timer();
        mTimerTask = new TimerTask() {
            public void run() {
                if (SysClassNet.isUp("wlan0")) try {
                    mNewTransmit =  Long.valueOf(SysClassNet.getTxBytes("wlan0"));
                    if (debug) Log.d(TAG, "============" +
                            "  mOldTransmit : " + mOldTransmit +
                            "  mNewTransmit : " + mNewTransmit +
                          "   mMiniTransmit : " + mMiniTransmit);
                    if (mOldTransmit != null &&
                            mNewTransmit.longValue() - mOldTransmit.longValue() < mMiniTransmit.longValue()) {
                        if (mTimerNum == 0) {
                            trigger();
                        }
                        mTimerNum--;
                        Log.d(TAG, "===== no transmit, num: " + mTimerNum);
                    } else {
                        Log.d(TAG, "===== reset timer");
                        int inactivityTimeout = Settings.System.getIntForUser(mContext.getContentResolver(),
                            Settings.System.WIFI_INACTIVITY_TIMEOUT, 300000, -2);
                        if (inactivityTimeout > 0) {
                            mTimerNum = inactivityTimeout / 60000;
                        } else {
                            stopCheckWlan();
                        }
                    }
                    mOldTransmit = mNewTransmit;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };
        mTimer.schedule(mTimerTask, 1000, 59000);
    }

    private void stopCheckWlan() {
        Log.d(TAG, "===== stop check wlan ");
        if (mTimerTask != null) {
            mTimerTask.cancel();
            mTimerTask = null;
        }
    }
}
