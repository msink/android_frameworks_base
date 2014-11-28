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

package android.net;

import android.content.Context;
import android.content.ContentResolver;
import android.net.NetworkInfo.DetailedState;
import android.os.Handler;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import android.content.Intent;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.provider.Settings;

import android.net.RouteInfo;
import java.net.UnknownHostException;
import android.text.TextUtils;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.io.File;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * This class tracks the data connection associated with Ethernet
 * This is a singleton class and an instance will be created by
 * ConnectivityService.
 * @hide
 */
public class EthernetDataTracker implements NetworkStateTracker {
    private static final String NETWORKTYPE = "ETHERNET";
    private static final String TAG = "Ethernet";

    private AtomicBoolean mTeardownRequested = new AtomicBoolean(false);
    private AtomicBoolean mPrivateDnsRouteSet = new AtomicBoolean(false);
    private AtomicInteger mDefaultGatewayAddr = new AtomicInteger(0);
    private AtomicBoolean mDefaultRouteSet = new AtomicBoolean(false);

    private static boolean mLinkUp;
    private LinkProperties mLinkProperties;
    private LinkCapabilities mLinkCapabilities;
    private NetworkInfo mNetworkInfo;
    private InterfaceObserver mInterfaceObserver;
    private String mHwAddr;

    /* For sending events to connectivity service handler */
    private Handler mCsHandler;
    private Context mContext;

    private static EthernetDataTracker sInstance;
    private static String sIfaceMatch = "";
    private static String mIface = "";

    private INetworkManagementService mNMService;

    public static final String ETHERNET_STATE_CHANGED_ACTION = "android.net.ethernet.ETHERNET_STATE_CHANGED";
    public static final String EXTRA_ETHERNET_STATE = "ethernet_state";
    public static final String ETHERNET_IFACE_STATE_CHANGED_ACTION = "android.net.ethernet.ETHERNET_IFACE_STATE_CHANGED";
    public static final String EXTRA_ETHERNET_IFACE_STATE = "ethernet_iface_state";
    public int ethCurrentState = ETHER_STATE_DISCONNECTED;
    public int ethCurrentIfaceState = ETHER_IFACE_STATE_DOWN;
    public static final int ETHER_STATE_DISCONNECTED=0;
    public static final int ETHER_STATE_CONNECTING=1;
    public static final int ETHER_STATE_CONNECTED=2;
    public static final int ETHER_IFACE_STATE_DOWN = 0;
    public static final int ETHER_IFACE_STATE_UP = 1;
    private WakeLock mWakeLock;

    private String mIpAddr;
    private String mGateway;
    private String mNetmask;
    private String mDns1;
    private String mDns2;
    private boolean mUseStaticIp = false;
    private int mPrefixLength;

    private void sendEthStateChangedBroadcast(int curState) {
        ethCurrentState=curState;
        final Intent intent = new Intent(ETHERNET_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);  //  
        intent.putExtra(EXTRA_ETHERNET_STATE, curState);
        mContext.sendStickyBroadcast(intent);
    }   
    private void sendEthIfaceStateChangedBroadcast(int curState) {
        final Intent intent = new Intent(ETHERNET_IFACE_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);  //  
        intent.putExtra(EXTRA_ETHERNET_IFACE_STATE, curState);
        ethCurrentIfaceState = curState;
        mContext.sendStickyBroadcast(intent);
    }
    private void acquireWakeLock(Context context) {
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"USB Ether");
            mWakeLock.acquire();
            Log.d(TAG,"acquireWakeLock USB Ether");
        }
    }
    private void releaseWakeLock() {
        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
            Log.d(TAG,"releaseWakeLock USB Ether");
        }
    }

    private static class InterfaceObserver extends INetworkManagementEventObserver.Stub {
        private EthernetDataTracker mTracker;

        InterfaceObserver(EthernetDataTracker tracker) {
            super();
            mTracker = tracker;
        }

        public void interfaceStatusChanged(String iface, boolean up) {
            Log.d(TAG, "Interface status changed: " + iface + (up ? "up" : "down"));
        }

        public void interfaceLinkStateChanged(String iface, boolean up) {
            if (mIface.equals(iface) && mLinkUp != up) {
                Log.d(TAG, "Interface " + iface + " link " + (up ? "up" : "down"));
                mLinkUp = up;
                mTracker.mNetworkInfo.setIsAvailable(up);

                // use DHCP
                if (up) {
                    mTracker.reconnect();
                } else {
                    mTracker.disconnect();
                }
            }
        }

        public void interfaceAdded(String iface) {
            mTracker.interfaceAdded(iface);
        }

        public void interfaceRemoved(String iface) {
            mTracker.interfaceRemoved(iface);
        }

        public void limitReached(String limitName, String iface) {
            // Ignored.
        }

        public void interfaceClassDataActivityChanged(String label, boolean active) {
            // Ignored.
        }
    }

    private EthernetDataTracker() {
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_ETHERNET, 0, NETWORKTYPE, "");
        mLinkProperties = new LinkProperties();
        mLinkCapabilities = new LinkCapabilities();
    }

    private void interfaceUp(String iface) {
        mNetworkInfo.setIsAvailable(true);
        sendEthIfaceStateChangedBroadcast(ETHER_IFACE_STATE_UP);
        Settings.Secure.putInt(mContext.getContentResolver(),
                               Settings.Secure.ETHERNET_ON,
                               1);
    }
	
    private void interfaceDown(String iface) {
        mNetworkInfo.setIsAvailable(false);
        sendEthIfaceStateChangedBroadcast(ETHER_IFACE_STATE_DOWN);
        Settings.Secure.putInt(mContext.getContentResolver(),
                               Settings.Secure.ETHERNET_ON,
                               0);
        mLinkProperties.clear();
    }

    private void interfaceAdded(String iface) {
        if (!iface.matches(sIfaceMatch))
            return;

        Log.d(TAG, "Adding " + iface);
        acquireWakeLock(mContext);

        synchronized(this) {
            if(!mIface.isEmpty())
                return;
            mIface = iface;
        }

        // we don't get link status indications unless the iface is up - bring it up
        try {
            mNMService.setInterfaceUp(iface);
        } catch (Exception e) {
            Log.e(TAG, "Error upping interface " + iface + ": " + e);
        }

        mNetworkInfo.setIsAvailable(true);
        Message msg = mCsHandler.obtainMessage(EVENT_CONFIGURATION_CHANGED, mNetworkInfo);
        msg.sendToTarget();
    }

    public void disconnect() {

        NetworkUtils.stopDhcp(mIface);

        mLinkProperties.clear();
        mNetworkInfo.setIsAvailable(false);
        mNetworkInfo.setDetailedState(DetailedState.DISCONNECTED, null, mHwAddr);

        Message msg = mCsHandler.obtainMessage(EVENT_CONFIGURATION_CHANGED, mNetworkInfo);
        msg.sendToTarget();

        msg = mCsHandler.obtainMessage(EVENT_STATE_CHANGED, mNetworkInfo);
        msg.sendToTarget();

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);
        try {
            service.clearInterfaceAddresses(mIface);
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear addresses or disable ipv6" + e);
        }

        sendEthStateChangedBroadcast(ETHER_STATE_DISCONNECTED);
    }

    private void interfaceRemoved(String iface) {
        if (!iface.equals(mIface))
            return;

        Log.d(TAG, "Removing " + iface);
        disconnect();
        mIface = "";

        releaseWakeLock();
    }

    private LinkAddress makeLinkAddress() {
        mPrefixLength = 24;
        try {
            InetAddress mask = NetworkUtils.numericToInetAddress(mNetmask);
            mPrefixLength = NetworkUtils.netmaskIntToPrefixLength(NetworkUtils.inetAddressToInt(mask));
        } catch (Exception e) {
            Log.e(TAG, "netmask to prefixLength exception: " + e);
        }
		
        if (TextUtils.isEmpty(mIpAddr)) {
            Log.e(TAG, "makeLinkAddress with empty ipAddress");
            return null;
        }
        return new LinkAddress(NetworkUtils.numericToInetAddress(mIpAddr), mPrefixLength);
    }
		
    private LinkProperties makeLinkProperties() {
        LinkProperties p = new LinkProperties();
        p.addLinkAddress(makeLinkAddress());
        p.addRoute(new RouteInfo(NetworkUtils.numericToInetAddress(mGateway)));
        if (TextUtils.isEmpty(mDns1) == false) {
            p.addDns(NetworkUtils.numericToInetAddress(mDns1));
        } else {
            Log.d(TAG, "makeLinkProperties with empty dns1!");
        }
        if (TextUtils.isEmpty(mDns2) == false) {
            p.addDns(NetworkUtils.numericToInetAddress(mDns2));
        } else {
            Log.d(TAG, "makeLinkProperties with empty dns2!");
        }
        return p;
    }	

    private void runDhcp() {
        Thread dhcpThread = new Thread(new Runnable() {
            public void run() {
                DhcpInfoInternal dhcpInfoInternal = new DhcpInfoInternal();
                if (!NetworkUtils.runDhcp(mIface, dhcpInfoInternal)) {
                    Log.e(TAG, "DHCP request error:" + NetworkUtils.getDhcpError());
                    return;
                }
                mLinkProperties = dhcpInfoInternal.makeLinkProperties();
                mLinkProperties.setInterfaceName(mIface);

                mNetworkInfo.setDetailedState(DetailedState.CONNECTED, null, mHwAddr);
                Message msg = mCsHandler.obtainMessage(EVENT_STATE_CHANGED, mNetworkInfo);
                msg.sendToTarget();

                sendEthStateChangedBroadcast(ETHER_STATE_CONNECTED);
                acquireWakeLock(mContext);
            }
        });
        dhcpThread.start();
    }

    public static synchronized EthernetDataTracker getInstance() {
        if (sInstance == null) sInstance = new EthernetDataTracker();
        return sInstance;
    }

    public Object Clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    public void setTeardownRequested(boolean isRequested) {
        mTeardownRequested.set(isRequested);
    }

    public boolean isTeardownRequested() {
        return mTeardownRequested.get();
    }

    /**
     * Begin monitoring connectivity
     */
    public void startMonitoring(Context context, Handler target) {
        mContext = context;
        mCsHandler = target;

        // register for notifications from NetworkManagement Service
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNMService = INetworkManagementService.Stub.asInterface(b);

        mInterfaceObserver = new InterfaceObserver(this);

        // enable and try to connect to an ethernet interface that
        // already exists
        sIfaceMatch = context.getResources().getString(
            com.android.internal.R.string.config_ethernet_iface_regex);
        try {
            final String[] ifaces = mNMService.listInterfaces();
            for (String iface : ifaces) {
                if (iface.matches(sIfaceMatch)) {
                    mIface = iface;
                    mNMService.setInterfaceUp(iface);
                    InterfaceConfiguration config = mNMService.getInterfaceConfig(iface);
                    mLinkUp = config.hasFlag("up");
                    if (config != null && mHwAddr == null) {
                        mHwAddr = config.getHardwareAddress();
                        if (mHwAddr != null) {
                            mNetworkInfo.setExtraInfo(mHwAddr);
                        }
                    }

                    // if a DHCP client had previously been started for this interface, then stop it
                    NetworkUtils.stopDhcp(mIface);

                    reconnect();
                    break;
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Could not get list of interfaces " + e);
        }

        try {
            mNMService.registerObserver(mInterfaceObserver);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not register InterfaceObserver " + e);
        }
    }

    /**
     * Disable connectivity to a network
     * TODO: do away with return value after making MobileDataStateTracker async
     */
    public boolean teardown() {
        mTeardownRequested.set(true);
        NetworkUtils.stopDhcp(mIface);
        return true;
    }

    /**
     * Re-enable connectivity to a network after a {@link #teardown()}.
     */
    public boolean reconnect() {
        if (mLinkUp) {
            mTeardownRequested.set(false);
            runDhcp();
        }
        return mLinkUp;
    }

    @Override
    public void captivePortalCheckComplete() {
        // not implemented
    }

    /**
     * Turn the wireless radio off for a network.
     * @param turnOn {@code true} to turn the radio on, {@code false}
     */
    public boolean setRadio(boolean turnOn) {
        return true;
    }

    /**
     * @return true - If are we currently tethered with another device.
     */
    public synchronized boolean isAvailable() {
        return mNetworkInfo.isAvailable();
    }

    /**
     * Tells the underlying networking system that the caller wants to
     * begin using the named feature. The interpretation of {@code feature}
     * is completely up to each networking implementation.
     * @param feature the name of the feature to be used
     * @param callingPid the process ID of the process that is issuing this request
     * @param callingUid the user ID of the process that is issuing this request
     * @return an integer value representing the outcome of the request.
     * The interpretation of this value is specific to each networking
     * implementation+feature combination, except that the value {@code -1}
     * always indicates failure.
     * TODO: needs to go away
     */
    public int startUsingNetworkFeature(String feature, int callingPid, int callingUid) {
        return -1;
    }

    /**
     * Tells the underlying networking system that the caller is finished
     * using the named feature. The interpretation of {@code feature}
     * is completely up to each networking implementation.
     * @param feature the name of the feature that is no longer needed.
     * @param callingPid the process ID of the process that is issuing this request
     * @param callingUid the user ID of the process that is issuing this request
     * @return an integer value representing the outcome of the request.
     * The interpretation of this value is specific to each networking
     * implementation+feature combination, except that the value {@code -1}
     * always indicates failure.
     * TODO: needs to go away
     */
    public int stopUsingNetworkFeature(String feature, int callingPid, int callingUid) {
        return -1;
    }

    @Override
    public void setUserDataEnable(boolean enabled) {
        Log.w(TAG, "ignoring setUserDataEnable(" + enabled + ")");
    }

    @Override
    public void setPolicyDataEnable(boolean enabled) {
        Log.w(TAG, "ignoring setPolicyDataEnable(" + enabled + ")");
    }

    /**
     * Check if private DNS route is set for the network
     */
    public boolean isPrivateDnsRouteSet() {
        return mPrivateDnsRouteSet.get();
    }

    /**
     * Set a flag indicating private DNS route is set
     */
    public void privateDnsRouteSet(boolean enabled) {
        mPrivateDnsRouteSet.set(enabled);
    }

    /**
     * Fetch NetworkInfo for the network
     */
    public synchronized NetworkInfo getNetworkInfo() {
        return mNetworkInfo;
    }

    /**
     * Fetch LinkProperties for the network
     */
    public synchronized LinkProperties getLinkProperties() {
        return new LinkProperties(mLinkProperties);
    }

   /**
     * A capability is an Integer/String pair, the capabilities
     * are defined in the class LinkSocket#Key.
     *
     * @return a copy of this connections capabilities, may be empty but never null.
     */
    public LinkCapabilities getLinkCapabilities() {
        return new LinkCapabilities(mLinkCapabilities);
    }

    /**
     * Fetch default gateway address for the network
     */
    public int getDefaultGatewayAddr() {
        return mDefaultGatewayAddr.get();
    }

    /**
     * Check if default route is set
     */
    public boolean isDefaultRouteSet() {
        return mDefaultRouteSet.get();
    }

    /**
     * Set a flag indicating default route is set for the network
     */
    public void defaultRouteSet(boolean enabled) {
        mDefaultRouteSet.set(enabled);
    }

    /**
     * Return the system properties name associated with the tcp buffer sizes
     * for this network.
     */
    public String getTcpBufferSizesPropName() {
        return "net.tcp.buffersize.wifi";
    }

    public void setDependencyMet(boolean met) {
        // not supported on this network
    }

    public int enableEthIface() {
        Log.d(TAG, "enableEthIface");
        int ret = NetworkUtils.enableInterface(mIface);
        if (0 == ret) {
            interfaceUp(mIface);
        } else {
            interfaceDown(mIface);
        }
        return ret;
    }

    public int disableEthIface() {
        Log.d(TAG, "disableEthIface");
        teardown();
        int ret = NetworkUtils.disableInterface(mIface);
        interfaceDown(mIface);
        return ret;
    }

    public String getEthIfaceName() {
        return mIface;
    }

    private String ReadFromFile(File file) {
        if ((file != null) && file.exists()) {
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
        Log.d(TAG, "getEthernetIfaceState()");
        return 0;
    }

    /*
     * 0: no carrier (RJ45 unplug)
     * 1: carrier exist (RJ45 plugin)
     */
    public int getEthernetCarrierState() {
        int state = getEthernetIfaceState();
        if((mIface != null) && state == ETHER_IFACE_STATE_UP) {
            File file = new File("/sys/class/net/"+mIface+"/carrier");
            String carrier = ReadFromFile(file);
            Log.d(TAG,"carrier="+carrier);
            int carrier_int = Integer.parseInt(carrier);
            return carrier_int;
        } else {
            return 0;
        }
    }
}

