package android.hardware;

import android.content.Context;
import android.os.ServiceManager;
import android.os.RemoteException;
import android.util.Log;

public class EpdManager {
    private static final String TAG = "EpdManager";

    private static final boolean LOCAL_LOGD = true;

    public static final String FULL = "1";
    public static final String A2 = "2";
    public static final String GRAY16 = "0";
    public static final String GRAY16DITHER = "1";
    public static final String GRAY2 = "2";
    public static final String GRAY2DITHER = "3";
    public static final String PART = "3";

    private String mCallerPkgName;
    private Context mContext;
    private IEpdService mEpdService;

    public EpdManager(Context context) {
        mCallerPkgName = "Anonymous";
        mContext = context;
        if (mContext != null) {
            mCallerPkgName = mContext.getPackageName();
        }
        mEpdService = IEpdService.Stub.asInterface(ServiceManager.getService(Context.EPD_SERVICE));
        if (mEpdService == null) {
            Log.e(TAG, context + " failed to get EpdService");
        } else {
            Log.d(TAG, context + " initialized EpdManager");
        }
    }

    public boolean acquireModeLock() {
        boolean granted = false;
        try {
            granted = mEpdService.updateModeLock(true, mCallerPkgName);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in EpdManager.acquireModeLock : ", e);
        }
        return granted;
    }

    public boolean releaseModeLock() {
        boolean granted = false;
        try {
            granted = mEpdService.updateModeLock(false, mCallerPkgName);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in EpdManager.releaseModeLock : ", e);
        }
        return granted;
    }

    public void setMode(String mode) {
        if (mode.equals(FULL)) {
            Log.e(TAG, "we not use this to set full mode in develop branch.", new Throwable());
            return;
        }
        if (mEpdService == null) {
            Log.e(TAG, "EpdService is not available");
            return;
        }
        Log.d(TAG, mCallerPkgName + " requests new mode " + mode);
        try {
            mEpdService.switchWorkMode(mode, mCallerPkgName);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in EpdManager.setMode : ", e);
        }
    }

    public void repaintDisplay() {
        if (mEpdService == null) {
            Log.e(TAG, "EpdService is not available");
            return;
        }
        Log.d(TAG, mCallerPkgName + " requests repaint");
        try {
            mEpdService.repaintDisplay();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in EpdManager.repaintDisplay : ", e);
        }
    }

    public void resetDisplay() {
        if (mEpdService == null) {
            Log.e(TAG, "EpdService is not available");
            return;
        }
        try {
            mEpdService.resetDisplay();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in EpdManager.resetDisplay : ", e);
        }
    }

    public boolean isBusy() {
        if (mEpdService == null) {
            Log.e(TAG, "EpdService is not available");
            return false;
        }
        boolean result = false;
        try {
            result = mEpdService.isBusyPainting();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in EpdManager.isBusy : ", e);
        }
        return result;
    }

    public void setGrayType(String type) {
        if (mEpdService == null) {
            Log.e(TAG, "EpdService is not available");
            return;
        }
        Log.d(TAG, mCallerPkgName + " requests new type " + type);
        try {
            mEpdService.switchGrayType(type);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in EpdManager.setGrayType : ", e);
        }
    }
}
