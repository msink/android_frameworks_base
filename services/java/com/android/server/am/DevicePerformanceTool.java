package com.android.server.am;

import android.util.Config;
import android.util.Log;
import android.content.pm.PackageManager;
import android.os.SystemProperties;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class DevicePerformanceTool {
    private final static String TAG = "GPUPerformance";
    private final static String mDefaultProfile = "7,50,100,133,160,200,266,400";

    HashMap<Integer, Integer> mLevelsMap = new HashMap<Integer, Integer>();

    /*
    * load native service.
    */
    static {
        System.load("/system/lib/libperformance_runtime.so");
    }

    public DevicePerformanceTool() {
        // Hardware init
        gpuInit();

        initData();
    }

    public void initData() {

        // Get gpu frequency level.
        String levels = getGpuLevels();

        if (levels == null || levels.equals("null") || Integer.valueOf(replaceBlank(levels).split(",")[0]) <= 1) {
            levels = mDefaultProfile;
        }

        String[] gpuLevels = replaceBlank(levels).split(",");
        int num = Integer.valueOf(gpuLevels[0]);

        mLevelsMap.put(PackageManager.HARDWARE_ACC_MODE_LOW,
                        Integer.valueOf(gpuLevels[1]));
        mLevelsMap.put(PackageManager.HARDWARE_ACC_MODE_NORMAL,
                        Integer.valueOf(gpuLevels[num/2]));
        mLevelsMap.put(PackageManager.HARDWARE_ACC_MODE_HIGH,
                        Integer.valueOf(gpuLevels[num-1]));
        mLevelsMap.put(PackageManager.HARDWARE_ACC_MODE_MAX,
                        Integer.valueOf(gpuLevels[num]));
        mLevelsMap.put(PackageManager.HARDWARE_ACC_MODE_SAFE,
                        Integer.valueOf(gpuLevels[num]));

        // Set auto as default solution.
        mLevelsMap.put(PackageManager.HARDWARE_ACC_MODE_UNKNOWN, 0);

        int value = 0;
        if (mLevelsMap.get(Integer.valueOf(2)) != null) {
            value = mLevelsMap.get(Integer.valueOf(2)).intValue();
        }
        SystemProperties.set("sys.gmali.videofreq", String.valueOf(value));
    }

    /*
     * Native methods.
     */
    public int gpuSetFreq(int freq) {
        return setGpuFreq(freq);
    }

    public int gpuGetFreq() {
        return getGpuFreq();
    }

    public int gpuGetLoad() {
        return getGpuLoad();
    }

    public int setPerformanceMode(int mode) {
        int value = 0;
        if (mLevelsMap.get(mode) != null) {
            value = mLevelsMap.get(mode);
        }

        SystemProperties.set("sys.gmali.savedinstance", String.valueOf(value));
        return setGpuPerformanceMode(mode, value);
    }

    public static String replaceBlank(String str) {
        String dest = "";
        if (str!=null) {
            Pattern p = Pattern.compile("\\s*|\t|\r|\n");
            Matcher m = p.matcher(str);
            dest = m.replaceAll("");
        }
        return dest;
    }

    /*
    * declare all the native interface.
    */
    private static native int gpuInit();
    private static native int getGpuLoad();
    private static native int setGpuFreq(int freq);
    private static native int getGpuFreq();
    private static native int setGpuPerformanceMode(int mode, int value);
    private static native String getGpuLevels();
}
