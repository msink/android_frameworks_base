package com.android.server;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IOnyxWifiLockManagerService;
import android.provider.Settings;
import android.util.Log;

import java.util.*;

class OnyxWifiLockManagerService extends IOnyxWifiLockManagerService.Stub {
    final private static String TAG = "OnyxWifiLockManagerService";
    private boolean debug = true;
    private Map<String,Integer> map = new HashMap();
    private Context mContext;
    private Handler mHandler;
    private long mDelay = -1;

    public OnyxWifiLockManagerService(Context context) {
        mContext = context;
        mHandler = new Handler();
    }

    public void lock(String className) {
        mHandler.removeCallbacks(mCloseWifiRunnable);
        if (map.get(className) == null) {
            Log.d(TAG, "wifi lock!!!!!");
            map.put(className, Integer.valueOf(0));
        } else {
            Log.e(TAG, "Duplicated locked, please check your code: " + className);
        }
        if (debug) {
            printMap(map);
        }
    }

    public void unLock(String className) {
        if (map.get(className) != null) {
            Log.d(TAG, "wifi unlock!!!!!");
            map.remove(className);
        } else {
            Log.e(TAG, "Not locked before: " + className);
        }
        if (debug) {
            printMap(map);
        }
        if (map.isEmpty()) {
            Log.d(TAG, "map is empty!");
            trigger();
        }
    }

    public void clear() {
        Log.d(TAG, "clear");
        map.clear();
        trigger();
    }

    public Map<String,Integer> getLockMap() {
        Log.d(TAG, "getLockMap");
        return map;
    }

    public void setTimeout(long ms) {
        Log.d(TAG, "setTimeout : " + ms);
        mDelay = ms;
    }

    private Runnable mCloseWifiRunnable = new Runnable() {
        public void run() {
            Log.d(TAG, "mCloseWifiRunnable");
            if (map.isEmpty()) {
                Log.d(TAG, "close wifi!");
                WifiManager wifi_manager = (WifiManager)mContext.getSystemService(Context.WIFI_SERVICE);
                wifi_manager.setWifiEnabled(false);
            }
        }
    };

    private void trigger() {
        Log.d(TAG, "trigger");
        long time = 0;
        if (mDelay > -1) {
            time = mDelay;
        } else {
            time = Settings.System.getInt(mContext.getContentResolver(), Settings.System.WIFI_LOCK_DELAY, 30000);
        }
        Log.d(TAG, "time out : " + time);
        mHandler.removeCallbacks(mCloseWifiRunnable);
        if (time > -1) {
            mHandler.postDelayed(mCloseWifiRunnable, time);
        }
    }

    private void printMap(Map map) {
        Set set = map.entrySet();
        for (Iterator iterator = set.iterator(); iterator.hasNext();) {
            Map.Entry mapentry = (Map.Entry)iterator.next();
            Log.d(TAG, "===== " + mapentry.getKey() + "/" + mapentry.getValue());
        }
    }
}
