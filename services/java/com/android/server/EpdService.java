package com.android.server;

import android.content.Context;
import android.hardware.IEpdService;
import android.os.SystemProperties;
import android.util.Log;

public final class EpdService extends IEpdService.Stub {
    private static final String TAG = "EpdService";
    private static final boolean LOCAL_LOGD = true;

    public static final String FULL = "1";
    public static final String A2 = "2";
    public static final String PART = "3";
    public static final String FULL_DITHER = "4";

    private String mModeLockOwner;
    private boolean mServiceReady;
    private Context mContext;

    private boolean mModeLocked = false;

    private final String mEpdPropName = "hw.epd.mode";
    private final String mGrayPropName = "hw.epd.gray";
    private final String mFullPaintPropName = "hw.epd.fullpaint";

    public EpdService(Context context) throws RuntimeException {
        mContext = context;
        Log.d(TAG, "Trying to initialize the device");

        SystemProperties.set(mEpdPropName, PART);
        SystemProperties.set(mGrayPropName, "0");
        SystemProperties.set(mFullPaintPropName, "0");

        mServiceReady = native_epd_init();

        if (!mServiceReady) {
            Log.e(TAG, "Failed to initialize the device");
            throw new RuntimeException();
        }

        Log.d(TAG, "Device initialized successfully");
    }

    public boolean updateModeLock(boolean acquired, String owner) {

        if (mModeLocked) {

            if (acquired) {
                if (!mModeLockOwner.equals(owner)) {
                    Log.e(TAG, owner + " wants to acquire the lock but " + mModeLockOwner + " still holds");
                } else {
                    Log.w(TAG, owner + " has already acquired the lock. No need to do it again. Maybe it forgets to release it?");
                }
                return false;
            }

            if (!acquired) {
                if (!mModeLockOwner.equals(owner)) {
                    Log.e(TAG, owner + " has no permission to release the lock. Only " + mModeLockOwner + " can release it");
                    return false;
                } else {
                    mModeLocked = false;
                    mModeLockOwner = null;
                    Log.d(TAG, owner + " released the mode lock");
                    return true;
                }
            }

        } else {

            if (acquired) {
                mModeLocked = true;
                mModeLockOwner = owner;
                Log.d(TAG, owner + " acquired the mode lock");
                return true;
            }

            if (!acquired) {
                Log.w(TAG, owner + " has never ever acquired the lock so there's no need to release");
                return false;
            }
        }

        return false;
    }

    public void switchWorkMode(String mode, String owner) {
        Log.d(TAG, owner + " requesting to switch mode to " + mode);

        if (mModeLocked && !mModeLockOwner.equals(owner)) {
            Log.w(TAG, "Mode has been locked down. Request from " + owner + " has been ignored");
        }

        if (!mode.equals(FULL)) {
            SystemProperties.set(mEpdPropName, mode);
        } else {
            SystemProperties.set(mFullPaintPropName, mode);
        }
    }

    public void repaintDisplay() {
        Log.d(TAG, "Requesting to repaint");

        native_epd_repaint_display();
    }

    public void resetDisplay() {
        Log.d(TAG, "Requesting to reset display");

        native_epd_reset_display();
    }

    public boolean isBusyPainting() {
        Log.d(TAG, "Requesting to check work status");

        return native_epd_is_busy_painting();
    }

    public void switchGrayType(String type) {
        Log.d(TAG, "requesting to switch gray to " + type);

        SystemProperties.set(mGrayPropName, type);
    }

    private static native boolean native_epd_init();

    private static native boolean native_epd_is_busy_painting();

    private static native void native_epd_repaint_display();

    private static native void native_epd_reset_display();
}
