package com.android.server.power;

import android.util.Slog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class MultiScreenSaversHelper {
    private static final String TAG = "MultiScreenSaversHelper";

    private static final String PATH = "/system/media/";
    private static final String TARGET_PATH = "/data/misc/";
    private static final String SCREEN_SAVER_PREFIX = "standby";
    private static final String SCREEN_SAVER_SUFFIX = ".png";
    private static final int FIRST_INDEX = 0;
    private static final String EXTERNAL_SCREEN_SAVERS_PATH = "/mnt/sdcard/screensavers/";

    private static String getAbsolutePath(String name) {
        File screenSaverFile = new File(EXTERNAL_SCREEN_SAVERS_PATH + name);
        if (screenSaverFile.exists()) {
            return EXTERNAL_SCREEN_SAVERS_PATH + name;
        }
        return PATH + name;
    }

    private static String getTargetAbsolutePath(String name) {
        return TARGET_PATH + name;
    }

    private static String getUsingRealName() {
        return SCREEN_SAVER_PREFIX + SCREEN_SAVER_SUFFIX;
    }

    private static String getIndexPictureName(int index) {
        return SCREEN_SAVER_PREFIX + index + SCREEN_SAVER_SUFFIX;
    }

    public static int getNextIndex(int currentIndex) {
        int next = ++currentIndex;
        String nextPath = getAbsolutePath(getIndexPictureName(next));
        File nextFile = new File(nextPath);
        if (nextFile.exists()) {
            Slog.d(TAG, "file exists, result index: " + next);
        } else {
            Slog.d(TAG, "file not exists, result index: 0");
            next = 0;
        }
        return next;
    }

    public static void init() {
        nextScreenSaver(-1);
    }

    private static void copyFile(String src, String dest) {
        File destFile = new File(dest);
        if (destFile.exists()) {
            destFile.delete();
        }
        FileInputStream inputStream = null;
        FileOutputStream outputStream = null;
        try {
            inputStream = new FileInputStream(src);
            outputStream = new FileOutputStream(destFile);
            byte[] buffer = new byte[4096];
            while (inputStream.read(buffer) != -1) {
                outputStream.write(buffer);
            }
        } catch (Exception e) {
            Slog.d(TAG, "failed to copy file, src: " + src + ", dest: " + dest);
            e.printStackTrace();
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static int nextScreenSaver(int currentIndex) {
        int next = getNextIndex(currentIndex);
        copyFile(getAbsolutePath(getIndexPictureName(next)),
                 getTargetAbsolutePath(getUsingRealName()));
        return next;
    }
}
