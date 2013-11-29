package android.hardware;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.IOnyxWifiLockManagerService;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Scanner;

public class DeviceController {
    private static final String TAG = "DeviceController";

    public static final int BRIGHTNESS_DEFAULT = 20;
    public static final int BRIGHTNESS_MAXIMUM = 255;
    public static final int BRIGHTNESS_MINIMUM = 0;

    public static final int TOUCH_TYPE_UNKNOWN = 0;
    public static final int TOUCH_TYPE_IR = 1;
    public static final int TOUCH_TYPE_CAPACITIVE = 2;

    private static final int FRONT_LIGHT_OFF = 0;
    private static final String[] mDev = {
        "WIFI:1", "IR:1", "TP:1", "AUDIO:1", "LIGHT:1",
        "5WAY_BUTTON:1", "PAGE_BUTTON:1" };
    private static final String mFile = "/system/hwinfo";
    private static final String mFrontLightFile =
        "/sys/devices/platform/rk29_backlight/backlight/rk28_bl/brightness";

    private Context mContext;

    private IOnyxWifiLockManagerService mOnyxWifiLockManagerService;

    public DeviceController(Context context) {
        mContext = context;
        mOnyxWifiLockManagerService = IOnyxWifiLockManagerService.Stub
            .asInterface(ServiceManager.getService(Context.ONYX_WIFI_LOCK_MANAGER_SERVICE));
    }

    public Map<String,Integer> getWifiLockMap() {
        Map<String,Integer> map = null;
        try {
            map = mOnyxWifiLockManagerService.getLockMap();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return map;
    }

    public void setWifiLockTimeout(long ms) {
        try {
            mOnyxWifiLockManagerService.setTimeout(ms);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void wifiLockClear() {
        try {
            mOnyxWifiLockManagerService.clear();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void wifiLock(String className) {
        try {
            mOnyxWifiLockManagerService.lock(className);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void wifiUnlock(String className) {
        try {
            mOnyxWifiLockManagerService.unLock(className);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private boolean readHWInfoFile(String path, String value) {
        String v = value.toUpperCase().trim();
        try {
            File f = new File(path);
            Scanner s = new Scanner(f);
            while (s.hasNextLine()) {
                if (v.equals(s.nextLine().toUpperCase().trim())) {
                    Log.d(TAG, v);
                    return true;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean hasWifi() {
        boolean hasWifi = readHWInfoFile("/system/hwinfo", mDev[0]);
        return hasWifi;
    }

    public boolean hasIR() {
        boolean hasIR = readHWInfoFile("/system/hwinfo", mDev[1]);
        return hasIR;
    }

    public boolean hasTP() {
        boolean hasTP = readHWInfoFile("/system/hwinfo", mDev[2]);
        return hasTP;
    }

    public boolean hasAudio() {
        boolean hasAudio = readHWInfoFile("/system/hwinfo", mDev[3]);
        return hasAudio;
    }

    public boolean hasFrontLight() {
        boolean hasFrontLight = readHWInfoFile("/system/hwinfo", mDev[4]);
        return hasFrontLight;
    }

    public boolean has5WayButton() {
        return readHWInfoFile("/system/hwinfo", mDev[5]);
    }

    public boolean hasPageButton() {
        return readHWInfoFile("/system/hwinfo", mDev[6]);
    }

    public boolean isTouchable() {
        return hasIR() || hasTP();
    }

    public int getTouchType() {
        if (hasIR())
            return TOUCH_TYPE_IR;
        if (hasTP())
            return TOUCH_TYPE_CAPACITIVE;
        return TOUCH_TYPE_UNKNOWN;
    }

    private int getFrontLightValueFromProvider() {
        int res = 0;
        int light_value;
        try {
            light_value = Settings.System.getInt(mContext.getContentResolver(),
                                                 "screen_brightness");
        } catch (Settings.SettingNotFoundException snfe) {
            light_value = BRIGHTNESS_DEFAULT;
        }
        res = light_value;
        return res;
    }

    public boolean openFrontLight() {
        int value = getFrontLightValueFromProvider();
        if (value == 0) {
            value = BRIGHTNESS_DEFAULT;
            Settings.System.putInt(mContext.getContentResolver(),
                                   "screen_brightness", value);
        }

        if (readFrontLightFile() == 0) {
            writeFrontLightFile(value);
            return true;
        }

        return false;
    }

    private int readFrontLightFile() {
        int value = 0;
        try {
            File f = new File(mFrontLightFile);
            Scanner s = new java.util.Scanner(f);
            if (s.hasNextInt()) {
                value = s.nextInt();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return value;
    }

    private void writeFrontLightFile(int value) {
        Log.d(TAG, "Set brightness: " + value);
        String message = Integer.toString(value);
        FileOutputStream fout = null;
        try {
            fout = new FileOutputStream(mFrontLightFile);
            byte[] bytes = message.getBytes();
            fout.write(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (fout != null) fout.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public boolean closeFrontLight()  {
        writeFrontLightFile(0);
        return true;
    }

    public int getFrontLightValue() {
        return readFrontLightFile();
    }

    public void setFrontLightValue(int value) {
        writeFrontLightFile(value);
    }
}
