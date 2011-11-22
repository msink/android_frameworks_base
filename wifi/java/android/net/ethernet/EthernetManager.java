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

package android.net.ethernet;

import android.os.RemoteException;
import android.os.Handler;
import android.util.Log;

public class EthernetManager {
    private static final String TAG = "EthernetManager";
    public static final boolean DEBUG = true;
    private static void LOG(String msg) {
        Log.d(TAG, msg);
    }

    public static final int ETHERNET_STATE_DISABLING = 0;
    public static final int ETHERNET_STATE_DISABLED = 1;
    public static final int ETHERNET_STATE_ENABLING = 2;
    public static final int ETHERNET_STATE_INIT_ENABLED = 3;
    public static final int ETHERNET_STATE_UNKNOWN = 4;
    public static final int ETHERNET_STATE_NO_CARRIER = 5;
    public static final int ETHERNET_STATE_CARRIER_DETECTED = 6;
    public static final int ETHERNET_STATE_DEV_FAIL = 7;

    public static final String EXTRA_ETHERNET_STATE = "ethernet_state";
    public static final String EXTRA_NETWORK_INFO = "networkInfo";
    public static final String EXTRA_PREVIOUS_ETHERNET_STATE = "previous_ethernet_state";
    public static final String NETWORK_STATE_CHANGED_ACTION = "android.net.ethernet.STATE_CHANGE";
    public static final String ETHERNET_STATE_CHANGED_ACTION = "android.net.ethernet.ETHERNET_STATE_CHANGED";

    Handler mHandler;
    IEthernetManager mService;
    public EthernetManager(IEthernetManager service, Handler handler) {
        mService = service;
        mHandler = handler;
    }

    public static String convStateToStr(int state) {
        switch (state) {
        case ETHERNET_STATE_DISABLING        : return "DISABLING";
        case ETHERNET_STATE_DISABLED         : return "DISABLED";
        case ETHERNET_STATE_ENABLING         : return "ENABLING";
        case ETHERNET_STATE_INIT_ENABLED     : return "INIT_ENABLED";
        default:
        case ETHERNET_STATE_UNKNOWN          : return "UNKNOWN";
        case ETHERNET_STATE_NO_CARRIER       : return "NO_CARRIER";
        case ETHERNET_STATE_CARRIER_DETECTED : return "CARRIER_DETECTED";
        case ETHERNET_STATE_DEV_FAIL         : return "DEV_FAIL";
        }
    }

    public EthernetInfo getConnectionInfo() {
        try {
            return mService.getConnectionInfo();
        } catch (RemoteException e) {
            return null;
        }
    }

    public int getEthernetState() {
        try {
            return mService.getEthernetEnabledState();
        } catch (RemoteException e) {
            return ETHERNET_STATE_UNKNOWN;
        }
    }

    public boolean isEthernetEnabled() {
        return (getEthernetState() != 0);
    }

    public boolean setEthernetEnabled(boolean enabled) {
        try {
           return mService.setEthernetEnabled(enabled);
        } catch (RemoteException e) {
            return false;
        }
    }
}
