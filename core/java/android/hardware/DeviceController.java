package android.hardware;

import android.content.Context;
import android.content.Intent;
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

    public static final String ACTION_OPEN_FRONT_LIGHT = "OPEN_FRONT_LIGHT";
    public static final String ACTION_CLOSE_FRONT_LIGHT = "CLOSE_FRONT_LIGHT";
    public static final String INTENT_FRONT_LIGHT_VALUE = "FRONT_LIGHT_VALUE";

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
            "/sys/class/backlight/rk28_bl/brightness";

    private static final int BRIGHTNESS_CLOSED = 0;
    private static final int BRIGHTNESS_OPENED = 1;

    private Context mContext;

    public DeviceController(Context context) {
        mContext = context;
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

    public static void setBrightnessState(Context context, int state) {
        try {
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.BRIGHTNESS_STATE, state);
        } catch (NumberFormatException e) {
            Log.e(TAG, "could not set brightness state", e);
        }
    }

    public static int getBrightnessState(Context context) {
        int state = BRIGHTNESS_CLOSED;
        try {
            state = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.BRIGHTNESS_STATE);
        } catch (Settings.SettingNotFoundException snfe) {
            Log.d(TAG, "brightness state get fail");
        }
        return state;
    }

    public boolean hasWifi() {
        boolean hasWifi = readHWInfoFile(mFile, mDev[0]);
        return hasWifi;
    }

    public boolean hasIR() {
        boolean hasIR = readHWInfoFile(mFile, mDev[1]);
        return hasIR;
    }

    public boolean hasFrontLight() {
        boolean hasFrontLight = readHWInfoFile(mFile, mDev[4]);
        return hasFrontLight;
    }

    public boolean hasAudio() {
        boolean hasAudio = readHWInfoFile(mFile, mDev[3]);
        return hasAudio;
    }

    public boolean hasTP() {
        boolean hasTP = readHWInfoFile(mFile, mDev[2]);
        return hasTP;
    }

    public static boolean has5WayButton() {
        return readHWInfoFile(mFile, mDev[5]);
    }

    public static boolean hasPageButton() {
        return readHWInfoFile(mFile, mDev[7]);
    }

    private static boolean readHWInfoFile(String path, String value) {
        String v = value.toUpperCase().trim();
        File f = null;
        Scanner s = null;
        try {
            f = new File(path);
            s = new Scanner(f);
            while (s.hasNextLine()) {
                if (v.equals(s.nextLine().toUpperCase().trim())) {
                    Log.d(TAG, v);
                    return true;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (s != null) {
                s.close();
            }
        }
        return false;
    }

    public int getFrontLightValue() {
        int value = readFrontLightFile();
        return value;
    }

    public static int getFrontLightValue(Context context) {
        int value = readFrontLightFile();
        return value;
    }

    public static void setFrontLightValue(Context context, int value) {
        writeFrontLightFile(value);
    }

    public static int getFrontLightConfigValue(Context context) {
        int res = -1;
        try {
            res = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS);
        } catch (Settings.SettingNotFoundException snfe) {
        }
        return res;
    }

    private static void sendOpenAndCloseFrontLightBroadcast(Context context,
            String action, int frontLightValue) {
        Intent intent = new Intent();
        intent.setAction(action);
        intent.putExtra(INTENT_FRONT_LIGHT_VALUE, frontLightValue);
        context.sendBroadcast(intent);
    }

    public static boolean setFrontLightConfigValue(Context context, int value) {
        return Settings.System.putInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, value);
    }

    public void setFrontLightValue(int value) {
        writeFrontLightFile(value);
    }

    public static boolean openFrontLight(Context context) {
        int value = getFrontLightConfigValue(context);
        if (value <= 0) {
            value = BRIGHTNESS_DEFAULT;
            setFrontLightConfigValue(context, BRIGHTNESS_DEFAULT);
        }
        setBrightnessState(context, BRIGHTNESS_OPENED);
        writeFrontLightFile(value);
        sendOpenAndCloseFrontLightBroadcast(context, ACTION_OPEN_FRONT_LIGHT, value);
        return true;
    }

    public boolean openFrontLight() {
        int value = getFrontLightConfigValue(mContext);
        if (value == 0) {
            value = BRIGHTNESS_DEFAULT;
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, value);
        }

        if (readFrontLightFile() == 0) {
            writeFrontLightFile(value);
            return true;
        }

        return false;
    }

    public boolean closeFrontLight()  {
        writeFrontLightFile(0);
        return true;
    }

    public static boolean closeFrontLight(Context context) {
        writeFrontLightFile(0);
        setBrightnessState(context, 0);
        sendOpenAndCloseFrontLightBroadcast(context, ACTION_CLOSE_FRONT_LIGHT, 0);
        return true;
    }

    private static int readFrontLightFile() {
        File f = null;
        Scanner s = null;
        int value = 0;
        try {
            f = new File(mFrontLightFile);
            s = new java.util.Scanner(f);
            if (s.hasNextInt()) {
                value = s.nextInt();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (s != null) {
                s.close();
            }
        }
        return value;
    }

    private static void writeFrontLightFile(int value) {
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
            if (fout != null) try {
                fout.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public boolean isFrontLightOn() {
        return (getFrontLightValue() > 0);
    }

    public void toggleFrontLight() {
        Log.d(TAG, "toggleFrontLight, is on? " + isFrontLightOn());
        Log.d(TAG, "toggleFrontLight, getFrontLightValue? " + getFrontLightValue());
        if (isFrontLightOn()) {
            closeFrontLight(mContext);
        } else {
            openFrontLight(mContext);
        }
    }

    public void wifiLock(String className) {
    }

    public void wifiLock(String className, long timeoutInMillis) {
    }

    public void wifiUnlock(String className) {
    }

    public Map<String,Long> getWifiLockMap() {
        return null;
    }

    public void wifiLockClear() {
    }

    public void setWifiLockTimeout(long ms) {
    }
}
