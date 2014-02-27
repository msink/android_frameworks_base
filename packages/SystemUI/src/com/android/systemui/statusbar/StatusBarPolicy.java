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

package com.android.systemui.statusbar.policy;

import android.app.StatusBarManager;
import android.app.AlertDialog;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothPbap;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.text.format.DateFormat;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.util.Slog;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.cdma.EriInfo;
import com.android.internal.telephony.cdma.TtyIntent;
import com.android.server.am.BatteryStatsService;

import com.android.systemui.R;

/**
 * This class contains all of the policy about which icons are installed in the status
 * bar at boot time.  It goes through the normal API for icons, even though it probably
 * strictly doesn't need to.
 */
public class StatusBarPolicy {
    private static final String TAG = "StatusBarPolicy";

    // message codes for the handler
    private static final int EVENT_BATTERY_CLOSE = 4;

    private static final int AM_PM_STYLE_NORMAL  = 0;
    private static final int AM_PM_STYLE_SMALL   = 1;
    private static final int AM_PM_STYLE_GONE    = 2;

    private static final int AM_PM_STYLE = AM_PM_STYLE_GONE;

    private static final int INET_CONDITION_THRESHOLD = 50;

    private final Context mContext;
    private final StatusBarManager mService;
    private final Handler mHandler = new StatusBarHandler();
    private final IBatteryStats mBatteryStats;

    // storage
    private StorageManager mStorageManager;

    // battery
    private boolean mBatteryFirst = true;
    private boolean mBatteryPlugged;
    private int mBatteryLevel;
    private AlertDialog mLowBatteryDialog;
    private TextView mBatterySubTitleTextView;
    private TextView mBatteryLevelTextView;
    private View mBatteryView;
    private int mBatteryViewSequence;
    private static final boolean SHOW_LOW_BATTERY_WARNING = true;
    private static final boolean SHOW_BATTERY_WARNINGS_IN_CALL = true;

    // ringer volume
    private boolean mVolumeVisible;

    // bluetooth device status
    private int mBluetoothHeadsetState;
    private boolean mBluetoothA2dpConnected;
    private int mBluetoothPbapState;
    private boolean mBluetoothEnabled;

    // wifi
    public static final int[][] sWifiSignalImages = {
            { R.drawable.stat_sys_wifi_signal_1,
              R.drawable.stat_sys_wifi_signal_2,
              R.drawable.stat_sys_wifi_signal_3,
              R.drawable.stat_sys_wifi_signal_4 },
            { R.drawable.stat_sys_wifi_signal_1,
              R.drawable.stat_sys_wifi_signal_2,
              R.drawable.stat_sys_wifi_signal_3,
              R.drawable.stat_sys_wifi_signal_4 }
        };
    private static final int sWifiTemporarilyNotConnectedImage =
            R.drawable.stat_sys_wifi_signal_0;

    private int mLastWifiSignalLevel = -1;
    private boolean mIsWifiConnected = false;

    // state of inet connection - 0 not connected, 100 connected
    private int mInetCondition = 0;

    // sync state
    // If sync is active the SyncActive icon is displayed. If sync is not active but
    // sync is failing the SyncFailing icon is displayed. Otherwise neither are displayed.

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                updateBattery(intent);
                if (Build.PRODUCT.equals("Imcosys")) {
                    updateLowBatteryString(intent);
                }
            }
            else if (action.equals(Intent.ACTION_ALARM_CHANGED)) {
                updateAlarm(intent);
            }
            else if (action.equals(Intent.ACTION_SYNC_STATE_CHANGED)) {
                updateSyncState(intent);
            }
            else if (action.equals(Intent.ACTION_BATTERY_LOW)) {
                onBatteryLow(intent);
            }
            else if (action.equals(Intent.ACTION_BATTERY_OKAY)
                    || action.equals(Intent.ACTION_POWER_CONNECTED)) {
                onBatteryOkay(intent);
            }
            else if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED) ||
                    action.equals(BluetoothHeadset.ACTION_STATE_CHANGED) ||
                    action.equals(BluetoothA2dp.ACTION_SINK_STATE_CHANGED) ||
                    action.equals(BluetoothPbap.PBAP_STATE_CHANGED_ACTION)) {
                updateBluetooth(intent);
            }
            else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION) ||
                    action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION) ||
                    action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
                updateWifi(intent);
            }
            else if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION) ||
                    action.equals(AudioManager.VIBRATE_SETTING_CHANGED_ACTION)) {
                updateVolume();
            }
            else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION) ||
                     action.equals(ConnectivityManager.INET_CONDITION_ACTION)) {
                // TODO - stop using other means to get wifi/mobile info
                updateConnectivity(intent);
            }
        }
    };

    private void updateLowBatteryString(Intent mIntent) {
        int status = mIntent.getIntExtra("status", 1);
        int plugType = mIntent.getIntExtra("plugged", 0);
        if (status == 2) {
            if (mBatteryLevelTextView != null) {
                mBatterySubTitleTextView.setText(R.string.battery_low_charging_subtitle_imcosys);
                mLowBatteryDialog.setTitle(R.string.battery_low_charging_title_imcosys);
            }
        } else if (status == 4) {
            if (mBatteryLevelTextView != null) {
                mBatterySubTitleTextView.setText(R.string.battery_low_subtitle_imcosys);
                mLowBatteryDialog.setTitle(R.string.battery_low_title_imcosys);
            }
        }
        return;
    }

    public StatusBarPolicy(Context context) {
        mContext = context;
        mService = (StatusBarManager)context.getSystemService(Context.STATUS_BAR_SERVICE);
        mBatteryStats = BatteryStatsService.getService();

        // storage
        mStorageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        mStorageManager.registerListener(
                new com.android.systemui.usb.StorageNotification(context));

        // battery
        mService.setIcon("battery", com.android.internal.R.drawable.stat_sys_battery_unknown, 0);

        // wifi
        mService.setIcon("wifi", sWifiSignalImages[0][0], 0);
        mService.setIconVisibility("wifi", false);
        // wifi will get updated by the sticky intents

        // bluetooth status
        mService.setIcon("bluetooth", R.drawable.stat_sys_data_bluetooth, 0);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            mBluetoothEnabled = adapter.isEnabled();
        } else {
            mBluetoothEnabled = false;
        }
        mBluetoothA2dpConnected = false;
        mBluetoothHeadsetState = BluetoothHeadset.STATE_DISCONNECTED;
        mBluetoothPbapState = BluetoothPbap.STATE_DISCONNECTED;
        mService.setIconVisibility("bluetooth", mBluetoothEnabled);

        // Alarm clock
        mService.setIcon("alarm_clock", R.drawable.stat_notify_alarm, 0);
        mService.setIconVisibility("alarm_clock", false);

        // Sync state
        mService.setIcon("sync_active", com.android.internal.R.drawable.stat_notify_sync_anim0, 0);
        mService.setIcon("sync_failing", com.android.internal.R.drawable.stat_notify_sync_error, 0);
        mService.setIconVisibility("sync_active", false);
        mService.setIconVisibility("sync_failing", false);

        // volume
        mService.setIcon("volume", R.drawable.stat_sys_ringer_silent, 0);
        mService.setIconVisibility("volume", false);
        updateVolume();

        IntentFilter filter = new IntentFilter();

        // Register for Intent broadcasts for...
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_LOW);
        filter.addAction(Intent.ACTION_BATTERY_OKAY);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_ALARM_CHANGED);
        filter.addAction(Intent.ACTION_SYNC_STATE_CHANGED);
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_SINK_STATE_CHANGED);
        filter.addAction(BluetoothPbap.PBAP_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(ConnectivityManager.INET_CONDITION_ACTION);
        mContext.registerReceiver(mIntentReceiver, filter, null, mHandler);
    }

    private final void updateAlarm(Intent intent) {
        boolean alarmSet = intent.getBooleanExtra("alarmSet", false);
        mService.setIconVisibility("alarm_clock", alarmSet);
    }

    private final void updateSyncState(Intent intent) {
        boolean isActive = intent.getBooleanExtra("active", false);
        boolean isFailing = intent.getBooleanExtra("failing", false);
        mService.setIconVisibility("sync_active", isActive);
        // Don't display sync failing icon: BUG 1297963 Set sync error timeout to "never"
        //mService.setIconVisibility("sync_failing", isFailing && !isActive);
    }

    private final void updateBattery(Intent intent) {
        final int id = intent.getIntExtra("icon-small", 0);
        int level = intent.getIntExtra("level", 0);
        mService.setIcon("battery", id, level);

        boolean plugged = intent.getIntExtra("plugged", 0) != 0;
        level = intent.getIntExtra("level", -1);
        if (false) {
            Slog.d(TAG, "updateBattery level=" + level
                    + " plugged=" + plugged
                    + " mBatteryPlugged=" + mBatteryPlugged
                    + " mBatteryLevel=" + mBatteryLevel
                    + " mBatteryFirst=" + mBatteryFirst);
        }

        boolean oldPlugged = mBatteryPlugged;

        mBatteryPlugged = plugged;
        mBatteryLevel = level;

        if (mBatteryFirst) {
            mBatteryFirst = false;
        }
        /*
         * No longer showing the battery view because it draws attention away
         * from the USB storage notification. We could still show it when
         * connected to a brick, but that could lead to the user into thinking
         * the device does not charge when plugged into USB (since he/she would
         * not see the same battery screen on USB as he sees on brick).
         */
        if (false) {
            Slog.d(TAG, "plugged=" + plugged + " oldPlugged=" + oldPlugged + " level=" + level);
        }
    }

    private void onBatteryLow(Intent intent) {
        if (SHOW_LOW_BATTERY_WARNING) {
            if (!SHOW_BATTERY_WARNINGS_IN_CALL)
                return;
            showLowBatteryWarning();
        }
    }

    private void onBatteryOkay(Intent intent) {
        if (mLowBatteryDialog != null
                && SHOW_LOW_BATTERY_WARNING) {
            mLowBatteryDialog.dismiss();
        }
    }

    private void setBatteryLevel(View parent, int id, int height, int background, int level) {
        ImageView v = (ImageView)parent.findViewById(id);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)v.getLayoutParams();
        lp.weight = height;
        if (background != 0) {
            v.setBackgroundResource(background);
            Drawable bkg = v.getBackground();
            bkg.setLevel(level);
        }
    }

    private void showLowBatteryWarning() {
        closeLastBatteryView();

        int mLowBatterySecondWarningLevel = mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_lowBatterySecondWarningLevelImcosy);
        // Show exact battery level.
        CharSequence levelText = mContext.getString(
                    R.string.battery_low_percent_format, mBatteryLevel);

        if (mBatteryLevelTextView != null) {
            mBatteryLevelTextView.setText(levelText);
            if (mBatteryLevel <= mLowBatterySecondWarningLevel && Build.PRODUCT.equals("Imcosys")) {
                mLowBatteryDialog.dismiss();

                View v = View.inflate(mContext, R.layout.battery_low, null);
                mBatterySubTitleTextView = (TextView)v.findViewById(R.id.subtitle);
                mBatteryLevelTextView = (TextView)v.findViewById(R.id.level_percent);
                if (Build.PRODUCT.equals("Imcosys")) {
                    mBatterySubTitleTextView.setText(R.string.battery_low_subtitle_imcosys);
                    mBatteryLevelTextView.setVisibility(View.GONE);
                }

                AlertDialog.Builder b = new AlertDialog.Builder(mContext);
                b.setCancelable(true);
                if (Build.PRODUCT.equals("Imcosys")) {
                    b.setTitle(R.string.battery_low_title_imcosys);
                } else {
                    b.setTitle(R.string.battery_low_title);
                }
                b.setView(v);
                b.setIcon(android.R.drawable.ic_dialog_alert);
                Intent intent = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_HISTORY);

                AlertDialog d = b.create();
                d.setOnDismissListener(mLowBatteryListener);
                d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                d.show();
                mLowBatteryDialog = d;
            }

        } else {
            View v = View.inflate(mContext, R.layout.battery_low, null);
            mBatterySubTitleTextView = (TextView)v.findViewById(R.id.subtitle);
            mBatteryLevelTextView=(TextView)v.findViewById(R.id.level_percent);

            mBatteryLevelTextView.setText(levelText);
            if (Build.PRODUCT.equals("Imcosys")) {
                mBatterySubTitleTextView.setText(R.string.battery_low_subtitle_imcosys);
                mBatteryLevelTextView.setVisibility(View.GONE);
            }

            AlertDialog.Builder b = new AlertDialog.Builder(mContext);
                b.setCancelable(true);
              if (Build.PRODUCT.equals("Imcosys")) {
                b.setTitle(R.string.battery_low_title_imcosys);
              } else {
                b.setTitle(R.string.battery_low_title);
              }
                b.setView(v);
                b.setIcon(android.R.drawable.ic_dialog_alert);
              if (!Build.PRODUCT.equals("Imcosys")) {
                b.setPositiveButton(android.R.string.ok, null);
              } else if (mBatteryLevel > mLowBatterySecondWarningLevel) {
                b.setPositiveButton(android.R.string.ok, null);
              }

                final Intent intent = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_HISTORY);
                if (intent.resolveActivity(mContext.getPackageManager()) != null) {
                  if (!Build.PRODUCT.equals("Imcosys")) {
                    b.setNegativeButton(R.string.battery_low_why,
                            new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            mContext.startActivity(intent);
                            if (mLowBatteryDialog != null) {
                                mLowBatteryDialog.dismiss();
                            }
                        }
                    });
                  } else if (mBatteryLevel > mLowBatterySecondWarningLevel) {
                    b.setNegativeButton(R.string.battery_low_why,
                            new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            mContext.startActivity(intent);
                            if (mLowBatteryDialog != null) {
                                mLowBatteryDialog.dismiss();
                            }
                        }
                    });
                  }
                }

            AlertDialog d = b.create();
            d.setOnDismissListener(mLowBatteryListener);
            d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            d.show();
            mLowBatteryDialog = d;
        }

        final ContentResolver cr = mContext.getContentResolver();
        if (Settings.System.getInt(cr,
                Settings.System.POWER_SOUNDS_ENABLED, 1) == 1)
        {
            final String soundPath = Settings.System.getString(cr,
                Settings.System.LOW_BATTERY_SOUND);
            if (soundPath != null) {
                final Uri soundUri = Uri.parse("file://" + soundPath);
                if (soundUri != null) {
                    final Ringtone sfx = RingtoneManager.getRingtone(mContext, soundUri);
                    if (sfx != null) {
                        sfx.setStreamType(AudioManager.STREAM_SYSTEM);
                        sfx.play();
                    }
                }
            }
        }
    }

    private DialogInterface.OnDismissListener mLowBatteryListener
            = new DialogInterface.OnDismissListener() {
        public void onDismiss(DialogInterface dialog) {
            mLowBatteryDialog = null;
            mBatteryLevelTextView = null;
        }
    };

    private void scheduleCloseBatteryView() {
        Message m = mHandler.obtainMessage(EVENT_BATTERY_CLOSE);
        m.arg1 = (++mBatteryViewSequence);
        mHandler.sendMessageDelayed(m, 3000);
    }

    private void closeLastBatteryView() {
        if (mBatteryView != null) {
            //mBatteryView.debug();
            WindowManagerImpl.getDefault().removeView(mBatteryView);
            mBatteryView = null;
        }
    }

    private void updateConnectivity(Intent intent) {
        NetworkInfo info = (NetworkInfo)(intent.getParcelableExtra(
                ConnectivityManager.EXTRA_NETWORK_INFO));
        int connectionStatus = intent.getIntExtra(ConnectivityManager.EXTRA_INET_CONDITION, 0);

        int inetCondition = (connectionStatus > INET_CONDITION_THRESHOLD ? 1 : 0);

        switch (info.getType()) {
        case ConnectivityManager.TYPE_WIFI:
            mInetCondition = inetCondition;
            if (info.isConnected()) {
                mIsWifiConnected = true;
                int iconId;
                if (mLastWifiSignalLevel == -1) {
                    iconId = sWifiSignalImages[mInetCondition][0];
                } else {
                    iconId = sWifiSignalImages[mInetCondition][mLastWifiSignalLevel];
                }
                mService.setIcon("wifi", iconId, 0);
                // Show the icon since wi-fi is connected
                mService.setIconVisibility("wifi", true);
            } else {
                mLastWifiSignalLevel = -1;
                mIsWifiConnected = false;
                int iconId = sWifiSignalImages[0][0];

                mService.setIcon("wifi", iconId, 0);
                // Hide the icon since we're not connected
                mService.setIconVisibility("wifi", false);
            }
            break;
        }
    }

    private final void updateVolume() {
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        final int ringerMode = audioManager.getRingerMode();
        final boolean visible = ringerMode == AudioManager.RINGER_MODE_SILENT ||
                ringerMode == AudioManager.RINGER_MODE_VIBRATE;
        final int iconId = audioManager.shouldVibrate(AudioManager.VIBRATE_TYPE_RINGER)
                ? R.drawable.stat_sys_ringer_vibrate
                : R.drawable.stat_sys_ringer_silent;

        if (visible) {
            mService.setIcon("volume", iconId, 0);
        }
        if (visible != mVolumeVisible) {
            mService.setIconVisibility("volume", visible);
            mVolumeVisible = visible;
        }
    }

    private final void updateBluetooth(Intent intent) {
        int iconId = R.drawable.stat_sys_data_bluetooth;
        String action = intent.getAction();
        if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            mBluetoothEnabled = state == BluetoothAdapter.STATE_ON;
        } else if (action.equals(BluetoothHeadset.ACTION_STATE_CHANGED)) {
            mBluetoothHeadsetState = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE,
                    BluetoothHeadset.STATE_ERROR);
        } else if (action.equals(BluetoothA2dp.ACTION_SINK_STATE_CHANGED)) {
            BluetoothA2dp a2dp = new BluetoothA2dp(mContext);
            if (a2dp.getConnectedSinks().size() != 0) {
                mBluetoothA2dpConnected = true;
            } else {
                mBluetoothA2dpConnected = false;
            }
        } else if (action.equals(BluetoothPbap.PBAP_STATE_CHANGED_ACTION)) {
            mBluetoothPbapState = intent.getIntExtra(BluetoothPbap.PBAP_STATE,
                    BluetoothPbap.STATE_DISCONNECTED);
        } else {
            return;
        }

        if (mBluetoothHeadsetState == BluetoothHeadset.STATE_CONNECTED || mBluetoothA2dpConnected ||
                mBluetoothPbapState == BluetoothPbap.STATE_CONNECTED) {
            iconId = R.drawable.stat_sys_data_bluetooth_connected;
        }

        mService.setIcon("bluetooth", iconId, 0);
        mService.setIconVisibility("bluetooth", mBluetoothEnabled);
    }

    private final void updateWifi(Intent intent) {
        final String action = intent.getAction();
        if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {

            final boolean enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;

            if (!enabled) {
                // If disabled, hide the icon. (We show icon when connected.)
                mService.setIconVisibility("wifi", false);
            }

        } else if (action.equals(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION)) {
            final boolean enabled = intent.getBooleanExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED,
                                                           false);
            if (!enabled) {
                mService.setIconVisibility("wifi", false);
            }
        } else if (action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
            int iconId;
            final int newRssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -200);
            int newSignalLevel = WifiManager.calculateSignalLevel(newRssi,
                                                                  sWifiSignalImages[0].length);
            if (newSignalLevel != mLastWifiSignalLevel) {
                mLastWifiSignalLevel = newSignalLevel;
                if (mIsWifiConnected) {
                    iconId = sWifiSignalImages[mInetCondition][newSignalLevel];
                } else {
                    iconId = sWifiTemporarilyNotConnectedImage;
                }
                mService.setIcon("wifi", iconId, 0);
            }
        }
    }

    private class StatusBarHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case EVENT_BATTERY_CLOSE:
                if (msg.arg1 == mBatteryViewSequence) {
                    closeLastBatteryView();
                }
                break;
            }
        }
    }
}
