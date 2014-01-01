package com.android.server;

import android.content.Context;
import android.net.ethernet.IEthernetManager;
import android.net.EthernetDataTracker;
import android.util.Log;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

public class EthernetService extends IEthernetManager.Stub {

    private static final String TAG = "EthernetService";
    private static final boolean DEBUG = true;

    private static void LOG(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }

    private Context mContext;

    private final EthernetDataTracker mEthernetDataTracker;

    EthernetService(Context context) {
        LOG("EthernetService() : Entered.");
        mContext = context;
        mEthernetDataTracker = EthernetDataTracker.getInstance();
    }

    public int getEthernetConnectState() {
        LOG("getEthernetEnabledState() : Entered.");
        return mEthernetDataTracker.ethCurrentState;
    }

    private String ReadFromFile(File file) {
        if (file != null && file.exists()) {
            try {
                FileInputStream fin= new FileInputStream(file);
                BufferedReader reader= new BufferedReader(new InputStreamReader(fin));
                String flag = reader.readLine();
                fin.close();
                return flag;
            }
            catch(IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public int getEthernetIfaceState() {
        LOG("getEthernetIfaceState()");
        String ifc = mEthernetDataTracker.getEthIfaceName();
        if (ifc == "")
            return mEthernetDataTracker.ETHER_IFACE_STATE_DOWN;
        File file = new File("/sys/class/net/"+ifc+"/flags");
        if (!file.exists())
            return mEthernetDataTracker.ETHER_IFACE_STATE_DOWN;
        String flags = ReadFromFile(file);
        LOG("flags="+flags);
        String flags_no_0x = flags.substring(2);
        int flags_int = Integer.parseInt(flags_no_0x, 16);
        if ((flags_int & 0x1)>0) {
            LOG("state=up");
            return mEthernetDataTracker.ETHER_IFACE_STATE_UP;
        } else {
            LOG("state=down");
            return mEthernetDataTracker.ETHER_IFACE_STATE_DOWN;
        }
    }

    public int getEthernetCarrierState() {
        LOG("getEthernetCarrierState()");
        int state = getEthernetIfaceState();
        String ifc = mEthernetDataTracker.getEthIfaceName();
        if (ifc != "" && state == mEthernetDataTracker.ETHER_IFACE_STATE_UP) {
            File file = new File("/sys/class/net/"+ifc+"/carrier");
            String carrier = ReadFromFile(file);
            LOG("carrier="+carrier);
            int carrier_int = Integer.parseInt(carrier);
            return carrier_int;
        } else {
            return 0;
        }
    }

    public boolean setEthernetEnabled(boolean enable) {
        LOG("setEthernetEnabled() : enable="+enable);
        if (enable) {
            mEthernetDataTracker.enableEthIface();
        } else {
            mEthernetDataTracker.disableEthIface();
        }
        return true;
    }

    public String getEthernetIfaceName() {
        return mEthernetDataTracker.getEthIfaceName();
    }
}
