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

package com.android.server;

import java.net.UnknownHostException;

import android.content.Context;
import android.net.ethernet.EthernetInfo;
import android.net.ethernet.EthernetStateTracker;
import android.net.ethernet.IEthernetManager;
import android.os.UEventObserver;
import android.util.Log;
import com.rockchip.android.macro.rkMacro;

public class EthernetService extends IEthernetManager.Stub {
    private static final String TAG = "EthernetService";
    private static final String WAKELOCK_TAG = "EthernetService";
    private static final boolean DEBUG = true;
    private static void LOG(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }

    private Context mContext;
    private final EthernetStateTracker mEthernetStateTracker;
    private UsbEthObserver mObserver;

    private class UsbEthObserver extends UEventObserver {
        final private static String USBETH_UEVENT_MATCH = "SUBSYSTEM=net";
        private Context mContext;

        public UsbEthObserver(Context context) {
            mContext = context;
            LOG("UsbEthObserver() : to start observing, to catch uevent with 'SUBSYSTEM=net'.");
            startObserving(USBETH_UEVENT_MATCH);
            init();
        }

        private final synchronized void init() {
        }

        public void onUEvent(UEvent event) {
            LOG("onUEvent() : get uevent : '" + event + "'.");
            String netInterface = event.get("INTERFACE");
            String action = event.get("ACTION");
            if (netInterface != null && netInterface.equals("eth0")) {
                if (action.equals("add")) {
                    LOG("onUEvent() : usb eth was plugged in, to 'ENABLE' eth0.");
                    setEthernetEnabled(true);
                } else if (action.equals("remove")) {
                    LOG("onUEvent() : usb eth was removed, to 'DISABLE' eth0.");
                    setEthernetEnabled(false);
                }
            }
        }
    }

    EthernetService(Context context, EthernetStateTracker Tracker) {
        LOG("EthernetService() : Entered.");
        mContext = context;
        mEthernetStateTracker = Tracker;
      if (rkMacro.ENABLE_ETHERNET) {
        LOG("EthernetService() : send COMMAND_CONFIG_ETHERNET to 'mEthernetStateTracker'.");
        mEthernetStateTracker.sendEmptyMessage(EthernetStateTracker.COMMAND_START_ETHERNET);
        LOG("EthernetService() : to create UsbEthObserver instance.");
        mObserver = new UsbEthObserver(mContext);
      } else {
        Log.i(TAG, "Device is configged NOT to support ethernet. Ethernet service is ending.");
      }
    }

    public int getEthernetEnabledState() {
        return mEthernetStateTracker.getEthernetState();
    }

    public boolean setEthernetEnabled(boolean enable) {
        if (enable) {
            mEthernetStateTracker.sendEmptyMessage(2);
        } else {
            mEthernetStateTracker.sendEmptyMessage(3);
        }
        return true;
    }

    public EthernetInfo getConnectionInfo() {
        return mEthernetStateTracker.requestConnectionInfo();
    }
}
