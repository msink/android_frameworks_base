/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.os;

import android.os.Environment;
import android.os.FileUtils;
import android.os.Power;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;

public class RecoverySystem {
    private static final String TAG = "RecoverySystem";

    private static final boolean DEBUG = true;
    private static void LOG(String p1) {
        if (DEBUG) {
            Log.d(TAG, p1);
        }
    }

    public static final String FLASH_ROOT =
        Environment.getFlashStorageDirectory().getAbsolutePath();

    public static final String SDCARD_ROOT =
        Environment.getExternalStorageDirectory().getAbsolutePath();

    public static final String CACHE_ROOT =
        Environment.getDownloadCacheDirectory().getAbsolutePath();

    private static File RECOVERY_DIR = new File("/cache/recovery");
    private static File COMMAND_FILE = new File(RECOVERY_DIR, "command");
    private static File LOG_FILE = new File(RECOVERY_DIR, "log");
    public static final String IMAGE_FILE_NAME = "update.zip";

    private static int LOG_FILE_MAX_LENGTH = 8 * 1024;

    public static void rebootAndUpdate(File update) throws IOException {
        String path = update.getCanonicalPath();
        if (path.startsWith("/cache/")) {
            path = "CACHE:"+ path.substring(7);
        } else if (path.startsWith("/data/")) {
            path = "DATA:" + path.substring(6);
        } else {
            throw new IllegalArgumentException("Must start with /cache or /data: " + path);
        }

        bootCommand("--update_package=" + path);
    }

    public static void rebootAndUpdate(String imagePath) throws IOException {
        LOG("rebootAndUpdate(String) : Image file path : " + imagePath + "; Boot command : " + composeUpdateCommand(imagePath) + "; Set root command and reboot!");
        bootCommand(composeUpdateCommand(imagePath));
    }

    public static void rebootAndWipe() throws IOException {
        bootCommand("--wipe_data");
    }

    /**
     * Reboot into the recovery system with the supplied argument.
     * @param arg to pass to the recovery utility.
     * @throws IOException if something goes wrong.
     */
    private static void bootCommand(String arg) throws IOException {
        LOG("Write /cache/recovery/command: " + arg);
        RECOVERY_DIR.mkdirs();  // In case we need it
        COMMAND_FILE.delete();  // In case it's not writable
        LOG_FILE.delete();

        FileWriter command = new FileWriter(COMMAND_FILE);
        try {
            command.write(arg);
            command.write("\n");
        } finally {
            command.close();
        }

        // Having written the command file, go ahead and reboot
        String reason = "recovery:" + arg;
        Power.reboot(reason);
        throw new IOException("Reboot failed (no permissions?)");
    }

    /**
     * Called after booting to process and remove recovery-related files.
     * @return the log file from recovery, or null if none was found.
     */
    public static String handleAftermath() {
        // Record the tail of the LOG_FILE
        String log = null;
        try {
            log = FileUtils.readTextFile(LOG_FILE, -LOG_FILE_MAX_LENGTH, "...\n");
        } catch (FileNotFoundException e) {
            Log.i(TAG, "No recovery log file");
        } catch (IOException e) {
            Log.e(TAG, "Error reading recovery log", e);
        }

        // Delete everything in RECOVERY_DIR except those beginning with LAST_PREFIX
        String[] names = RECOVERY_DIR.list();
        for (int i = 0; names != null && i < names.length; i++) {
            File f = new File(RECOVERY_DIR, names[i]);
            if (!f.delete()) {
                Log.e(TAG, "Can't delete: " + f);
            } else {
                Log.i(TAG, "Deleted: " + f);
            }
        }

        return log;
    }

    private static String composeUpdateCommand(String arg) {
        LOG("composeUpdateCommand() : 'imagePath' = " + arg);

        if (arg.equals(FLASH_ROOT + "/" + IMAGE_FILE_NAME)) {
            return new String("--update_package=uDisk:update.zip");
        }
        if (arg.equals(SDCARD_ROOT + "/" + IMAGE_FILE_NAME)) {
            return new String("--update_package=SDCARD:update.zip");
        }
        if (arg.equals(CACHE_ROOT + "/" + IMAGE_FILE_NAME)) {
            return new String("--update_package=CACHE:update.zip");
        }

        throw new IllegalArgumentException("Not valid image file path.");
    }
}
