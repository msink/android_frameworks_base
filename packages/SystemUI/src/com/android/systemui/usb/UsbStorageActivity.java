/*
 * Copyright (C) 2007 Google Inc.
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

package com.android.systemui.usb;

import com.android.systemui.R;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.DialogInterface.OnCancelListener;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.hardware.Usb;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.storage.IMountService;
import android.os.storage.StorageManager;
import android.os.storage.StorageEventListener;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.util.Log;

import java.util.List;

/**
 * This activity is shown to the user for him/her to enable USB mass storage
 * on-demand (that is, when the USB cable is connected). It uses the alert
 * dialog style. It will be launched from a notification.
 */
public class UsbStorageActivity extends Activity
        implements OnCancelListener {
    private static final String TAG = "UsbStorageActivity";
    private static boolean debug = false;

    private void LOG(String msg) {
        if (debug)
            Log.e(TAG, msg);
    }

    private TextView chargingIcon;
    private ImageView chargingSelect;
    private TextView usbIcon;
    private TextView usbIconLarger;
    private TextView usbMsg;
    private LinearLayout usbConnectted;
    private LinearLayout usbHeader;
    private LinearLayout usbPreConnect;
    private ImageView usbSelect;
    private LinearLayout fullScreenLayout;

    private AlertDialog ad = null;
    private StorageManager mStorageManager = null;
    private static final int DLG_CONFIRM_KILL_STORAGE_USERS = 1;
    private static final int DLG_ERROR_SHARING = 2;
    static final boolean localLOGV = false;
    private boolean dialogIsThere = false;
    private boolean mWasUsbStorageInUse = false;
    private boolean mSkipSwitch = false;

    // UI thread
    private Handler mUIHandler;

    // thread for working with the storage services, which can be slow
    private Handler mAsyncStorageHandler;

    /** Used to detect when the USB cable is unplugged, so we can call finish() */
    private BroadcastReceiver mUsbStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Usb.ACTION_USB_STATE)) {
                LOG("receive Usb.ACTION_USB_STATE broadcast");
                handleUsbStateChanged(intent);
            } else if (intent.getAction().equals(Intent.ACTION_CLOSE_STATUSBAR_USB)) {
                LOG("receive ACTION_CLOSE_STATUSBAR_USB broadcast");
                if (dialogIsThere) {
                    dismissDialog(DLG_CONFIRM_KILL_STORAGE_USERS);
                    dialogIsThere = false;
                }
                finish();
            }
        }
    };

    private StorageEventListener mStorageListener = new StorageEventListener() {
        @Override
        public void onStorageStateChanged(String path, String oldState, String newState) {
            LOG("onStorageStateChanged----------------" + path +
                "---oldState---" + oldState +
                "----newState-----" + newState);
            if (newState.equals(Environment.MEDIA_SHARED) &&
                    path.equals(Environment.getFlashStorageDirectory().getPath())) {
                LOG("receive Environment.MEDIA_SHARED");
                switchDisplay(true);
            } else if (newState.equals(Environment.MEDIA_MOUNTED) &&
                    path.equals(Environment.getFlashStorageDirectory().getPath())) {
                LOG("receive Environment.MEDIA_MOUNTED");
                switchDisplay(false);
            }
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LOG("onCreate..............");

        if (mStorageManager == null) {
            mStorageManager = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
            if (mStorageManager == null) {
                Log.w(TAG, "Failed to get StorageManager");
            }
        }
        
        mUIHandler = new Handler();

        HandlerThread thr = new HandlerThread("SystemUI UsbStorageActivity");
        thr.start();
        mAsyncStorageHandler = new Handler(thr.getLooper());

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (Environment.isExternalStorageRemovable()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }

        setContentView(R.layout.usb_layout_all);

        usbMsg = (TextView) findViewById(R.id.usb_msg);
        usbIconLarger = (TextView) findViewById(R.id.usb_icon_larger);
        if (Build.PRODUCT.equals("Texet"))
            usbIconLarger.setBackgroundResource(R.drawable.texet_usbconnected);

        chargingIcon = (TextView) findViewById(R.id.btr_choose_icon);
        usbIcon = (TextView) findViewById(R.id.usb_choose_icon);
        chargingSelect = (ImageView) findViewById(R.id.btr_click);
        usbSelect = (ImageView) findViewById(R.id.usb_click);
        usbHeader = (LinearLayout) findViewById(R.id.usb_header);
        usbPreConnect = (LinearLayout) findViewById(R.id.usb_pre_connect);
        usbConnectted = (LinearLayout) findViewById(R.id.usb_connected);
        fullScreenLayout = (LinearLayout) findViewById(R.id.usb_dis_full_screen);
        if (Build.PRODUCT.equals("inves"))
            mSkipSwitch = true;

        if (mSkipSwitch) {
            fullScreenLayout.setVisibility(View.GONE);
            usbConnectted.setVisibility(View.GONE);
            usbPreConnect.setVisibility(View.VISIBLE);

            if (chargingSelect.isFocused()) {
                chargingIcon.setBackgroundResource(R.drawable.choose_icon_foc);
                usbIcon.setBackgroundResource(R.drawable.choose_icon_def);
            } else {
                chargingIcon.setBackgroundResource(R.drawable.choose_icon_def);
                usbIcon.setBackgroundResource(R.drawable.choose_icon_foc);
            }
        } else {
            usbPreConnect.setVisibility(View.GONE);
            if (Build.PRODUCT.equals("DistriRead")) {
                fullScreenLayout.setVisibility(View.VISIBLE);
                usbConnectted.setVisibility(View.GONE);
            } else {
                fullScreenLayout.setVisibility(View.GONE);
                usbConnectted.setVisibility(View.VISIBLE);
            }
        }

        chargingSelect.setOnClickListener(new ImageView.OnClickListener() {
            public void onClick(View v) {
                LOG("+-----------------------------+");
                finish();
            }
        });

        usbSelect.setOnClickListener(new ImageView.OnClickListener() {
            public void onClick(View v) {
                LOG("+++++++++++++++++++++++++++++");
                switchUsbMassStorage(true);
            }
        });

        if (!Build.PRODUCT.equals("inves")) {
            switchDisplay(true, true);
            if (!mStorageManager.isUsbMassStorageEnabled()) {
                switchUsbMassStorage(true);
            }
            mSkipSwitch = true;
        }
    }

    private void switchDisplay(final boolean usbStorageInUse) {
        LOG("switchDisplay.........usbStorageInUse....." + usbStorageInUse);
        switchDisplay(usbStorageInUse, false);
    }

    private void switchDisplay(final boolean usbStorageInUse, final boolean force) {
        LOG("switchDisplay.........usbStorageInUse....." + usbStorageInUse +
            ".....force....." + force);
        if (usbStorageInUse == mWasUsbStorageInUse && !force) {
            return;
        }
        mWasUsbStorageInUse = usbStorageInUse;
        mUIHandler.post(new Runnable() {
            @Override
            public void run() {
                switchDisplayAsync(usbStorageInUse);
            }
        });
    }

    private void switchDisplayAsync(boolean usbStorageInUse) {
        LOG("switchDisplayAsync.........usbStorageInUse....." + usbStorageInUse);
        if (!Build.PRODUCT.equals("DistriRead")) {
            usbPreConnect.setVisibility(View.GONE);
            usbConnectted.setVisibility(View.VISIBLE);
            usbHeader.setVisibility(View.VISIBLE);
            if (Build.PRODUCT.equals("Texet"))
                usbIconLarger.setBackgroundResource(R.drawable.texet_usbconnected);
            if (usbStorageInUse) {
                 usbMsg.setText(R.string.connected_computer);
            } else {
                 usbMsg.setText(R.string.connecting);
            }
        } else {
            usbPreConnect.setVisibility(View.GONE);
            usbConnectted.setVisibility(View.GONE);
            usbHeader.setVisibility(View.GONE);
            fullScreenLayout.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        LOG("onResume..............");

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_STATUSBAR_USB);
        filter.addAction(Usb.ACTION_USB_STATE);
        mStorageManager.registerListener(mStorageListener);
        registerReceiver(mUsbStateReceiver, filter);
        if (mSkipSwitch && !Build.PRODUCT.equals("inves")) {
            mSkipSwitch = false;
        } else if (!Build.PRODUCT.equals("inves") && !mStorageManager.isUsbMassStorageEnabled()) {
            switchUsbMassStorage(true);
        }
    }

    @Override
    public void onAttachedToWindow() {
        getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD);
        super.onAttachedToWindow();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (usbPreConnect.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (chargingSelect.isFocused())
                    usbSelect.requestFocus();
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (usbSelect.isFocused())
                    chargingSelect.requestFocus();
            }
            if (chargingSelect.isFocused()) {
                chargingIcon.setBackgroundResource(R.drawable.choose_icon_foc);
                usbIcon.setBackgroundResource(R.drawable.choose_icon_def);
            } else {
                chargingIcon.setBackgroundResource(R.drawable.choose_icon_def);
                usbIcon.setBackgroundResource(R.drawable.choose_icon_foc);
            }
        }
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_BACK) {
            LOG("Home key down");
            finish();
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LOG("onPause..............");
        if (mUsbStateReceiver != null) {
            unregisterReceiver(mUsbStateReceiver);
        }
        if (mStorageManager == null && mStorageListener != null) {
            mStorageManager.unregisterListener(mStorageListener);
        }
    }

    protected void onDestroy() {
        super.onDestroy();
        LOG("onDestroy..............");
        switchUsbMassStorage(false);
    }

    private void handleUsbStateChanged(Intent intent) {
        this.LOG("handleUsbStateChanged..............");
        boolean connected = intent.getExtras().getBoolean(Usb.USB_CONNECTED);
        if (!connected) {
            // It was disconnected from the plug, so finish
            if (dialogIsThere) {
                dismissDialog(DLG_CONFIRM_KILL_STORAGE_USERS);
                dialogIsThere = false;
            }
            finish();
        }
    }

    @Override
    public Dialog onCreateDialog(int id, Bundle args) {
        switch (id) {
        case DLG_CONFIRM_KILL_STORAGE_USERS:
            dialogIsThere = true;
            ad = new AlertDialog.Builder(this)
                    .setTitle(com.android.internal.R.string.dlg_confirm_kill_storage_users_title)
                    .setPositiveButton(com.android.internal.R.string.dlg_ok,
                        new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dismissDialog(DLG_CONFIRM_KILL_STORAGE_USERS);
                            dialogIsThere = false;
                            switchUsbMassStorage(true);
                        }})
                    .setNegativeButton(com.android.internal.R.string.cancel,
                        new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dismissDialog(DLG_CONFIRM_KILL_STORAGE_USERS);
                            dialogIsThere = false;
                            finish();
                        }})
                    .setMessage(com.android.internal.R.string.dlg_confirm_kill_storage_users_text)
                    .setOnCancelListener(this)
                    .create();
            return ad;
        case DLG_ERROR_SHARING:
            ad = new AlertDialog.Builder(this)
                    .setTitle(com.android.internal.R.string.dlg_error_title)
                    .setNeutralButton(com.android.internal.R.string.dlg_ok, null)
                    .setMessage(com.android.internal.R.string.usb_storage_error_message)
                    .setOnCancelListener(this)
                    .create();
            return ad;
        }
        return null;
    }

    private void switchUsbMassStorage(final boolean on) {
        LOG("switchUsbMassStorage..........ON...." + on);

        // things to do elsewhere
        mAsyncStorageHandler.post(new Runnable() {
            @Override
            public void run() {
                if (on) {
                    mStorageManager.enableUsbMassStorage();
                } else {
                    mStorageManager.disableUsbMassStorage();
                }
                switchDisplay(mStorageManager.isUsbMassStorageEnabled(), true);
            }
        });
    }

    public void onCancel(DialogInterface dialog) {
        LOG("onCancel..............");
        if (dialogIsThere) {
            dismissDialog(DLG_CONFIRM_KILL_STORAGE_USERS);
            dialogIsThere = false;
        }
        finish();
    }
}
