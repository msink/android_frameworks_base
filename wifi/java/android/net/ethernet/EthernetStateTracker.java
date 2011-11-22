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

import android.R;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.NetworkStateTracker;
import android.net.NetworkUtils;
import android.net.NetworkInfo.DetailedState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.format.Formatter;
import android.text.TextUtils;
import android.util.*;
import com.rockchip.android.macro.rkMacro;

import java.lang.System;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * @hide
 */
public class EthernetStateTracker extends NetworkStateTracker {
    private static final String TAG="EthernetStateTracker";
    private static final String WAKELOCK_TAG = "EthernetStateTracker";
    private static void LOG(String msg) {
        Slog.d(TAG, msg);
    }

    private static final int EVENT_INTERFACE_CONFIGURATION_SUCCEEDED = 6;
    private static final int EVENT_INTERFACE_CONFIGURATION_FAILED = 7;
    public static final int COMMAND_START_ETHERNET = 1;
    public static final int COMMAND_ENABLE_ETHERNET = 2;
    public static final int COMMAND_DISABLE_ETHERNET = 3;

    private static String LS = System.getProperty("line.separator");

    private DhcpInfo mDhcpInfo;
    private DhcpHandler mDhcpTarget;
    private EthernetInfo mEthernetInfo;
    private String mInterfaceName;
    private MonitorHandler mMonitorHandler;
    private SettingsObserver mSettingsObserver;
    private static String[] sDnsPropNames;
    private static PowerManager.WakeLock sWakeLock;

    private int mEthernetState;
    private boolean mHaveIPAddress;
    private boolean mObtainingIPAddress;
    private boolean mUseStaticIp;

    boolean mHasCarrier;

    public EthernetStateTracker(Context context, Handler target) {
        super(context, target, ConnectivityManager.TYPE_ETHERNET, 0, "ETHERNET", "");
        mEthernetState = EthernetManager.ETHERNET_STATE_DISABLED;
        mUseStaticIp = false;
        mHaveIPAddress = false;
        mObtainingIPAddress = false;
        LOG("EthernetStateTracker() : Entered.");
      if (rkMacro.ENABLE_ETHERNET) {
        mInterfaceName = SystemProperties.get("ethernet.interface", "eth0");
        sDnsPropNames = new String[] {
            "dhcp." + mInterfaceName + ".dns1",
            "dhcp." + mInterfaceName + ".dns2"
        };
        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        sWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
        mEthernetInfo = new EthernetInfo();
        mDhcpInfo = new DhcpInfo();
        int state = getPersistedEthEnabled() ? EthernetManager.ETHERNET_STATE_INIT_ENABLED
                                             : EthernetManager.ETHERNET_STATE_DISABLED;
        LOG("EthernetStateTracker() : init state : " + convStateToStr(state));
        setEthStateAndSendBroadcast(state);
        mSettingsObserver = new SettingsObserver(new Handler());
        if (mMonitorHandler == null) {
            LOG("EthernetStateTracker() : start 'ehternet monitor thread'.");
            HandlerThread monitorThread = new HandlerThread("Ethernet Monitor Thread");
            monitorThread.start();
            mMonitorHandler = new MonitorHandler(monitorThread.getLooper(), this);
        }
      } else {
        Slog.i(TAG, "Device is configged NOT to support ethernet. Init a dummy instance.");
        mEthernetState = EthernetManager.ETHERNET_STATE_DISABLED;
      }
    }

    public void handleMessage(Message msg) {
        switch (msg.what) {
        case COMMAND_START_ETHERNET:
            LOG("handleMessage() : process 'COMMAND_START_ETHERNET' msg. mEthernetState : " + convStateToStr(mEthernetState));
            if (mEthernetState != EthernetManager.ETHERNET_STATE_DISABLED) {
                LOG("handleMessage() : start 'eth'.");
                startEth();
            }
            break;
        case COMMAND_ENABLE_ETHERNET:
            LOG("handleMessage() : process 'COMMAND_ENABLE_ETHERNET'. mEthernetState : " + convStateToStr(mEthernetState));
            setPersistedEthEnabled(true);
            startEth();
            break;
        case COMMAND_DISABLE_ETHERNET:
            LOG("handleMessage() : process 'COMMAND_DISABLE_ETHERNET'. mEthernetState : " + convStateToStr(mEthernetState));
            if (EthernetManager.ETHERNET_STATE_DISABLED != mEthernetState) {
                setPersistedEthEnabled(false);
                sWakeLock.acquire();
                LOG("handleMessage() : stop monitor callback chain.");
                mMonitorHandler.removeMessages(1);
                resetInterface();
                setDetailedState(NetworkInfo.DetailedState.DISCONNECTED);
                mNetworkInfo.setIsAvailable(false);
                sendNetworkStateChangeBroadcast();
                setEthStateAndSendBroadcast(EthernetManager.ETHERNET_STATE_DISABLED);
                sWakeLock.release();
            }
            break;
        case EVENT_INTERFACE_CONFIGURATION_SUCCEEDED:
            mHaveIPAddress = true;
            mObtainingIPAddress = false;
            mEthernetInfo.setIpAddress(mDhcpInfo.ipAddress);
            if (mNetworkInfo.getDetailedState() != NetworkInfo.DetailedState.CONNECTED) {
                setDetailedState(NetworkInfo.DetailedState.CONNECTED);
                mNetworkInfo.setIsAvailable(true);
                sendNetworkStateChangeBroadcast();
            } else {
                Message newMsg = mTarget.obtainMessage(4, mNetworkInfo);
                newMsg.sendToTarget();
            }
            sWakeLock.release();
            break;
        case EVENT_INTERFACE_CONFIGURATION_FAILED:
            mHaveIPAddress = false;
            mEthernetInfo.setIpAddress(0);
            mObtainingIPAddress = false;
            setDetailedState(NetworkInfo.DetailedState.FAILED);
            mNetworkInfo.setIsAvailable(false);
            sendNetworkStateChangeBroadcast();
            sWakeLock.release();
            break;
        }
    }

    public String[] getNameServers() {
        String[] dnsAddresses = new String[2];
        int index = 0;
        if (mDhcpInfo.dns1 != 0) {
            dnsAddresses[index] = Formatter.formatIpAddress(mDhcpInfo.dns1);
            LOG("getNameServers() : dnsAddresses[" + index + "] = " + dnsAddresses[index]);
            index = index + 1;
        }
        if (mDhcpInfo.dns2 != 0) {
            dnsAddresses[index] = Formatter.formatIpAddress(mDhcpInfo.dns2);
        }
        return dnsAddresses;
    }

    @Override
    public String getTcpBufferSizesPropName() {
        return "net.tcp.buffersize.ethernet";
    }

    @Override
    public void startMonitoring() {
    }

    @Override
    public boolean teardown() {
      if (rkMacro.ENABLE_ETHERNET) {
        DetailedState state = mNetworkInfo.getDetailedState();
        LOG("teardown() : Current detailed state : " + state);
        if (DetailedState.CONNECTED == state) {
            sendEmptyMessage(COMMAND_DISABLE_ETHERNET);
            return true;
        }
      } else {
        Slog.i(TAG, "teardown() : Device is configged NOT to support ethernet. Abort 'teardown' operation!");
      }
        return false;
    }

    @Override
    public boolean reconnect() {
        DetailedState state = mNetworkInfo.getDetailedState();
        LOG("reconnect() : Current detailed state : " + state);
      if (rkMacro.ENABLE_ETHERNET) {
        if (DetailedState.CONNECTED != state && DetailedState.CONNECTING != state) {
            sendEmptyMessage(COMMAND_ENABLE_ETHERNET);
            return true;
        }
      } else {
        Slog.i(TAG, "reconnect() : Device is configged NOT to support ethernet. Abort reconnecting!");
      }
        return false;
    }

    @Override
    public boolean setRadio(boolean turnOn) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean isAvailable() {
        //Only say available if we have interfaces and user did not disable us.
        return (mEthernetState == EthernetManager.ETHERNET_STATE_CARRIER_DETECTED);
    }

    @Override
    public int startUsingNetworkFeature(String feature, int callingPid,
            int callingUid) {
        // TODO Auto-generated method stub
        return -1;
    }

    @Override
    public int stopUsingNetworkFeature(String feature, int callingPid,
            int callingUid) {
        // TODO Auto-generated method stub
        return -1;
    }

    public void releaseWakeLock() {
    }

    public EthernetInfo requestConnectionInfo() {
        return mEthernetInfo;
    }

    public int getEthernetState() {
        return mEthernetState;
    }

    private class DhcpHandler extends Handler {
        private static final int COMMAND_START_ETHERNET_DHCP = 1;
        private boolean mCancelNotify;
        private Handler mTarget;

        public DhcpHandler(Looper looper, Handler target) {
            super(looper);
            mTarget = target;
        }

        public void handleMessage(Message msg) {
            LOG("DhcpHandler::handleMessage() : Entered : msg = " + msg);
            switch (msg.what) {
            case COMMAND_START_ETHERNET_DHCP:
                synchronized (this) {
                    mCancelNotify = false;
                }
                LOG("DhcpHandler::handleMessage() : DHCP request started");
                int event;
                if (NetworkUtils.runDhcp(mInterfaceName, mDhcpInfo)) {
                    event = EVENT_INTERFACE_CONFIGURATION_SUCCEEDED;
                    LOG("DhcpHandler::handleMessage() : DHCP request succeeded");
                } else {
                    event = EVENT_INTERFACE_CONFIGURATION_FAILED;
                    Slog.w(TAG, "DhcpHandler::handleMessage() : DHCP request failed : " + NetworkUtils.getDhcpError(mInterfaceName));
                }
                synchronized (this) {
                    if (!mCancelNotify) {
                        mTarget.sendEmptyMessage(event);
                    }
                }
                break;
            }
        }

        public synchronized void setCancelNotify(boolean cancelNotify) {
            mCancelNotify = cancelNotify;
        }
    }

    private class MonitorHandler extends Handler {
        final private static int COMMAND_MONITOR_ETHERNET = 1;
        private Handler mTarget;

        public MonitorHandler(Looper looper, Handler target) {
            super(looper);
            mTarget = target;
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
            case COMMAND_MONITOR_ETHERNET:
                boolean hasCarrier = false;
                synchronized (EthernetStateTracker.this) {
                    hasCarrier = EthernetNative.isCarrierDetected();
                }
                if (hasCarrier != mHasCarrier) {
                    if (!hasCarrier) {
                        LOG("MonitorHandler::handleMessage() : No carrier is detected, reset eth.");
                        setEthStateAndSendBroadcast(EthernetManager.ETHERNET_STATE_NO_CARRIER);
                        resetInterface();
                        synchronized (EthernetStateTracker.this) {
                            EthernetNative.turnUpInterface();
                        }
                        setDetailedState(NetworkInfo.DetailedState.DISCONNECTED);
                        mNetworkInfo.setIsAvailable(false);
                    } else {
                        setEthStateAndSendBroadcast(EthernetManager.ETHERNET_STATE_CARRIER_DETECTED);
                        LOG("MonitorHandler::handleMessage() : Carrier is detected, try to config eth again.");
                        mTarget.sendEmptyMessage(COMMAND_START_ETHERNET);
                    }
                    mHasCarrier = hasCarrier;
                }
                sendEmptyMessageDelayed(COMMAND_MONITOR_ETHERNET, 1000);
                break;
            }
        }
    }

    private void checkUseStaticIp() {
        mUseStaticIp = false;
        final ContentResolver cr = mContext.getContentResolver();
        try {
            if (Settings.System.getInt(cr, Settings.System.ETHERNET_USE_STATIC_IP) == 0) {
                LOG("checkUseStaticIp() : user set to use DHCP, about to Return.");
                return;
            }
        } catch (Settings.SettingNotFoundException e) {
            return;
        }

        try {
            String addr = Settings.System.getString(cr, Settings.System.ETHERNET_STATIC_IP);
            if (addr != null) {
                mDhcpInfo.ipAddress = stringToIpAddr(addr);
            } else {
                LOG("checkUseStaticIp() : No valid IP addr.");
                return;
            }
            addr = Settings.System.getString(cr, Settings.System.ETHERNET_STATIC_GATEWAY);
            if (addr != null) {
                mDhcpInfo.gateway = stringToIpAddr(addr);
            } else {
                LOG("checkUseStaticIp() : No valid gateway.");
                return;
            }
            addr = Settings.System.getString(cr, Settings.System.ETHERNET_STATIC_NETMASK);
            if (addr != null) {
                mDhcpInfo.netmask = stringToIpAddr(addr);
            } else {
                LOG("checkUseStaticIp() : No valid netmask.");
                return;
            }
            addr = Settings.System.getString(cr, Settings.System.ETHERNET_STATIC_DNS1);
            if (addr != null) {
                mDhcpInfo.dns1 = stringToIpAddr(addr);
            } else {
                LOG("checkUseStaticIp() : No valid dns1.");
                return;
            }
            addr = Settings.System.getString(cr, Settings.System.ETHERNET_STATIC_DNS2);
            if (addr != null) {
                mDhcpInfo.dns2 = stringToIpAddr(addr);
            } else {
                mDhcpInfo.dns2 = 0;
            }
        } catch (UnknownHostException e) {
            Slog.w(TAG, "checkUseStaticIp() : Get INVALID IP addr from \'Settings.System\' : e = " + e);
            return;
        }
        mUseStaticIp = true;
        LOG("checkUseStaticIp() : about to Exit : mUseStaticIp = " + mUseStaticIp);
    }

    private static int stringToIpAddr(String addrString) throws UnknownHostException {
        if (addrString == null || TextUtils.isEmpty(addrString))
            return 0;
        try {
            String[] parts = addrString.split("\\.");
            if (parts.length != 4) {
                throw new UnknownHostException(addrString);
            }
            int a = Integer.parseInt(parts[0])      ;
            int b = Integer.parseInt(parts[1]) <<  8;
            int c = Integer.parseInt(parts[2]) << 16;
            int d = Integer.parseInt(parts[3]) << 24;
            return a | b | c | d;
        } catch (NumberFormatException ex) {
            throw new UnknownHostException(addrString);
        }
    }

    private static String convStateToStr(int p1) {
        return EthernetManager.convStateToStr(p1);
    }

    public void resetInterface() {
        LOG("resetInterface() : Entered.");
        mHaveIPAddress = false;
        mObtainingIPAddress = false;
        mEthernetInfo.setIpAddress(0);
        NetworkUtils.resetConnections(mInterfaceName);
        if (mDhcpTarget != null) {
            mDhcpTarget.setCancelNotify(true);
            mDhcpTarget.removeMessages(1);
        }
        if (!NetworkUtils.stopDhcp(mInterfaceName)) {
            Slog.e("EthernetStateTracker", "Could not stop DHCP");
        }
        NetworkUtils.disableInterface(mInterfaceName);
    }

    private void configureInterface() {
        LOG("configureInterface() : Entered : mUseStaticIp = " + mUseStaticIp);
        if (!mUseStaticIp) {
            if (!mObtainingIPAddress) {
                mObtainingIPAddress = true;
                LOG("configureInterface() : snnd 'COMMAND_START_ETHERNET_DHCP' to 'mDhcpTarget'.");
                mDhcpTarget.sendEmptyMessage(COMMAND_START_ETHERNET);
            }
        } else {
            int event;
            if (NetworkUtils.configureInterface(mInterfaceName, mDhcpInfo)) {
                LOG("configureInterface() : Static IP configuration SUCCEEDED.");
                mHaveIPAddress = true;
                event = EVENT_INTERFACE_CONFIGURATION_SUCCEEDED;
            } else {
                Slog.w(TAG, "configureInterface() : Static IP configuration FAILED!");
                mHaveIPAddress = false;
                event = EVENT_INTERFACE_CONFIGURATION_FAILED;
            }
            sendEmptyMessage(event);
        }
    }

    private void sendNetworkStateChangeBroadcast() {
        Intent intent = new Intent("android.net.ethernet.STATE_CHANGE");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("networkInfo", mNetworkInfo);
        LOG("sendNetworkStateChangeBroadcast() : Current detailed status : " + mNetworkInfo.getDetailedState());
        mContext.sendStickyBroadcast(intent);
    }

    private void sendEthStateChangedBroadcast(int preState, int curState) {
        Intent intent = new Intent("android.net.ethernet.ETHERNET_STATE_CHANGED");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("ethernet_state", curState);
        intent.putExtra("previous_ethernet_state", preState);
        LOG("sendNetworkStateChangeBroadcast() : preState = " + convStateToStr(preState)
                                            + ", curState = " + convStateToStr(curState));
        mContext.sendStickyBroadcast(intent);
    }

    private boolean getPersistedEthEnabled() {
        ContentResolver cr = mContext.getContentResolver();
        try {
            return (Settings.Secure.getInt(cr, Settings.Secure.ETHERNET_ON) == 1);
        } catch (Settings.SettingNotFoundException e) {
            Settings.Secure.putInt(cr, Settings.Secure.ETHERNET_ON, 0);
            Slog.w(TAG, "getPersistedEthEnabled() : fail to get setting for 'ETHERNET_ON': e = " + e);
            return false;
        }
    }

    private void setPersistedEthEnabled(boolean enable) {
        LOG("setPersistedEthEnabled() : enable = " + enable);
        ContentResolver cr = mContext.getContentResolver();
        Settings.Secure.putInt(cr, Settings.Secure.ETHERNET_ON, enable ? 1 : 0);
    }

    private void setEthStateAndSendBroadcast(int newState) {
        int preState = mEthernetState;
        mEthernetState = newState;
        sendEthStateChangedBroadcast(preState, mEthernetState);
    }

    private synchronized void startEth() {
        sWakeLock.acquire();
        if (EthernetNative.turnUpInterface() != 0) {
            mEthernetState = EthernetManager.ETHERNET_STATE_DISABLED;
            android.util.Slog.e("EthernetStateTracker", "startEth() : fail to turn up ethernet interface!");
            sWakeLock.release();
            setEthStateAndSendBroadcast(7);
        } else {
            if (mMonitorHandler != null) {
                mMonitorHandler.removeMessages(1);
                mMonitorHandler.sendEmptyMessage(COMMAND_START_ETHERNET);
            }
            try {
                Thread.sleep(300);
            } catch(InterruptedException e) {
                LOG("startEth() : e = " + e);
            }

            mHasCarrier = EthernetNative.isCarrierDetected();
            LOG("startEth() : mHasCarrier = " + mHasCarrier);
            if (mHasCarrier) {
                mEthernetInfo.setMacAddress(EthernetNative.getMacAddress());
                LOG("startEth() : MAC addr of ethernet interface : " + mEthernetInfo.getMacAddress());
                mEthernetInfo.setLinkSpeed(EthernetNative.getLinkSpeed());
                LOG("startEth() : link speed of ethernet interface : " + mEthernetInfo.getLinkSpeed() + " Mb/s.");
                mEthernetInfo.setDuplexingMode(EthernetNative.getDuplexingMode());
                LOG("startEth() : Duplexing Mode ID of ethernet interface : " + mEthernetInfo.getDuplexingMode());
                setEthStateAndSendBroadcast(EthernetManager.ETHERNET_STATE_CARRIER_DETECTED);
                setDetailedState(NetworkInfo.DetailedState.CONNECTING);
                sendNetworkStateChangeBroadcast();
                checkUseStaticIp();
                if (mDhcpTarget == null) {
                    HandlerThread dhcpThread = new HandlerThread("Ethernet DHCP Handler Thread");
                    dhcpThread.start();
                    mDhcpTarget = new DhcpHandler(dhcpThread.getLooper(), this);
                }
                LOG("startEth() : about to config ethernet interface according to user setting.");
                configureInterface();
            } else {
                setEthStateAndSendBroadcast(EthernetManager.ETHERNET_STATE_NO_CARRIER);
                sWakeLock.release();
            }
        }
    }

    private class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
            ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver
                (Settings.System.getUriFor("ethernet_use_static_ip"), false, this);
        }

        public void onChange(boolean selfChange) {
            LOG("SettingsObserver::onChange() : Entered : selfChange = " + selfChange +
                                                       ", mUseStaticIp = " + mUseStaticIp);
            super.onChange(selfChange);
            boolean wasStaticIp = mUseStaticIp;
            int oIp, oGw, oMsk, oDns1, oDns2;
            oIp = oGw = oMsk = oDns1 = oDns2 = 0;
            if (wasStaticIp) {
                oIp = mDhcpInfo.ipAddress;
                oGw = mDhcpInfo.gateway;
                oMsk = mDhcpInfo.netmask;
                oDns1 = mDhcpInfo.dns1;
                oDns2 = mDhcpInfo.dns2;
            }
            checkUseStaticIp();
            if (EthernetManager.ETHERNET_STATE_DISABLED == mEthernetState) {
                LOG("SettingsObserver::onChange() : eth is diabled, to return");
                return;
            }

            synchronized (EthernetStateTracker.this) {
                if (!EthernetNative.isCarrierDetected()) {
                    LOG("SettingsObserver::onChange() : phiscis link is DOWN, about to return.");
                    return;
                }
            }

            LOG("SettingsObserver::onChange() : wasStaticIp = " + wasStaticIp
                                           + ", mUseStaticIp = " + mUseStaticIp);
            boolean changed = (wasStaticIp != mUseStaticIp) ||
                              (wasStaticIp && (oIp != mDhcpInfo.ipAddress ||
                                               oGw != mDhcpInfo.gateway ||
                                               oMsk != mDhcpInfo.netmask ||
                                               oDns1 != mDhcpInfo.dns1 ||
                                               oDns2 != mDhcpInfo.dns2));
            LOG("SettingsObserver::onChange() : changed = " + changed);
            if (changed) {
                sWakeLock.acquire();
                LOG("SettingsObserver::onChange() : to reset eth interface.");
                resetInterface();
                synchronized (EthernetStateTracker.this) {
                    EthernetNative.turnUpInterface();
                }
                configureInterface();
                if (mUseStaticIp) {
                    Message msg = mTarget.obtainMessage(EVENT_CONFIGURATION_CHANGED, mNetworkInfo);
                    msg.sendToTarget();
                }
            }
        }
    }
}
