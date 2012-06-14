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
/* Copyright 2012 Freescale Semiconductor, Inc. */

package android.os;

import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;

import java.util.HashMap;

/**
 * This class allows you to access the state of Second Display and communicate with Display devices.
 *
 * <p>You can obtain an instance of this class by calling
 * {@link android.content.Context#getSystemService(java.lang.String) Context.getSystemService()}.
 *
 * {@samplecode
 * DisplayManager manager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
 * }
 */
public class DisplayManager {
    private static final String TAG = "DisplayManager";

   /**
     * Broadcast Action:  A sticky broadcast for Display state change events when in device mode.
     *
     * This is a sticky broadcast for clients that includes second display connected/disconnected state,
     * <ul>
     * <li> {@link #DISPLAY_CONNECTED} boolean indicating whether second display is connected or disconnected.
     *
     * {@hide}
     */
    public static final String ACTION_DISPLAY_STATE =
            "android.os.action.DISPLAY_STATE";

   /**
     * Broadcast Action:  A broadcast for Display device attached event.
     *
     * This intent is sent when a Display device is attached to the Display bus when in host mode.
     * <ul>
     * for the attached device
     * </ul>
     */
    public static final String ACTION_DISPLAY_DEVICE_0_ATTACHED =
            "android.os.action.DISPLAY_DEVICE_0_ATTACHED";

    public static final String ACTION_DISPLAY_DEVICE_1_ATTACHED =
            "android.os.action.DISPLAY_DEVICE_1_ATTACHED";

   /**
     * Broadcast Action:  A broadcast for Display device attached event.
     *
     * This intent is sent when a Display device is attached to the Display bus when in host mode.
     * <ul>
     * for the attached device
     * </ul>
     */
    public static final String ACTION_DISPLAY_DEVICE_2_ATTACHED =
            "android.os.action.DISPLAY_DEVICE_2_ATTACHED";


   /**
     * Broadcast Action:  A broadcast for Display device attached event.
     *
     * This intent is sent when a Display device is attached to the Display bus when in host mode.
     * <ul>
     * for the attached device
     * </ul>
     */
    public static final String ACTION_DISPLAY_DEVICE_3_ATTACHED =
            "android.os.action.DISPLAY_DEVICE_3_ATTACHED";


   /**
     * Broadcast Action:  A broadcast for Display device attached event.
     *
     * This intent is sent when a Display device is attached to the Display bus when in host mode.
     * <ul>
     * for the attached device
     * </ul>
     */
    public static final String ACTION_DISPLAY_DEVICE_4_ATTACHED =
            "android.os.action.DISPLAY_DEVICE_4_ATTACHED";

    public static final String ACTION_DISPLAY_DEVICE_5_ATTACHED =
            "android.os.action.DISPLAY_DEVICE_5_ATTACHED";

    /**
     * Boolean extra indicating whether Display is connected or disconnected.
     * Used in extras for the {@link #ACTION_DISPLAY_STATE} broadcast.
     *
     * {@hide}
     */
    public static final String DISPLAY_CONNECTED = "connected";

    /**
     * Boolean extra indicating whether Display is configured.
     * Used in extras for the {@link #ACTION_DISPLAY_STATE} broadcast.
     *
     * {@hide}
     */
    public static final String DISPLAY_CONFIGURED = "configured";

    public static final String EXTRA_DISPLAY_STATE = "display_state";

    public static final int DISPLAY_STATE_DISABLING = 0 ;
    public static final int DISPLAY_STATE_DISABLED  = 1 ;

    public static final int DISPLAY_STATE_ENABLING  = 2 ;
    public static final int DISPLAY_STATE_ENABLED   = 3 ;

    public static final int DISPLAY_STATE_UNKNOWN   = 4 ;

    public static final String EXTRA_DISPLAY_DEVICE = "display_device";

    public static final String EXTRA_DISPLAY_CONNECT = "display_connect";


    /**
     * Name of extra added to the {@link android.app.PendingIntent}
     * containing a boolean value indicating whether the user granted permission or not.
     */
    public static final String EXTRA_PERMISSION_GRANTED = "permission";

    private final Context mContext;
    private final IDisplayManager mService;

    /**
     * {@hide}
     */
    public DisplayManager(Context context, IDisplayManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Returns a HashMap containing all display mode currently attached.
     * Display mode name is the key for the returned HashMap.
     * The result will be empty if no devices are attached, or if
     *
     * @return HashMap containing all connected display devices.
     */
    public String[] getDisplayModeList(int dispid) {
        try {
            return mService.getDisplayModeList(dispid);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getDisplayModeList", e);
            return null;
        }
    }

    /**
     * set the resolution of the second display;
     */
    public boolean setDisplayMode(int dispid, String mode) {
        try {
            return mService.setDisplayMode(dispid, mode);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in setDisplayMode", e);
            return false;
        }
    }

    public boolean setDisplayEnable(int dispid, boolean enable) {
        try {
            return mService.setDisplayEnable(dispid, enable);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in setDisplayEnable", e);
            return false;
        }
    }

    public boolean setDisplayMirror(int dispid, boolean enable) {
        try {
            return mService.setDisplayMirror(dispid, enable);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in setDisplayMirror", e);
            return false;
        }
    }
    public boolean setDisplayXOverScan(int dispid, int xOverscan){
        try {
            return mService.setDisplayXOverScan(dispid, xOverscan);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in setDisplayOverScan", e);
            return false;
        }
    }
    public boolean setDisplayYOverScan(int dispid, int yOverscan){
        try {
            return mService.setDisplayYOverScan(dispid, yOverscan);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in setDisplayOverScan", e);
            return false;
        }
    }
    public boolean setDisplayRotation(int dispid, boolean enable) {
        try {
            return mService.setDisplayRotation(dispid, enable);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in setDisplayRotation", e);
            return false;
        }
    }
    public boolean setDisplayColorDepth(int dispid, int colordepth) {
        try {
            return mService.setDisplayColorDepth(dispid, colordepth);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in setDisplayColorDepth", e);
            return false;
        }
    }
    public boolean setDisplayKeepRate(int dispid, int keepRate)  {
        try {
            return mService.setDisplayKeepRate(dispid, keepRate);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in setDisplayKeepRate", e);
            return false;
        }
    }


    /**
     * set the resolution of the second display;
     */
    public String getDisplayMode(int dispid) {
        try {
            return mService.getDisplayMode(dispid);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getDisplayMode", e);
            return null;
        }
    }

    public String getDisplayName(int dispid) {
        try {
            return mService.getDisplayName(dispid);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getDisplayName", e);
            return null;
        }
    }

    public boolean getDisplayEnable(int dispid) {
        try {
            return mService.getDisplayEnable(dispid);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getDisplayEnable", e);
            return false;
        }
    }

    public boolean getDisplayMirror(int dispid) {
        try {
            return mService.getDisplayMirror(dispid);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getDisplayMirror", e);
            return false;
        }
    }
    public int getDisplayXOverScan(int dispid){
        try {
            return mService.getDisplayXOverScan(dispid);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getDisplayXOverScan", e);
            return 0;
        }
    }
    public int getDisplayYOverScan(int dispid){
        try {
            return mService.getDisplayYOverScan(dispid);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getDisplayYOverScan", e);
            return 0;
        }
    }
    public boolean getDisplayRotation(int dispid) {
        try {
            return mService.getDisplayRotation(dispid);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getDisplayRotation", e);
            return false;
        }
    }
    public int getDisplayColorDepth(int dispid) {
        try {
            return mService.getDisplayColorDepth(dispid);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getDisplayColorDepth", e);
            return 16;
        }
    }
    public int getDisplayKeepRate(int dispid) {
        try {
            return mService.getDisplayKeepRate(dispid);
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getDisplayKeepRate", e);
            return 0;
        }
    }

    public boolean rebootSystem() {
        try {
            return mService.rebootSystem();
        }catch (RemoteException e) {
            Log.e(TAG, "RemoteException in rebootSystem", e);
            return false;
        }
    }

}
