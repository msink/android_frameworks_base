/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.net.DhcpInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Handler;
import android.os.RemoteException;
import android.os.WorkSource;
import android.os.Messenger;
import android.net.EthernetDataTracker;
import com.android.internal.util.AsyncChannel;
import java.util.List;
import android.util.Log;

/**
 * This class provides the primary API for managing all aspects of Wi-Fi
 * connectivity. Get an instance of this class by calling
 * {@link android.content.Context#getSystemService(String) Context.getSystemService(Context.WIFI_SERVICE)}.

 * It deals with several categories of items:
 * <ul>
 * <li>The list of configured networks. The list can be viewed and updated,
 * and attributes of individual entries can be modified.</li>
 * <li>The currently active Wi-Fi network, if any. Connectivity can be
 * established or torn down, and dynamic information about the state of
 * the network can be queried.</li>
 * <li>Results of access point scans, containing enough information to
 * make decisions about what access point to connect to.</li>
 * <li>It defines the names of various Intent actions that are broadcast
 * upon any sort of change in Wi-Fi state.
 * </ul>
 * This is the API to use when performing Wi-Fi specific operations. To
 * perform operations that pertain to network connectivity at an abstract
 * level, use {@link android.net.ConnectivityManager}.
 */
public class EthernetManager {
	
    private static final String TAG = "EthernetManager";
    public static final boolean DEBUG = true;
    private static void LOG(String msg) {
        if ( DEBUG ) {
            Log.d(TAG, msg);
        }
    }
    
    IEthernetManager mService;

    public EthernetManager(IEthernetManager service, Handler handler) {
        mService = service;
    }
    
    public int getEthernetConnectState() {
        LOG("getEthernetState() : Entered.");
        try {
            return mService.getEthernetConnectState();
        } catch (RemoteException e) {
            return EthernetDataTracker.ETHER_STATE_DISCONNECTED;
        }
    }
	
   public int getEthernetIfaceState() {
	   LOG("getEthernetIfaceState() : Entered.");
	   try {
		   return mService.getEthernetIfaceState();
	   } catch (RemoteException e) {
		   return EthernetDataTracker.ETHER_IFACE_STATE_DOWN;
	   }
   }

/*
0: no carrier (RJ45 unplug)
1: carrier exist (RJ45 plugin)
*/
   public int getEthernetCarrierState() {
	   LOG("getEthernetCarrierState() : Entered.");
	   try {
		   return mService.getEthernetCarrierState();
	   } catch (RemoteException e) {
		   return 0;
	   }
   }

    public boolean setEthernetEnabled(boolean enabled) {
        try {
            return mService.setEthernetEnabled(enabled);
        } catch (RemoteException e) {
            return false;
        }
    }

    public String getEthernetIfaceName() {
        try {
            return mService.getEthernetIfaceName();
        } catch (RemoteException e) {
            Log.e(TAG, "get ethernet interface name failed");
            return "eth0";
        }
    }
}

