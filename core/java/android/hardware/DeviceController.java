package android.hardware;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Scanner;

public class DeviceController {
    private static final String TAG = "DeviceController";

    public static final int TOUCH_TYPE_UNKNOWN = 0;
    public static final int TOUCH_TYPE_IR = 1;
    public static final int TOUCH_TYPE_CAPACITIVE = 2;

    private static final String[] mDev = {
        "WIFI:1", "IR:1", "TP:1", "AUDIO:1", "LIGHT:1",
        "5WAY_BUTTON:1", "PAGE_BUTTON:1" };
    private static final String mFile = "/system/hwinfo";

    private Context mContext;

    public DeviceController(Context context) {
        mContext = context;
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
}
