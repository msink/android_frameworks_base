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

package com.android.systemui.statusbar;

import android.app.Service;
import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;
import com.android.internal.statusbar.StatusBarNotification;

import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.DeviceController;
import android.hardware.EpdManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RatingBar;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ToggleButton;
import android.widget.FrameLayout;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.StatusBarPolicy;



public class StatusBarService extends Service implements CommandQueue.Callbacks {
    static final String TAG = "StatusBarService";
    static final boolean SPEW_ICONS = false;
    static final boolean SPEW = false;

    private static final int MSG_ANIMATE = 1000;
    private static final int MSG_ANIMATE_REVEAL = 1001;

    public static final String ACTION_STATUSBAR_START
            = "com.android.internal.policy.statusbar.START";

    static final int EXPANDED_LEAVE_ALONE = -10000;
    static final int EXPANDED_FULL_OPEN = -10001;

    public static int BRIGHTNESS_ON = DeviceController.BRIGHTNESS_MAXIMUM;
    private Integer[] mFrontLightValue = {
          0,   5,  10,  15,  20,  25,  30,  35,  40,  45,
         50,  55,  60,  65,  70,  75,  80,  85,  90,  95,
        100, 105, 110, 115, 120, 125, 130, 135, 140, 145,
        150, 155, 160,           175, 180, 185, 190, 195,
        200, 205, 210, 215, 220, 225, 230, 235, 240, 245,
        250 };
    private List<Integer> mLightSteps = new ArrayList();
    private boolean isLongClickOpenAndCloseFrontLight = false;
    private IntentFilter filter = null;
    private BroadcastReceiver mOpenAndCloseFrontLightReceiver = null;
    private Context mContext = null;

    StatusBarPolicy mIconPolicy;

    CommandQueue mCommandQueue;
    IStatusBarService mBarService;

    int mIconSize;
    Display mDisplay;
    StatusBarView mStatusBarView;
    int mPixelFormat;
    H mHandler = new H();
    Object mQueueLock = new Object();

    // icons
    LinearLayout mIcons;
    IconMerger mNotificationIcons;
    LinearLayout mStatusIcons;

    // expanded notifications
    Dialog mExpandedDialog;
    ExpandedView mExpandedView;
    WindowManager.LayoutParams mExpandedParams;
    ScrollView mScrollView;
    View mNotificationLinearLayout;
    View mExpandedContents;
    // top bar
    TextView mNoNotificationsTitle;
    TextView mClearButton;
    // ongoing
    NotificationData mOngoing = new NotificationData();
    TextView mOngoingTitle;
    LinearLayout mOngoingItems;
    // latest
    NotificationData mLatest = new NotificationData();
    TextView mLatestTitle;
    LinearLayout mLatestItems;
    // position
    int[] mPositionTmp = new int[2];
    boolean mExpandedVisible;

    // the date view
    DateView mDateView;

    // the tracker view
    private boolean mPanelSlightlyVisible;

    // ticker
    private Ticker mTicker;
    private View mTickerView;
    private boolean mTicking;

    // Tracking finger for opening/closing.
    int mEdgeBorder; // corresponds to R.dimen.status_bar_edge_ignore
    boolean mTracking;
    VelocityTracker mVelocityTracker;

    static final int ANIM_FRAME_DURATION = (1000/60);
    float mDisplayHeight;
    boolean mAnimatingReveal = false;
    int[] mAbsPos = new int[2];

    // for disabling the status bar
    int mDisabled = StatusBarManager.DISABLE_NOTIFICATION_ICONS;

    final private static int LOW_BRIGHTNESS_MAX =
        Math.min(DeviceController.BRIGHTNESS_DEFAULT,
                 DeviceController.BRIGHTNESS_MAXIMUM);

    int currentKeyCode;
    float mAnimAccel;
    long mAnimLastTime;
    float mAnimVel;
    float mAnimY;
    boolean mAnimating;
    private ImageButton mBtnRefreshMode = null;
    CloseDragHandle mCloseView;
    long mCurAnimationTime;
    private DeviceController mDev;
    EpdManager mEpd;
    public boolean mExpanded;
    private ToggleButton mLightSwitch = null;
    WindowManager.LayoutParams mTrackingParams;
    int mTrackingPosition;
    TrackingView mTrackingView;
    int mViewDelta;
    private ToggleButton mWifiSwitch = null;
    private ImageButton mWifiSettings  = null;
    private WifiManager mWifiManager = null;
    private RatingBar mRatingBarLightSettings = null;
    AlertDialog mHomeMenuDialog = null;
    View homeMenuDialogView = null;
    private boolean isHomeMenuDialogShow = false;

    private class ExpandedDialog extends Dialog {
        ExpandedDialog(Context context) {
            super(context, com.android.internal.R.style.Theme_Light_NoTitleBar);
            mContext = context;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    animateCollapse();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }


    @Override
    public void onCreate() {
        // First set up our views and stuff.
        mDisplay = ((WindowManager)getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        makeStatusBarView(this);

        // Connect in to the status bar manager service
        StatusBarIconList iconList = new StatusBarIconList();
        ArrayList<IBinder> notificationKeys = new ArrayList<IBinder>();
        ArrayList<StatusBarNotification> notifications = new ArrayList<StatusBarNotification>();
        mCommandQueue = new CommandQueue(this, iconList);
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        try {
            mBarService.registerStatusBar(mCommandQueue, iconList, notificationKeys, notifications);
        } catch (RemoteException ex) {
            // If the system process isn't there we're doomed anyway.
        }

        // Set up the initial icon state
        int N = iconList.size();
        int viewIndex = 0;
        for (int i=0; i<N; i++) {
            StatusBarIcon icon = iconList.getIcon(i);
            if (icon != null) {
                addIcon(iconList.getSlot(i), i, viewIndex, icon);
                viewIndex++;
            }
        }

        mOpenAndCloseFrontLightReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                if (intent != null && intent.getAction() != null &&
                        (DeviceController.ACTION_OPEN_FRONT_LIGHT.equals(intent.getAction()) ||
                        DeviceController.ACTION_CLOSE_FRONT_LIGHT.equals(intent.getAction()))) {
                    isLongClickOpenAndCloseFrontLight = true;
                    int front_light_value = intent.getIntExtra(DeviceController.INTENT_FRONT_LIGHT_VALUE, 0);
                    mRatingBarLightSettings.setProgress(getIndex(front_light_value));
                }
            }
        };
        filter = new IntentFilter();
        filter.addAction(DeviceController.ACTION_OPEN_FRONT_LIGHT);
        filter.addAction(DeviceController.ACTION_CLOSE_FRONT_LIGHT);
        mContext.registerReceiver(mOpenAndCloseFrontLightReceiver, filter);

        mLightSteps = Arrays.asList(mFrontLightValue);
        if (mLightSteps != null) {
            mRatingBarLightSettings.setNumStars(mLightSteps.size() - 1);
            mRatingBarLightSettings.setMax(mLightSteps.size() - 1);
        } else {
            int numStarts = mRatingBarLightSettings.getNumStars();
            mLightSteps = initRangeArray(numStarts);
        }
        Collections.sort(mLightSteps);

        // Set up the initial notification state
        N = notificationKeys.size();
        if (N == notifications.size()) {
            for (int i=0; i<N; i++) {
                addNotification(notificationKeys.get(i), notifications.get(i));
            }
        } else {
            Log.wtf(TAG, "Notification list length mismatch: keys=" + N
                    + " notifications=" + notifications.size());
        }

        // Put up the view
        addStatusBarView();

        // Lastly, call to the icon policy to install/update all the icons.
        mIconPolicy = new StatusBarPolicy(this);

        mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
    }

    @Override
    public void onDestroy() {
        if (mOpenAndCloseFrontLightReceiver != null) {
            mContext.unregisterReceiver(mOpenAndCloseFrontLightReceiver);
        }
    }

    /**
     * Nobody binds to us.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private List<Integer> initRangeArray(int numStarts) {
        List<Integer> brightnessList = new ArrayList(numStarts);
        for (int i = 0; i <= numStarts; i++) {
            brightnessList.add((BRIGHTNESS_ON * i) / numStarts);
        }
        return brightnessList;
    }

    private int getIndex(int val) {
        int index = Collections.binarySearch(mLightSteps, val);
        if (index == -1) {
            index = 0;
        } else if (index < 0) {
            if (Math.abs(index) <= mLightSteps.size()) {
                index = Math.abs(index) - 2;
            } else {
                index = mLightSteps.size() - 1;
            }
        }
        return index;
    }

    // ================================================================================
    // Constructing the view
    // ================================================================================
    private void makeStatusBarView(Context context) {
        Resources res = context.getResources();

        mIconSize = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_icon_size);

        ExpandedView expanded = (ExpandedView)View.inflate(context,
                R.layout.status_bar_expanded, null);
        expanded.mService = this;

        StatusBarView sb = (StatusBarView)View.inflate(context, R.layout.status_bar, null);
        sb.mService = this;

        // figure out which pixel-format to use for the status bar.
        mPixelFormat = PixelFormat.TRANSLUCENT;
        Drawable bg = sb.getBackground();
        if (bg != null) {
            mPixelFormat = bg.getOpacity();
        }

        mDev = new DeviceController(context);

        mStatusBarView = sb;
        mStatusIcons = (LinearLayout)sb.findViewById(R.id.statusIcons);
        mNotificationIcons = (IconMerger)sb.findViewById(R.id.notificationIcons);
        mIcons = (LinearLayout)sb.findViewById(R.id.icons);
        mTickerView = sb.findViewById(R.id.ticker);

        mExpandedDialog = new ExpandedDialog(context);
        mExpandedView = expanded;
        mDateView = (DateView)expanded.findViewById(R.id.date);
        mExpandedContents = expanded.findViewById(R.id.notificationLinearLayout);
        mOngoingTitle = (TextView)expanded.findViewById(R.id.ongoingTitle);
        mOngoingItems = (LinearLayout)expanded.findViewById(R.id.ongoingItems);
        mLatestTitle = (TextView)expanded.findViewById(R.id.latestTitle);
        mLatestItems = (LinearLayout)expanded.findViewById(R.id.latestItems);
        mNoNotificationsTitle = (TextView)expanded.findViewById(R.id.noNotificationsTitle);
        mClearButton = (TextView)expanded.findViewById(R.id.clear_all_button);
        mClearButton.setOnClickListener(mClearButtonListener);
        mScrollView = (ScrollView)expanded.findViewById(R.id.scroll);
        mNotificationLinearLayout = expanded.findViewById(R.id.notificationLinearLayout);

        mRatingBarLightSettings = (RatingBar)
            expanded.findViewById(R.id.ratingbar_light_settings);
        mRatingBarLightSettings.setFocusable(false);
        setLightRatingBarDefaultProgress();
        mRatingBarLightSettings.setOnRatingBarChangeListener(
            new RatingBar.OnRatingBarChangeListener() {
            @Override
            public void onRatingChanged(RatingBar ratingBar, float rating,
                    boolean fromUser) {
                if (!isLongClickOpenAndCloseFrontLight) {
                    setFrontLightValue();
                }
                updateLightSwitch();
                isLongClickOpenAndCloseFrontLight = false;
            }
        });
        mRatingBarLightSettings.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == 0)
                    mEpd.setMode(EpdManager.A2);
                else if (event.getAction() == 1)
                    mEpd.setMode(EpdManager.PART);
                else if (event.getAction() == 2)
                    setLightRatingBarProgress();
                return false;
            }
        });

        mBtnRefreshMode = (ImageButton)
            expanded.findViewById(R.id.imagebutton_refresh_mode);
        mBtnRefreshMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!StatusBarView.isA2Mode) {
                    v.requestEpdMode(View.EPD_A2, true);
                    StatusBarView.isA2Mode = true;
                    mBtnRefreshMode.setImageResource(R.drawable.refresh_a2_light);
                } else {
                    v.requestEpdMode(View.EPD_NULL, true);
                    StatusBarView.isA2Mode = false;
                    mBtnRefreshMode.setImageResource(R.drawable.refresh_light);
                }
            }
        });

        LinearLayout linearlayout_light_switch = (LinearLayout)
            expanded.findViewById(R.id.linearlayout_light_switch);
        mLightSwitch = (ToggleButton)
            expanded.findViewById(R.id.togglebutton_light_switch);
        linearlayout_light_switch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mLightSwitch.isChecked()) {
                    mLightSwitch.setChecked(true);
                    mDev.openFrontLight();
                } else {
                    mLightSwitch.setChecked(false);
                    mDev.closeFrontLight();
                }
            }
        });
        mLightSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mLightSwitch.isChecked()) {
                    mDev.openFrontLight();
                } else {
                    mDev.closeFrontLight();
                }
            }
        });

        ImageButton volume_add = (ImageButton)
            expanded.findViewById(R.id.imagebutton_volume_add);
        volume_add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mStatusBarView.Adjust_Volume(true);
            }
        });
        ImageButton volume_down = (ImageButton)
            expanded.findViewById(R.id.imagebutton_volume_down);
        volume_down.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mStatusBarView.Adjust_Volume(false);
            }
        });
        ImageButton light_down = (ImageButton)
            expanded.findViewById(R.id.imagebutton_light_down);
        light_down.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mRatingBarLightSettings.setProgress(
                    mRatingBarLightSettings.getProgress() - 1);
                if (mRatingBarLightSettings.getProgress() == 0) {
                    mLightSwitch.setChecked(false);
                } else {
                    mLightSwitch.setChecked(true);
                }
            }
        });
        ImageButton light_add = (ImageButton)
            expanded.findViewById(R.id.imagebutton_light_add);
        light_add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mLightSwitch.setChecked(true);
                if (mRatingBarLightSettings.getProgress()
                        == mRatingBarLightSettings.getMax()) {
                    setLightRatingBarProgress();
                } else {
                    mRatingBarLightSettings.setProgress(
                        mRatingBarLightSettings.getProgress() + 1);
                }

            }
        });
        LinearLayout linearLayout_wifi_switch = (LinearLayout)
            expanded.findViewById(R.id.linearlayout_wifi_switch);
        mWifiSwitch = (ToggleButton)
            expanded.findViewById(R.id.togglebutton_wifi_switch);
        if (Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.PARENT_CONTROL_ENABLED, 0) == 1) {
            linearLayout_wifi_switch.setVisibility(View.GONE);
        }
        linearLayout_wifi_switch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mWifiManager.isWifiEnabled()) {
                    mWifiManager.setWifiEnabled(false);
                } else {
                    mWifiManager.setWifiEnabled(true);
                }
            }
        });
        mWifiSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mWifiManager.isWifiEnabled()) {
                    mWifiManager.setWifiEnabled(false);
                } else {
                    mWifiManager.setWifiEnabled(true);
                }
            }
        });
        mWifiSettings = (ImageButton)
            expanded.findViewById(R.id.button_wifi_settings);
        mWifiSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent();
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setAction(Settings.ACTION_WIFI_SETTINGS);
                startActivity(intent);
                mExpandedDialog.dispatchKeyEvent(new KeyEvent(1, 4));
            }
        });

        mExpandedView.setVisibility(View.GONE);
        mOngoingTitle.setVisibility(View.GONE);
        mLatestTitle.setVisibility(View.GONE);

        mTicker = new MyTicker(context, sb);
        TickerView tickerView = (TickerView)sb.findViewById(R.id.tickerText);
        tickerView.mTicker = mTicker;

        mTrackingView = (TrackingView) View.inflate(context,
            R.layout.status_bar_tracking, null);
        mTrackingView.mService = this;
        mCloseView = (CloseDragHandle) mTrackingView.findViewById(R.id.close);
        mCloseView.mService = this;

        mEdgeBorder = res.getDimensionPixelSize(R.dimen.status_bar_edge_ignore);

        // set the inital view visibility
        setAreThereNotifications();

        mEpd = new EpdManager(context);

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(Intent.ACTION_CLOSE_STATUSBAR_USB);
        filter.addAction(Intent.ACTION_HOME_MENU);
        filter.addAction(Intent.ACTION_CHANGE_LIGHT_STATE);
        context.registerReceiver(mBroadcastReceiver, filter);

        LinearLayout linearlayout_light_panel = (LinearLayout)
                expanded.findViewById(R.id.linearlayout_light_panel);
        CarrierLabel carrierlabelView = (CarrierLabel)
                expanded.findViewById(R.id.carrierlabel);

        if (mDev.isTouchable()) {
            mBtnRefreshMode.setVisibility(View.GONE);
        }
        if (!mDev.hasWifi()) {
            linearLayout_wifi_switch.setVisibility(View.GONE);
            mWifiSettings.setVisibility(View.GONE);
            if (carrierlabelView != null) {
                carrierlabelView.setVisibility(View.INVISIBLE);
            }
        }
        if (!mDev.hasAudio()) {
            volume_add.setVisibility(View.GONE);
            volume_down.setVisibility(View.GONE);
        }
        if (!mDev.hasFrontLight()) {
            linearlayout_light_panel.setVisibility(View.GONE);
        }
        updateLightSwitch();
    }

    protected void addStatusBarView() {
        Resources res = getResources();
        final int height= res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);

        final StatusBarView view = mStatusBarView;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height,
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING,
                PixelFormat.RGBX_8888);
        lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
        lp.setTitle("StatusBar");
        lp.windowAnimations = com.android.internal.R.style.Animation_StatusBar;

        WindowManagerImpl.getDefault().addView(view, lp);
    }

    public void addIcon(String slot, int index, int viewIndex, StatusBarIcon icon) {
        if (SPEW_ICONS) {
            Slog.d(TAG, "addIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex
                    + " icon=" + icon);
        }
        StatusBarIconView view = new StatusBarIconView(this, slot);
        view.set(icon);
        mStatusIcons.addView(view, viewIndex, new LinearLayout.LayoutParams(mIconSize, -1));
    }

    public void updateIcon(String slot, int index, int viewIndex,
            StatusBarIcon old, StatusBarIcon icon) {
        if (SPEW_ICONS) {
            Slog.d(TAG, "updateIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex
                    + " old=" + old + " icon=" + icon);
        }
        StatusBarIconView view = (StatusBarIconView)mStatusIcons.getChildAt(viewIndex);
        view.set(icon);
    }

    public void removeIcon(String slot, int index, int viewIndex) {
        if (SPEW_ICONS) {
            Slog.d(TAG, "removeIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex);
        }
        mStatusIcons.removeViewAt(viewIndex);
    }

    public void addNotification(IBinder key, StatusBarNotification notification) {
        boolean shouldTick = true;
        if (notification.notification.fullScreenIntent != null) {
            shouldTick = false;
            Slog.d(TAG, "Notification has fullScreenIntent; sending fullScreenIntent");
            try {
                notification.notification.fullScreenIntent.send();
            } catch (PendingIntent.CanceledException e) {
            }
        } 

        StatusBarIconView iconView = addNotificationViews(key, notification);
        if (iconView == null) return;

        if (shouldTick) {
            tick(notification);
        }
        
        // Recalculate the position of the sliding windows and the titles.
        setAreThereNotifications();
        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
    }

    public void updateNotification(IBinder key, StatusBarNotification notification) {
        NotificationData oldList;
        int oldIndex = mOngoing.findEntry(key);
        if (oldIndex >= 0) {
            oldList = mOngoing;
        } else {
            oldIndex = mLatest.findEntry(key);
            if (oldIndex < 0) {
                Slog.w(TAG, "updateNotification for unknown key: " + key);
                return;
            }
            oldList = mLatest;
        }
        final NotificationData.Entry oldEntry = oldList.getEntryAt(oldIndex);
        final StatusBarNotification oldNotification = oldEntry.notification;
        final RemoteViews oldContentView = oldNotification.notification.contentView;

        final RemoteViews contentView = notification.notification.contentView;

        if (false) {
            Slog.d(TAG, "old notification: when=" + oldNotification.notification.when
                    + " ongoing=" + oldNotification.isOngoing()
                    + " expanded=" + oldEntry.expanded
                    + " contentView=" + oldContentView);
            Slog.d(TAG, "new notification: when=" + notification.notification.when
                    + " ongoing=" + oldNotification.isOngoing()
                    + " contentView=" + contentView);
        }

        // Can we just reapply the RemoteViews in place?  If when didn't change, the order
        // didn't change.
        if (notification.notification.when == oldNotification.notification.when
                && notification.isOngoing() == oldNotification.isOngoing()
                && oldEntry.expanded != null
                && contentView != null && oldContentView != null
                && contentView.getPackage() != null
                && oldContentView.getPackage() != null
                && oldContentView.getPackage().equals(contentView.getPackage())
                && oldContentView.getLayoutId() == contentView.getLayoutId()) {
            if (SPEW) Slog.d(TAG, "reusing notification");
            oldEntry.notification = notification;
            try {
                // Reapply the RemoteViews
                contentView.reapply(this, oldEntry.content);
                // update the contentIntent
                final PendingIntent contentIntent = notification.notification.contentIntent;
                if (contentIntent != null) {
                    oldEntry.content.setOnClickListener(new Launcher(contentIntent,
                                notification.pkg, notification.tag, notification.id));
                }
                // Update the icon.
                final StatusBarIcon ic = new StatusBarIcon(notification.pkg,
                        notification.notification.icon, notification.notification.iconLevel,
                        notification.notification.number);
                if (!oldEntry.icon.set(ic)) {
                    handleNotificationError(key, notification, "Couldn't update icon: " + ic);
                    return;
                }
            }
            catch (RuntimeException e) {
                // It failed to add cleanly.  Log, and remove the view from the panel.
                Slog.w(TAG, "Couldn't reapply views for package " + contentView.getPackage(), e);
                removeNotificationViews(key);
                addNotificationViews(key, notification);
            }
        } else {
            if (SPEW) Slog.d(TAG, "not reusing notification");
            removeNotificationViews(key);
            addNotificationViews(key, notification);
        }

        // Restart the ticker if it's still running
        if (notification.notification.tickerText != null
                && !TextUtils.equals(notification.notification.tickerText,
                    oldEntry.notification.notification.tickerText)) {
            tick(notification);
        }

        // Recalculate the position of the sliding windows and the titles.
        setAreThereNotifications();
        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
    }

    public void removeNotification(IBinder key) {
        if (SPEW) Slog.d(TAG, "removeNotification key=" + key);
        StatusBarNotification old = removeNotificationViews(key);

        if (old != null) {
            // Cancel the ticker if it's still running
            mTicker.removeEntry(old);

            // Recalculate the position of the sliding windows and the titles.
            setAreThereNotifications();
            updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
        }
    }

    private int chooseIconIndex(boolean isOngoing, int viewIndex) {
        final int latestSize = mLatest.size();
        if (isOngoing) {
            return latestSize + (mOngoing.size() - viewIndex);
        } else {
            return latestSize - viewIndex;
        }
    }

    View[] makeNotificationView(StatusBarNotification notification, ViewGroup parent) {
        Notification n = notification.notification;
        RemoteViews remoteViews = n.contentView;
        if (remoteViews == null) {
            return null;
        }

        // create the row view
        LayoutInflater inflater = (LayoutInflater)getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View row = inflater.inflate(R.layout.status_bar_latest_event, parent, false);

        // bind the click event to the content area
        ViewGroup content = (ViewGroup)row.findViewById(R.id.content);
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        content.setOnFocusChangeListener(mFocusChangeListener);
        PendingIntent contentIntent = n.contentIntent;
        if (contentIntent != null) {
            content.setOnClickListener(new Launcher(contentIntent, notification.pkg,
                        notification.tag, notification.id));
        }

        View expanded = null;
        Exception exception = null;
        try {
            expanded = remoteViews.apply(this, content);
        }
        catch (RuntimeException e) {
            exception = e;
        }
        if (expanded == null) {
            String ident = notification.pkg + "/0x" + Integer.toHexString(notification.id);
            Slog.e(TAG, "couldn't inflate view for notification " + ident, exception);
            return null;
        } else {
            content.addView(expanded);
            row.setDrawingCacheEnabled(true);
        }

        return new View[] { row, content, expanded };
    }

    StatusBarIconView addNotificationViews(IBinder key, StatusBarNotification notification) {
        NotificationData list;
        ViewGroup parent;
        final boolean isOngoing = notification.isOngoing();
        if (isOngoing) {
            list = mOngoing;
            parent = mOngoingItems;
        } else {
            list = mLatest;
            parent = mLatestItems;
        }
        // Construct the expanded view.
        final View[] views = makeNotificationView(notification, parent);
        if (views == null) {
            handleNotificationError(key, notification, "Couldn't expand RemoteViews for: "
                    + notification);
            return null;
        }
        final View row = views[0];
        final View content = views[1];
        final View expanded = views[2];
        // Construct the icon.
        final StatusBarIconView iconView = new StatusBarIconView(this,
                notification.pkg + "/0x" + Integer.toHexString(notification.id));
        final StatusBarIcon ic = new StatusBarIcon(notification.pkg, notification.notification.icon,
                    notification.notification.iconLevel, notification.notification.number);
        if (!iconView.set(ic)) {
            handleNotificationError(key, notification, "Coulding create icon: " + ic);
            return null;
        }
        // Add the expanded view.
        final int viewIndex = list.add(key, notification, row, content, expanded, iconView);
        parent.addView(row, viewIndex);
        // Add the icon.
        final int iconIndex = chooseIconIndex(isOngoing, viewIndex);
        mNotificationIcons.addView(iconView, iconIndex);
        return iconView;
    }

    StatusBarNotification removeNotificationViews(IBinder key) {
        NotificationData.Entry entry = mOngoing.remove(key);
        if (entry == null) {
            entry = mLatest.remove(key);
            if (entry == null) {
                Slog.w(TAG, "removeNotification for unknown key: " + key);
                return null;
            }
        }
        // Remove the expanded view.
        ((ViewGroup)entry.row.getParent()).removeView(entry.row);
        // Remove the icon.
        ((ViewGroup)entry.icon.getParent()).removeView(entry.icon);

        return entry.notification;
    }

    private void setAreThereNotifications() {
        boolean ongoing = mOngoing.hasVisibleItems();
        boolean latest = mLatest.hasVisibleItems();

        // (no ongoing notifications are clearable)
        if (mLatest.hasClearableItems()) {
            mClearButton.setVisibility(View.VISIBLE);
        } else {
            mClearButton.setVisibility(View.INVISIBLE);
        }

        mOngoingTitle.setVisibility(ongoing ? View.VISIBLE : View.GONE);
        mLatestTitle.setVisibility(latest ? View.VISIBLE : View.GONE);

        if (ongoing || latest) {
            mNoNotificationsTitle.setVisibility(View.GONE);
            mScrollView.setFocusable(true);
        } else {
            mNoNotificationsTitle.setVisibility(View.VISIBLE);
            mScrollView.setFocusable(false);
        }
    }


    /**
     * State is one or more of the DISABLE constants from StatusBarManager.
     */
    public void disable(int state) {
        final int old = mDisabled;
        final int diff = state ^ old;
        mDisabled = state;

        if ((diff & StatusBarManager.DISABLE_EXPAND) != 0) {
            if ((state & StatusBarManager.DISABLE_EXPAND) != 0) {
                if (SPEW) Slog.d(TAG, "DISABLE_EXPAND: yes");
                animateCollapse();
            }
        }
        if ((diff & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
            if ((state & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
                if (SPEW) Slog.d(TAG, "DISABLE_NOTIFICATION_ICONS: yes");
                if (mTicking) {
                    mTicker.halt();
                } else {
                    setNotificationIconVisibility(false, com.android.internal.R.anim.fade_out);
                }
            } else {
                if (SPEW) Slog.d(TAG, "DISABLE_NOTIFICATION_ICONS: no");
                if (!mExpandedVisible) {
                    setNotificationIconVisibility(true, com.android.internal.R.anim.fade_in);
                }
            }
        } else if ((diff & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
            if (mTicking && (state & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
                if (SPEW) Slog.d(TAG, "DISABLE_NOTIFICATION_TICKER: yes");
                mTicker.halt();
            }
        }
    }

    /**
     * All changes to the status bar and notifications funnel through here and are batched.
     */
    private class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_ANIMATE:
                    doAnimation();
                    break;
                case MSG_ANIMATE_REVEAL:
                    doRevealAnimation();
                    break;
            }
        }
    }

    View.OnFocusChangeListener mFocusChangeListener = new View.OnFocusChangeListener() {
        public void onFocusChange(View v, boolean hasFocus) {
            // Because 'v' is a ViewGroup, all its children will be (un)selected
            // too, which allows marqueeing to work.
            v.setSelected(hasFocus);
        }
    };

    private void makeExpandedVisible() {
        if (SPEW) Slog.d(TAG, "Make expanded visible: expanded visible=" + mExpandedVisible);
        if (mExpandedVisible) {
            return;
        }
        mExpandedVisible = true;
        visibilityChanged(true);
        mExpandedView.requestEpdMode(View.EPD_FULL);
        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);

        mExpandedParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        mExpandedParams.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        mExpandedDialog.getWindow().setAttributes(mExpandedParams);
        mExpandedView.requestFocus(View.FOCUS_FORWARD);

        mExpandedView.setVisibility(View.VISIBLE);

        setDateViewVisibility(true, com.android.internal.R.anim.fade_in);
    }

    void performExpand() {
        if (SPEW) Slog.d(TAG, "performExpand: mExpanded=" + mExpanded);
        if ((mDisabled & StatusBarManager.DISABLE_EXPAND) != 0) {
            return ;
        }
        if (mExpanded) {
            return;
        }

        mExpanded = true;
        makeExpandedVisible();
        updateExpandedViewPos(EXPANDED_FULL_OPEN);
        setLightRatingBarDefaultProgress();

        if (false) postStartTracing();
        if (mDev.getFrontLightValue() > 0) {
            mLightSwitch.setChecked(true);
        } else {
            mLightSwitch.setChecked(false);
        }
    }

    void performCollapse() {
        if (SPEW) Slog.d(TAG, "performCollapse: mExpanded=" + mExpanded
                + " mExpandedVisible=" + mExpandedVisible
                + " mTicking=" + mTicking);

        if (!mExpandedVisible) {
            return;
        }
        mExpandedVisible = false;
        visibilityChanged(false);
        mExpandedParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        mExpandedParams.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        mExpandedDialog.getWindow().setAttributes(mExpandedParams);

        if (mExpanded) {
            mExpandedView.requestFullWhenHidden();
        }
        mTrackingView.setVisibility(View.GONE);
        mExpandedView.setVisibility(View.GONE);
        if ((mDisabled & StatusBarManager.DISABLE_NOTIFICATION_ICONS) == 0) {
            setNotificationIconVisibility(true, com.android.internal.R.anim.fade_in);
        }

        if (!mExpanded) {
            return;
        }
        mExpanded = false;
    }

    boolean interceptTouchEvent(MotionEvent event) {
        if (SPEW) {
            Slog.d(TAG, "Touch: rawY=" + event.getRawY() + " event=" + event + " mDisabled="
                + mDisabled);
        }

        if ((mDisabled & StatusBarManager.DISABLE_EXPAND) != 0) {
            return false;
        }

        final int statusBarSize = mStatusBarView.getHeight();
        final int hitSize = statusBarSize*2;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            final int y = (int)event.getRawY();

            if (!mExpanded) {
                mViewDelta = statusBarSize - y;
            } else {
                mTrackingView.getLocationOnScreen(mAbsPos);
                mViewDelta = mAbsPos[1] + mTrackingView.getHeight() - y;
            }
            if ((!mExpanded && y < hitSize) ||
                    (mExpanded && y > (mDisplay.getHeight()-hitSize))) {

                // We drop events at the edge of the screen to make the windowshade come
                // down by accident less, especially when pushing open a device with a keyboard
                // that rotates (like g1 and droid)
                int x = (int)event.getRawX();
                final int edgeBorder = mEdgeBorder;
                if (x >= edgeBorder && x < mDisplay.getWidth() - edgeBorder) {
                    prepareTracking(y, !mExpanded);// opening if we're not already fully visible
                    mVelocityTracker.addMovement(event);
                }
            }

        } else if (mTracking) {

            mVelocityTracker.addMovement(event);
            final int minY = statusBarSize;
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                int y = (int)event.getRawY();
                if (mAnimatingReveal && y < minY) {
                    // nothing
                } else  {
                    mAnimatingReveal = false;
                    updateExpandedViewPos(EXPANDED_FULL_OPEN);
                    performExpand();
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP && !mExpanded) {
                updateExpandedViewPos(0);
                performCollapse();
            }

        }
        return false;
    }

    private class Launcher implements View.OnClickListener {
        private PendingIntent mIntent;
        private String mPkg;
        private String mTag;
        private int mId;

        Launcher(PendingIntent intent, String pkg, String tag, int id) {
            mIntent = intent;
            mPkg = pkg;
            mTag = tag;
            mId = id;
        }

        public void onClick(View v) {

            animateCollapse();

            try {
                // The intent we are sending is for the application, which
                // won't have permission to immediately start an activity after
                // the user switches to home.  We know it is safe to do at this
                // point, so make sure new activity switches are now allowed.
                ActivityManagerNative.getDefault().resumeAppSwitches();
            } catch (RemoteException e) {
            }

            if (mIntent != null) {
                int[] pos = new int[2];
                v.getLocationOnScreen(pos);
                Intent overlay = new Intent();
                overlay.setSourceBounds(
                        new Rect(pos[0], pos[1], pos[0]+v.getWidth(), pos[1]+v.getHeight()));
                try {
                    mIntent.send(StatusBarService.this, 0, overlay);
                } catch (PendingIntent.CanceledException e) {
                    // the stack trace isn't very helpful here.  Just log the exception message.
                    Slog.w(TAG, "Sending contentIntent failed: " + e);
                }
            }

            try {
                mBarService.onNotificationClick(mPkg, mTag, mId);
            } catch (RemoteException ex) {
                // system process is dead if we're here.
            }
        }
    }

    private void tick(StatusBarNotification n) {
        // Show the ticker if one is requested. Also don't do this
        // until status bar window is attached to the window manager,
        // because...  well, what's the point otherwise?  And trying to
        // run a ticker without being attached will crash!
        if (n.notification.tickerText != null && mStatusBarView.getWindowToken() != null) {
            if (0 == (mDisabled & (StatusBarManager.DISABLE_NOTIFICATION_ICONS
                            | StatusBarManager.DISABLE_NOTIFICATION_TICKER))) {
                mTicker.addEntry(n);
            }
        }
    }

    /**
     * Cancel this notification and tell the StatusBarManagerService / NotificationManagerService
     * about the failure.
     *
     * WARNING: this will call back into us.  Don't hold any locks.
     */
    void handleNotificationError(IBinder key, StatusBarNotification n, String message) {
        removeNotification(key);
        try {
            mBarService.onNotificationError(n.pkg, n.tag, n.id, n.uid, n.initialPid, message);
        } catch (RemoteException ex) {
            // The end is nigh.
        }
    }

    private class MyTicker extends Ticker {
        MyTicker(Context context, StatusBarView sb) {
            super(context, sb);
        }

        @Override
        void tickerStarting() {
            if (SPEW) Slog.d(TAG, "tickerStarting");
            mTicking = true;
            mIcons.setVisibility(View.GONE);
            mTickerView.setVisibility(View.VISIBLE);
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_up_in, null));
            mIcons.startAnimation(loadAnim(com.android.internal.R.anim.push_up_out, null));
        }

        @Override
        void tickerDone() {
            if (SPEW) Slog.d(TAG, "tickerDone");
            mTicking = false;
            mIcons.setVisibility(View.VISIBLE);
            mTickerView.setVisibility(View.GONE);
            mIcons.startAnimation(loadAnim(com.android.internal.R.anim.push_down_in, null));
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_down_out, null));
        }

        void tickerHalting() {
            if (SPEW) Slog.d(TAG, "tickerHalting");
            mTicking = false;
            mIcons.setVisibility(View.VISIBLE);
            mTickerView.setVisibility(View.GONE);
            mIcons.startAnimation(loadAnim(com.android.internal.R.anim.fade_in, null));
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.fade_out, null));
        }
    }

    public String viewInfo(View v) {
        return "(" + v.getLeft() + "," + v.getTop() + ")(" + v.getRight() + "," + v.getBottom()
                + " " + v.getWidth() + "x" + v.getHeight() + ")";
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump StatusBar from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        synchronized (mQueueLock) {
            pw.println("Current Status Bar state:");
            pw.println("  mExpanded=" + mExpanded
                    + ", mExpandedVisible=" + mExpandedVisible);
            pw.println("  mTicking=" + mTicking);
            pw.println("  mTracking=" + mTracking);
            pw.println("  mAnimating=" + mAnimating
                    + ", mAnimY=" + mAnimY + ", mAnimVel=" + mAnimVel
                    + ", mAnimAccel=" + mAnimAccel);
            pw.println("  mCurAnimationTime=" + mCurAnimationTime
                    + " mAnimLastTime=" + mAnimLastTime);
            pw.println("  mDisplayHeight=" + mDisplayHeight
                    + " mAnimatingReveal=" + mAnimatingReveal
                    + " mViewDelta=" + mViewDelta);
            pw.println("  mDisplayHeight=" + mDisplayHeight);
            pw.println("  mExpandedParams: " + mExpandedParams);
            pw.println("  mExpandedView: " + viewInfo(mExpandedView));
            pw.println("  mExpandedDialog: " + mExpandedDialog);
            pw.println("  mTrackingParams: " + mTrackingParams);
            pw.println("  mTrackingView: " + viewInfo(mTrackingView));
            pw.println("  mOngoingTitle: " + viewInfo(mOngoingTitle));
            pw.println("  mOngoingItems: " + viewInfo(mOngoingItems));
            pw.println("  mLatestTitle: " + viewInfo(mLatestTitle));
            pw.println("  mLatestItems: " + viewInfo(mLatestItems));
            pw.println("  mNoNotificationsTitle: " + viewInfo(mNoNotificationsTitle));
            pw.println("  mCloseView: " + viewInfo(mCloseView));
            pw.println("  mTickerView: " + viewInfo(mTickerView));
            pw.println("  mScrollView: " + viewInfo(mScrollView)
                    + " scroll " + mScrollView.getScrollX() + "," + mScrollView.getScrollY());
            pw.println("mNotificationLinearLayout: " + viewInfo(mNotificationLinearLayout));
        }

        if (true) {
            // must happen on ui thread
            mHandler.post(new Runnable() {
                    public void run() {
                        Slog.d(TAG, "mStatusIcons:");
                        mStatusIcons.debug();
                    }
                });
        }

    }

    void onBarViewAttached() {
        WindowManager.LayoutParams lp;
        int pixelFormat;
        Drawable bg;

        /// ---------- Tracking View --------------
        pixelFormat = PixelFormat.RGBX_8888;
        bg = mTrackingView.getBackground();
        if (bg != null) {
            pixelFormat = bg.getOpacity();
        }

        lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                pixelFormat);
        lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
        lp.setTitle("TrackingView");
        lp.y = mTrackingPosition;
        mTrackingParams = lp;
        WindowManagerImpl.getDefault().addView(mTrackingView, lp);
    }

    void onTrackingViewAttached() {
        WindowManager.LayoutParams lp;
        int pixelFormat;
        Drawable bg;

        /// ---------- Expanded View --------------
        pixelFormat = PixelFormat.TRANSLUCENT;

        final int disph = mDisplay.getHeight();
        lp = mExpandedDialog.getWindow().getAttributes();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = getExpandedHeight();
        lp.x = 0;
        mTrackingPosition = lp.y = -disph; // sufficiently large negative
        lp.type = WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
        lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_DITHER
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.format = pixelFormat;
        lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
        lp.setTitle("StatusBarExpanded");
        mExpandedDialog.getWindow().setAttributes(lp);
        mExpandedDialog.getWindow().setFormat(pixelFormat);
        mExpandedParams = lp;

        mExpandedDialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        mExpandedDialog.setContentView(mExpandedView,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                           ViewGroup.LayoutParams.MATCH_PARENT));
        mExpandedDialog.getWindow().setBackgroundDrawable(null);
        mExpandedDialog.show();
        FrameLayout hack = (FrameLayout)mExpandedView.getParent();
    }

    void setDateViewVisibility(boolean visible, int anim) {
        mDateView.setUpdates(visible);
        mDateView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    void setNotificationIconVisibility(boolean visible, int anim) {
        int old = mNotificationIcons.getVisibility();
        int v = visible ? View.VISIBLE : View.INVISIBLE;
        if (old != v) {
            mNotificationIcons.setVisibility(v);
            mNotificationIcons.startAnimation(loadAnim(anim, null));
        }
    }

    int getExpandedHeight() {
        return mDisplay.getHeight() - mStatusBarView.getHeight();
    }

    void updateExpandedHeight() {
        if (mExpandedView != null) {
            mExpandedParams.height = getExpandedHeight();
            mExpandedDialog.getWindow().setAttributes(mExpandedParams);
        }
    }

    /**
     * The LEDs are turned o)ff when the notification panel is shown, even just a little bit.
     * This was added last-minute and is inconsistent with the way the rest of the notifications
     * are handled, because the notification isn't really cancelled.  The lights are just
     * turned off.  If any other notifications happen, the lights will turn back on.  Steve says
     * this is what he wants. (see bug 1131461)
     */
    void visibilityChanged(boolean visible) {
        if (mPanelSlightlyVisible != visible) {
            mPanelSlightlyVisible = visible;
            try {
                mBarService.onPanelRevealed();
            } catch (RemoteException ex) {
                // Won't fail unless the world has ended.
            }
        }
    }

    void performDisableActions(int net) {
        int old = mDisabled;
        int diff = net ^ old;
        mDisabled = net;

        // act accordingly
        if ((diff & StatusBarManager.DISABLE_EXPAND) != 0) {
            if ((net & StatusBarManager.DISABLE_EXPAND) != 0) {
                Slog.d(TAG, "DISABLE_EXPAND: yes");
            }
        }
        if ((diff & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
            if ((net & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
                Slog.d(TAG, "DISABLE_NOTIFICATION_ICONS: yes");
                if (mTicking) {
                    mNotificationIcons.setVisibility(View.INVISIBLE);
                    mTicker.halt();
                } else {
                    setNotificationIconVisibility(false, com.android.internal.R.anim.fade_out);
                }
            } else {
                Slog.d(TAG, "DISABLE_NOTIFICATION_ICONS: no");
                if (!mExpandedVisible) {
                    setNotificationIconVisibility(true, com.android.internal.R.anim.fade_in);
                }
            }
        } else if ((diff & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
            Slog.d(TAG, "DISABLE_NOTIFICATION_TICKER: "
                + (((net & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0)
                    ? "yes" : "no"));
            if (mTicking && (net & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
                mTicker.halt();
            }
        }
    }

    private View.OnClickListener mClearButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            try {
                mBarService.onClearAllNotifications();
            } catch (RemoteException ex) {
                // system process is dead if we're here.
            }
            animateCollapse();
        }
    };

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();
            if (Intent.ACTION_HOME_MENU.equals(action) && !isHomeMenuDialogShow) {
                createHomeMenuDialog();
                mHomeMenuDialog.show();
                int menu_width = (int)(getResources().getDimension(R.dimen.menu_width));
                int menu_height = (int)(getResources().getDimension(R.dimen.menu_height));
                mHomeMenuDialog.setContentView(homeMenuDialogView,
                    new ViewGroup.LayoutParams(menu_width, menu_height));
            }

            if (Intent.ACTION_CHANGE_LIGHT_STATE.equals(action)) {
                if (mDev.getFrontLightValue() > 0) {
                    mDev.closeFrontLight();
                    mLightSwitch.setChecked(false);
                } else {
                    mDev.openFrontLight();
                    mLightSwitch.setChecked(true);
                }
            }

            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)
                    || Intent.ACTION_CLOSE_STATUSBAR_USB.equals(action)) {
                animateCollapse();
            }
            else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                updateResources();
            }
            else if (intent.getAction().equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                if (WifiManager.WIFI_STATE_DISABLED ==
                        intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                           WifiManager.WIFI_STATE_DISABLED)) {
                    mWifiSwitch.setChecked(false);
                    mWifiSwitch.setClickable(true);
                }
                else if (WifiManager.WIFI_STATE_ENABLED ==
                        intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                           WifiManager.WIFI_STATE_DISABLED)) {
                    mWifiSwitch.setClickable(true);
                    mWifiSwitch.setChecked(true);
                }
                else if (WifiManager.WIFI_STATE_ENABLING ==
                        intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                           WifiManager.WIFI_STATE_DISABLED)
                     || WifiManager.WIFI_STATE_DISABLING ==
                        intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                           WifiManager.WIFI_STATE_DISABLED)
                     || WifiManager.WIFI_STATE_UNKNOWN ==
                        intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                           WifiManager.WIFI_STATE_DISABLED)) {
                    mWifiSwitch.setClickable(false);
                    mWifiSwitch.setChecked(false);
                }
            }
        }
    };

    /**
     * Reload some of our resources when the configuration changes.
     *
     * We don't reload everything when the configuration changes -- we probably
     * should, but getting that smooth is tough.  Someday we'll fix that.  In the
     * meantime, just update the things that we know change.
     */
    void updateResources() {
        Resources res = getResources();

        mClearButton.setText(getText(R.string.status_bar_clear_all_button));
        mOngoingTitle.setText(getText(R.string.status_bar_ongoing_events_title));
        mLatestTitle.setText(getText(R.string.status_bar_latest_events_title));
        mNoNotificationsTitle.setText(getText(R.string.status_bar_no_notifications_title));

        mEdgeBorder = res.getDimensionPixelSize(R.dimen.status_bar_edge_ignore);

        if (false) Slog.v(TAG, "updateResources");
    }

    //
    // tracing
    //

    void postStartTracing() {
        mHandler.postDelayed(mStartTracing, 3000);
    }

    void vibrate() {
    }

    Runnable mStartTracing = new Runnable() {
        public void run() {
            vibrate();
            SystemClock.sleep(250);
            Slog.d(TAG, "startTracing");
            android.os.Debug.startMethodTracing("/data/statusbar-traces/trace");
            mHandler.postDelayed(mStopTracing, 10000);
        }
    };

    Runnable mStopTracing = new Runnable() {
        public void run() {
            android.os.Debug.stopMethodTracing();
            Slog.d(TAG, "stopTracing");
            vibrate();
        }
    };

    public void animateCollapse() {
        if (!mExpandedVisible) {
            return;
        }
        int y;
        if (mAnimating) {
            y = (int) mAnimY;
        } else {
            y = mDisplay.getHeight() - 1;
        }
        mExpanded = true;
        prepareTracking(y, false);
        performCollapse();
    }

    public void animateExpand() {
        if ((mDisabled & 1) != 0) {
            return;
        }
        if (mExpanded) {
            return;
        }
        prepareTracking(0, true);
        performFling(0, 2000, true);
    }

    private void createHomeMenuDialog() {
        LayoutInflater inflater = LayoutInflater.from(mStatusBarView.getContext());
        mHomeMenuDialog = null;
        homeMenuDialogView = null;
        homeMenuDialogView = inflater.inflate(R.layout.home_menu_dialog, null);
        mHomeMenuDialog = new AlertDialog.Builder(mStatusBarView.getContext()).create();
        mHomeMenuDialog.setInverseBackgroundForced(true);
        mHomeMenuDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);

        if (!mDev.hasFrontLight()) {
            Log.i("jim_log", "does not have moon light");
            ((LinearLayout) homeMenuDialogView.findViewById(R.id.linearlayout_moonlight))
                .setVisibility(View.INVISIBLE);
            ((LinearLayout) homeMenuDialogView.findViewById(R.id.linearlayout_setting))
                .setVisibility(View.VISIBLE);
        }

        mHomeMenuDialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                currentKeyCode = keyCode;
                switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    mHomeMenuDialog.dismiss();
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (mDev.hasFrontLight()) {
                        Intent intent = new Intent(Intent.ACTION_CHANGE_LIGHT_STATE);
                        mStatusBarView.getContext().sendOrderedBroadcast(intent, null);
                    } else {
                        Intent intent = new Intent();
                        intent.setClassName(
                            "com.onyx.android.launcher",
                            "com.onyx.android.launcher.SettingsActivity");
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    }
                    mHomeMenuDialog.dismiss();
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    mHomeMenuDialog.dismiss();
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    mStatusBarView.sendKeyEvent(KeyEvent.KEYCODE_HOME, 102, true);
                    mStatusBarView.sendKeyEvent(KeyEvent.KEYCODE_HOME, 102, false);
                    mHomeMenuDialog.dismiss();
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    performExpand();
                    mHomeMenuDialog.dismiss();
                    break;
                case KeyEvent.KEYCODE_BACK:
                    mHomeMenuDialog.dismiss();
                    break;
                }
                return true;
            }
        });
        mHomeMenuDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(final DialogInterface dialog) {
                isHomeMenuDialogShow = false;
                if (currentKeyCode == 21) {
                    mStatusBarView.sendKeyEvent(KeyEvent.KEYCODE_MENU, 59, true);
                    mStatusBarView.sendKeyEvent(KeyEvent.KEYCODE_MENU, 59, false);
                }
            }
        });
        mHomeMenuDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                isHomeMenuDialogShow = true;
                homeMenuDialogView.requestEpdMode(View.EPD_FULL);
            }
        });
    }

    private Animation loadAnim(int id, Animation.AnimationListener listener) {
        Animation anim = AnimationUtils.loadAnimation(StatusBarService.this, id);
        if (listener != null) {
            anim.setAnimationListener(listener);
        }
        return anim;
    }

    void doAnimation() {
        if (mAnimating) {
            if (SPEW) Slog.d(TAG, "doAnimation");
            if (SPEW) Slog.d(TAG, "doAnimation before mAnimY=" + mAnimY);
            incrementAnim();
            if (SPEW) Slog.d(TAG, "doAnimation after  mAnimY=" + mAnimY);
            if (mAnimY >= mDisplay.getHeight()-1) {
                if (SPEW) Slog.d(TAG, "Animation completed to expanded state.");
                mAnimating = false;
                updateExpandedViewPos(EXPANDED_FULL_OPEN);
                performExpand();
            }
            else if (mAnimY < mStatusBarView.getHeight()) {
                if (SPEW) Slog.d(TAG, "Animation completed to collapsed state.");
                mAnimating = false;
                updateExpandedViewPos(0);
                performCollapse();
            }
            else {
                updateExpandedViewPos(EXPANDED_FULL_OPEN);
                mCurAnimationTime += ANIM_FRAME_DURATION;
                mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE), mCurAnimationTime);
            }
        }
    }

    void incrementAnim() {
        long now = SystemClock.uptimeMillis();
        float t = ((float)(now - mAnimLastTime)) / 1000;            // ms -> s
        final float y = mAnimY;
        final float v = mAnimVel;                                   // px/s
        final float a = mAnimAccel;                                 // px/s/s
        mAnimY = y + (v*t) + (0.5f*a*t*t);                          // px
        mAnimVel = v + (a*t);                                       // px/s
        mAnimLastTime = now;                                        // ms
        //Slog.d(TAG, "y=" + y + " v=" + v + " a=" + a + " t=" + t + " mAnimY=" + mAnimY
        //        + " mAnimAccel=" + mAnimAccel);
    }

    void doRevealAnimation() {
        final int h = mCloseView.getHeight() + mStatusBarView.getHeight();
        if (mAnimatingReveal && mAnimating && mAnimY < h) {
            incrementAnim();
            if (mAnimY >= h) {
                mAnimY = h;
                updateExpandedViewPos((int)mAnimY);
            } else {
                updateExpandedViewPos((int)mAnimY);
                mCurAnimationTime += ANIM_FRAME_DURATION;
                mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE_REVEAL),
                        mCurAnimationTime);
            }
        }
    }

    void performFling(int y, float vel, boolean always) {
        mAnimatingReveal = false;
        mDisplayHeight = mDisplay.getHeight();

        mAnimY = y;
        mAnimVel = vel;

        //Slog.d(TAG, "starting with mAnimY=" + mAnimY + " mAnimVel=" + mAnimVel);

        if (mExpanded) {
            if (!always && (
                    vel > 200.0f
                    || (y > (mDisplayHeight-25) && vel > -200.0f))) {
                // We are expanded, but they didn't move sufficiently to cause
                // us to retract.  Animate back to the expanded position.
                mAnimAccel = 2000.0f;
                if (vel < 0) {
                    mAnimVel = 0;
                }
            }
            else {
                // We are expanded and are now going to animate away.
                mAnimAccel = -2000.0f;
                if (vel > 0) {
                    mAnimVel = 0;
                }
            }
        } else {
            if (always || (
                    vel > 200.0f
                    || (y > (mDisplayHeight/2) && vel > -200.0f))) {
                // We are collapsed, and they moved enough to allow us to
                // expand.  Animate in the notifications.
                mAnimAccel = 2000.0f;
                if (vel < 0) {
                    mAnimVel = 0;
                }
            }
            else {
                // We are collapsed, but they didn't move sufficiently to cause
                // us to retract.  Animate back to the collapsed position.
                mAnimAccel = -2000.0f;
                if (vel > 0) {
                    mAnimVel = 0;
                }
            }
        }
        //Slog.d(TAG, "mAnimY=" + mAnimY + " mAnimVel=" + mAnimVel
        //        + " mAnimAccel=" + mAnimAccel);

        long now = SystemClock.uptimeMillis();
        mAnimLastTime = now;
        mCurAnimationTime = now + ANIM_FRAME_DURATION;
        mAnimating = true;
        mHandler.removeMessages(MSG_ANIMATE);
        mHandler.removeMessages(MSG_ANIMATE_REVEAL);
        mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE), mCurAnimationTime);
        stopTracking();
    }

    void prepareTracking(int y, boolean opening) {
        mTracking = true;
        mVelocityTracker = VelocityTracker.obtain();
        if (opening) {
            mAnimAccel = 2000.0f;
            mAnimVel = 200;
            mAnimY = mStatusBarView.getHeight();
            mAnimating = true;
            mAnimatingReveal = true;
            mHandler.removeMessages(MSG_ANIMATE);
            mHandler.removeMessages(MSG_ANIMATE_REVEAL);
            long now = SystemClock.uptimeMillis();
            mAnimLastTime = now;
            mCurAnimationTime = now + ANIM_FRAME_DURATION;
            mAnimating = true;
        } else {
            // it's open, close it?
            if (mAnimating) {
                mAnimating = false;
                mHandler.removeMessages(MSG_ANIMATE);
            }
            updateExpandedViewPos(0);
        }
    }

    void stopTracking() {
        mTracking = false;
        mVelocityTracker.recycle();
        mVelocityTracker = null;
    }

    void updateExpandedViewPos(int expandedPosition) {
        if (SPEW) {
            Slog.d(TAG, "updateExpandedViewPos before expandedPosition=" + expandedPosition
                    + " mTrackingParams.y="
                    + ((mTrackingParams == null) ? "???" : mTrackingParams.y)
                    + " mTrackingPosition=" + mTrackingPosition);
        }

        int h = mStatusBarView.getHeight();
        int disph = mDisplay.getHeight();

        // If the expanded view is not visible, make sure they're still off screen.
        // Maybe the view was resized.
        if (!mExpandedVisible) {
            if (mTrackingView != null) {
                mTrackingPosition = -disph;
                if (mTrackingParams != null) {
                    mTrackingParams.y = mTrackingPosition;
                    WindowManagerImpl.getDefault().updateViewLayout(mTrackingView, mTrackingParams);
                }
            }
            if (mExpandedParams != null) {
                mExpandedParams.y = -disph;
                mExpandedDialog.getWindow().setAttributes(mExpandedParams);
            }
            return;
        }

        // tracking view...
        int pos;
        if (expandedPosition == EXPANDED_FULL_OPEN) {
            pos = h;
        }
        else if (expandedPosition == EXPANDED_LEAVE_ALONE) {
            pos = mTrackingPosition;
        }
        else {
            if (expandedPosition <= disph) {
                pos = expandedPosition;
            } else {
                pos = disph;
            }
            pos -= disph-h;
        }
        mTrackingPosition = mTrackingParams.y = pos;
        mTrackingParams.height = disph-h;
        WindowManagerImpl.getDefault().updateViewLayout(mTrackingView, mTrackingParams);

        if (mExpandedParams != null) {
            mCloseView.getLocationInWindow(mPositionTmp);
            final int closePos = mPositionTmp[1];

            mExpandedContents.getLocationInWindow(mPositionTmp);
            final int contentsBottom = mPositionTmp[1] + mExpandedContents.getHeight();

            if (expandedPosition != EXPANDED_LEAVE_ALONE) {
                mExpandedParams.y = pos + mTrackingView.getHeight()
                        - (mTrackingParams.height-closePos) - contentsBottom;
                int max = h;
                if (mExpandedParams.y > max) {
                    mExpandedParams.y = max;
                }
                int min = mTrackingPosition;
                if (mExpandedParams.y < min) {
                    mExpandedParams.y = min;
                }

                boolean visible = (mTrackingPosition + mTrackingView.getHeight()) > h;
                if (!visible) {
                    // if the contents aren't visible, move the expanded view way off screen
                    // because the window itself extends below the content view.
                    mExpandedParams.y = -disph;
                }
                mExpandedDialog.getWindow().setAttributes(mExpandedParams);

                if (SPEW) Slog.d(TAG, "updateExpandedViewPos visibilityChanged(" + visible + ")");
                visibilityChanged(visible);
            }
        }

        if (SPEW) {
            Slog.d(TAG, "updateExpandedViewPos after  expandedPosition=" + expandedPosition
                    + " mTrackingParams.y=" + mTrackingParams.y
                    + " mTrackingPosition=" + mTrackingPosition
                    + " mExpandedParams.y=" + mExpandedParams.y
                    + " mExpandedParams.height=" + mExpandedParams.height);
        }
    }

    private int getBrightnessOfRating(int value) {
        if (value <= 10)
            return (value * 2) + 0;
        int big_interval = (255 - LOW_BRIGHTNESS_MAX)
                         / (mRatingBarLightSettings.getNumStars() - 10);
        return ((value - 10) * big_interval) + LOW_BRIGHTNESS_MAX;
    }

    private void setLightRatingBarDefaultProgress() {
        int value;
        try {
            value = Settings.System.getInt(mStatusBarView.getContext().getContentResolver(),
                 Settings.System.SCREEN_BRIGHTNESS);
        } catch (Settings.SettingNotFoundException snfe) {
            value = DeviceController.BRIGHTNESS_DEFAULT;
        }
        int index = getIndex(value);
        mRatingBarLightSettings.setProgress(index);
    }

    private void setLightRatingBarProgress() {
        int brightness = getBrightnessOfRating(
                mRatingBarLightSettings.getProgress());
        Settings.System.putInt(
                mStatusBarView.getContext().getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS,
                brightness);
        mDev.setFrontLightValue(brightness);
        if (mRatingBarLightSettings.getProgress() == 0) {
            mLightSwitch.setChecked(false);
        } else {
            mLightSwitch.setChecked(true);
        }
    }

    private void setFrontLightValue() {
        if (mLightSteps.size() <= 0) return;
        int value = ((Integer)(mLightSteps.get(mRatingBarLightSettings
                                          .getProgress()))).intValue();
        mDev.setFrontLightValue(value);
        setFrontLightConfigValue(mContext, value);
    }

    public boolean setFrontLightConfigValue(Context context, int value) {
        return Settings.System.putInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, value);
    }

    private void updateLightSwitch() {
        if (mLightSwitch != null) {
            if (mRatingBarLightSettings.getProgress() != 0 && !mLightSwitch.isChecked()) {
                mLightSwitch.setChecked(true);
            } else if (mRatingBarLightSettings.getProgress() == 0 && mLightSwitch.isChecked()) {
                mLightSwitch.setChecked(false);
            }
        }
    }
}
