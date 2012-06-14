/*
* Copyright (C) 2012 Freescale Semiconductor, Inc. All Rights Reserved.
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

package android.view;
import android.util.Slog;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ServiceManager;
import android.os.RemoteException;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FileNotFoundException;
import android.view.IWindowManager;
import android.content.Context;
import android.util.AndroidException;

public class DisplayCommand {
    static final String TAG = "Display";
    public static final int OPERATE_CODE_ENABLE =  0x1000;
    public static final int OPERATE_CODE_DISABLE = 0x2000;
    public static final int OPERATE_CODE_CHANGE =  0x4000;

    public static final int OPERATE_CODE_CHANGE_RESOLUTION = 0x1;
    public static final int OPERATE_CODE_CHANGE_OVERSCAN = 0x2;
    public static final int OPERATE_CODE_CHANGE_MIRROR = 0x4;
    public static final int OPERATE_CODE_CHANGE_COLORDEPTH = 0x8;
    public static final int OPERATE_CODE_CHANGE_ROTATION = 0x10;
    public static final int OPERATE_CODE_CHANGE_KEEPRATE = 0x20;

    public static final int SETTING_MODE_FULL_SCREEN = 0x1000;
    public static final int SETTING_MODE_KEEP_PRIMARY_RATE = 0x2000;
    public static final int SETTING_MODE_KEEP_16_9_RATE = 0x4000;
    public static final int SETTING_MODE_KEEP_4_3_RATE = 0x8000;

    IBinder mSurfaceFlinger = null;
    IWindowManager mWindowManager;
    ConfigParam mCfgParam = new ConfigParam();

    public DisplayCommand() {
        IBinder surfaceFlinger = ServiceManager.getService("SurfaceFlinger");
        if (surfaceFlinger != null) {
            mSurfaceFlinger = surfaceFlinger;
        }
        mWindowManager = IWindowManager.Stub.asInterface(ServiceManager.checkService(
                Context.WINDOW_SERVICE));
    }
 
    public static class ConfigParam {
        public int displayId;
        public int operateCode; //operate code: enable, change or disable display.
        public int rotation;
        public int xOverScan;
        public int yOverScan;
        public int mirror;
        public int colorDepth;
        public int keepRate;
        public String mode;

        public ConfigParam() {
            displayId = -1;
            operateCode = 0;
            mode = null;
            rotation = 0;
            xOverScan = 0;
            yOverScan = 0;
            mirror = 0;
            colorDepth = 0;
            keepRate = 0;
        }

        public void writeToParcel(Parcel out) {
            out.writeInt(displayId);
            out.writeInt(operateCode);
            out.writeInt(rotation);
            out.writeInt(xOverScan);
            out.writeInt(yOverScan);
            out.writeInt(mirror);
            out.writeInt(colorDepth);
            out.writeInt(keepRate);
            out.writeString(mode);
        }
    }

    private int transferParam() {
        int ret = 0;

        try {
            Parcel reply = Parcel.obtain();
            Parcel data = Parcel.obtain();
            data.writeInterfaceToken("android.ui.ISurfaceComposer");
            mCfgParam.writeToParcel(data);
            //CONFIG_DISPLAY = IBinder::FIRST_CALL_TRANSACTION + 100
            mSurfaceFlinger.transact(IBinder.FIRST_CALL_TRANSACTION + 100,
                                    data, reply, 0);

            ret = reply.readInt();
            reply.recycle();
            data.recycle();
        } catch(RemoteException ex) {
            Slog.e(TAG, "DisplayCommand catch exception!");
        }

        return ret;
    }

    private int setMainDisplay(String mode) {
        int ret = 0;
        int width_startIndex =0;
        int width_endIndex   =0;
        int height_startIndex=0;
        int height_endIndex  =0;
        int width = 0;
        int height = 0;
        try {
            if(mWindowManager == null) {
                Slog.e(TAG, "mWindowManager invalidate!");
                return -1;
            }

            if (mode != null) {
                mWindowManager.setDisplayMode(mode);
            } else {
                Slog.e(TAG, "setMainDisplay invalidate:width=" + width + " height=" + height);
            }

        } catch(RemoteException ex) {
            Slog.e(TAG, "DisplayCommand setMainDisplay exception!");
        }

        return ret;
    }

    public int enable(int displayId, String mode, int rotation,
                          int xoverScan, int yoverScan, int mirror, int colorDepth, int keepRate) { 
        int ret = 0;

        if (mSurfaceFlinger == null) {
            Slog.e(TAG, "DisplayCommand: mSurfaceFlinger=null!");
            return -1;
        }

        mCfgParam.displayId = displayId;
        mCfgParam.operateCode = OPERATE_CODE_ENABLE | OPERATE_CODE_CHANGE_RESOLUTION | OPERATE_CODE_CHANGE_OVERSCAN |
                      OPERATE_CODE_CHANGE_MIRROR | OPERATE_CODE_CHANGE_COLORDEPTH | OPERATE_CODE_CHANGE_ROTATION;
        mCfgParam.mode = mode;
        mCfgParam.rotation = rotation;
        mCfgParam.xOverScan = xoverScan;
        mCfgParam.yOverScan = yoverScan;
        mCfgParam.mirror = mirror;
        mCfgParam.colorDepth = colorDepth;
        mCfgParam.keepRate = keepRate;

        ret = transferParam();

        return ret;
    }

    public int disable(int displayId) {
        int ret = 0;

        if (mSurfaceFlinger == null) {
            Slog.e(TAG, "DisplayCommand: mSurfaceFlinger=null!");
            return -1;
        }

        mCfgParam.displayId = displayId;
        mCfgParam.operateCode = OPERATE_CODE_DISABLE;

        ret = transferParam();
        return ret;
    }

    public int setResolution(int displayId, String mode) {
        int ret = 0;

        if (mSurfaceFlinger == null) {
            Slog.e(TAG, "DisplayCommand: mSurfaceFlinger=null!");
            return -1;
        }

        if(displayId == 0) {
            return setMainDisplay(mode);
        }

        mCfgParam.displayId = displayId;
        mCfgParam.operateCode = OPERATE_CODE_CHANGE | OPERATE_CODE_CHANGE_RESOLUTION;
        //mCfgParam.width = width;
        //mCfgParam.height = height;
        mCfgParam.mode = mode;
        ret = transferParam();
        return ret;
    }

    public int setOverScan(int displayId, int Xratio, int Yratio) {
        int ret = 0;

        if (mSurfaceFlinger == null) {
            Slog.e(TAG, "DisplayCommand: mSurfaceFlinger=null!");
            return -1;
        }

        mCfgParam.displayId = displayId;
        mCfgParam.operateCode = OPERATE_CODE_CHANGE | OPERATE_CODE_CHANGE_OVERSCAN;
        mCfgParam.xOverScan = Xratio;
        mCfgParam.yOverScan = Yratio;

        ret = transferParam();
        return ret;
    }

    public int setMirror(int displayId, int mirror) {
        int ret = 0;

        if (mSurfaceFlinger == null) {
            Slog.e(TAG, "DisplayCommand: mSurfaceFlinger=null!");
            return -1;
        }

        mCfgParam.displayId = displayId;
        mCfgParam.operateCode = OPERATE_CODE_CHANGE | OPERATE_CODE_CHANGE_MIRROR;
        mCfgParam.mirror = mirror;

        ret = transferParam();
        return ret;
    }

    public int setColorDepth(int displayId, int depth) {
        int ret = 0;

        if (mSurfaceFlinger == null) {
            Slog.e(TAG, "DisplayCommand: mSurfaceFlinger=null!");
            return -1;
        }

        mCfgParam.displayId = displayId;
        mCfgParam.operateCode = OPERATE_CODE_CHANGE | OPERATE_CODE_CHANGE_COLORDEPTH;
        mCfgParam.colorDepth = depth;

        ret = transferParam();
        return ret;
    }

    public int setRotation(int displayId, int rotation) {
        int ret = 0;

        if (mSurfaceFlinger == null) {
            Slog.e(TAG, "DisplayCommand: mSurfaceFlinger=null!");
            return -1;
        }

        mCfgParam.displayId = displayId;
        mCfgParam.operateCode = OPERATE_CODE_CHANGE | OPERATE_CODE_CHANGE_ROTATION;
        mCfgParam.rotation = rotation;

        ret = transferParam();
        return ret;
    }

    public int setKeepRate(int displayId, int keepRate) {
        int ret = 0;

        if (mSurfaceFlinger == null) {
            Slog.e(TAG, "DisplayCommand: mSurfaceFlinger=null!");
            return -1;
        }

        mCfgParam.displayId = displayId;
        mCfgParam.operateCode = OPERATE_CODE_CHANGE | OPERATE_CODE_CHANGE_KEEPRATE;
        mCfgParam.keepRate = keepRate;

        ret = transferParam();
        return ret;
    }

    public int broadcastEvent() {
        int ret = 0;

        //try {
        //    ;

        //} catch(RemoteException ex) {
        //    Slog.e(TAG, "DisplayCommand enable failed!");
        //}
        return ret;
    }

}


