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
import android.app.Dialog;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.text.format.DateFormat;
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
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.FrameLayout;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Set;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.StatusBarPolicy;



public class StatusBarService extends Service implements CommandQueue.Callbacks {
    static final String TAG = "StatusBarService";
    static final boolean SPEW_ICONS = false;
    static final boolean SPEW = false;

    public static final String ACTION_STATUSBAR_START
            = "com.android.internal.policy.statusbar.START";

    static final int EXPANDED_LEAVE_ALONE = -10000;
    static final int EXPANDED_FULL_OPEN = -10001;

    StatusBarPolicy mIconPolicy;

    CommandQueue mCommandQueue;
    IStatusBarService mBarService;

    int mIconSize;
    Display mDisplay;
    StatusBarView mStatusBarView;
    int mPixelFormat;
    Handler mHandler = new Handler();
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
    boolean mExpanded;
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
    boolean mTrackingExpanded;

    static final int ANIM_FRAME_DURATION = (1000/60);
    float mDisplayHeight;
    int[] mAbsPos = new int[2];

    // for disabling the status bar
    int mDisabled = StatusBarManager.DISABLE_NOTIFICATION_ICONS;

    private Context mContext;
    private Calendar mCalendar;
    TextView mDate_s;

    private class ExpandedDialog extends Dialog {
        ExpandedDialog(Context context) {
            super(context, com.android.internal.R.style.Theme_Light_NoTitleBar);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    performCollapse();
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
    }

    @Override
    public void onDestroy() {
        // we're never destroyed
    }

    /**
     * Nobody binds to us.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ================================================================================
    // Constructing the view
    // ================================================================================
    private void makeStatusBarView(Context context) {
        mContext = context;
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

        mStatusBarView = sb;
        mStatusIcons = (LinearLayout)sb.findViewById(R.id.statusIcons);
        mNotificationIcons = (IconMerger)sb.findViewById(R.id.notificationIcons);
        mIcons = (LinearLayout)sb.findViewById(R.id.icons);
        mTickerView = sb.findViewById(R.id.ticker);

        mExpandedDialog = new ExpandedDialog(context);
        mExpandedView = expanded;
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

        mOngoingTitle.setVisibility(View.GONE);
        mLatestTitle.setVisibility(View.GONE);

        mTicker = new MyTicker(context, sb);

        TickerView tickerView = (TickerView)sb.findViewById(R.id.tickerText);
        tickerView.mTicker = mTicker;

        mEdgeBorder = res.getDimensionPixelSize(R.dimen.status_bar_edge_ignore);

        // set the inital view visibility
        setAreThereNotifications();

        mDate_s = (TextView)sb.findViewById(R.id.date_s);
        updateClock();

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_CLOSE_STATUSBAR_USB);
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        context.registerReceiver(mBroadcastReceiver, filter);
    }

    protected void addStatusBarView() {
        Resources res = getResources();
        final int height;
        String statusBarOne = SystemProperties.get("ro.caration.statusbar_one");
        if ("1".equals(statusBarOne)) {
            height = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        } else {
            height = 34;
        }

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

    private final void updateClock() {
        mCalendar = Calendar.getInstance();
        mCalendar.setTimeInMillis(System.currentTimeMillis());
        String str = DateFormat.getDateFormat(mContext).format(mCalendar.getTime());
        mDate_s.setText(str + "  ");
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
    }

    public void removeNotification(IBinder key) {
        if (SPEW) Slog.d(TAG, "removeNotification key=" + key);
        StatusBarNotification old = removeNotificationViews(key);

        if (old != null) {
            // Cancel the ticker if it's still running
            mTicker.removeEntry(old);

            // Recalculate the position of the sliding windows and the titles.
            setAreThereNotifications();
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
        } else {
            mNoNotificationsTitle.setVisibility(View.VISIBLE);
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
                performCollapse();
            }
        }
        if ((diff & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
            if ((state & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
                if (SPEW) Slog.d(TAG, "DISABLE_NOTIFICATION_ICONS: yes");
                if (mTicking) {
                    mTicker.halt();
                } else {
                    setNotificationIconVisibility(false);
                }
            } else {
                if (SPEW) Slog.d(TAG, "DISABLE_NOTIFICATION_ICONS: no");
                if (!mExpandedVisible) {
                    setNotificationIconVisibility(true);
                }
            }
        } else if ((diff & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
            if (mTicking && (state & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
                if (SPEW) Slog.d(TAG, "DISABLE_NOTIFICATION_TICKER: yes");
                mTicker.halt();
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

        mExpandedParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        mExpandedParams.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        mExpandedDialog.getWindow().setAttributes(mExpandedParams);
        mExpandedView.requestFocus(View.FOCUS_FORWARD);

        mExpandedDialog.show();
        mExpandedView.requestFullWhenShown();

        setDateViewVisibility(true);
    }

    public void performExpand() {
        if (SPEW) Slog.d(TAG, "performExpand: mExpanded=" + mExpanded);
        if ((mDisabled & StatusBarManager.DISABLE_EXPAND) != 0) {
            return ;
        }
        if (mExpanded) {
            return;
        }

        mExpanded = true;
        makeExpandedVisible();

        if (false) postStartTracing();
    }

    public void performCollapse() {
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
        mExpandedDialog.hide();

        if ((mDisabled & StatusBarManager.DISABLE_NOTIFICATION_ICONS) == 0) {
            setNotificationIconVisibility(true);
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
        final int bottom = mNotificationLinearLayout.getBottom();
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            final int y = (int)event.getRawY();
            if ((!mExpanded && y < hitSize) ||
                    (mExpanded && y > bottom)) {

                // We drop events at the edge of the screen to make the windowshade come
                // down by accident less, especially when pushing open a device with a keyboard
                // that rotates (like g1 and droid)
                int x = (int)event.getRawX();
                int edgeBorder = mEdgeBorder;
                if (x >= edgeBorder && x < mDisplay.getWidth() - edgeBorder) {
                    mTracking = true;
                    mTrackingExpanded = mExpanded;
                }
            }

        } else if (mTracking) {
            final int minY = statusBarSize;
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                int y = (int)event.getRawY();
                if (y >= minY) {
                    performExpand();
                    mTracking = false;
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP ||
                       event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (mTrackingExpanded)
                    performCollapse();
                mTracking = false;
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

            performCollapse();

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
        }

        @Override
        void tickerDone() {
            if (SPEW) Slog.d(TAG, "tickerDone");
            mTicking = false;
            mIcons.setVisibility(View.VISIBLE);
            mTickerView.setVisibility(View.GONE);
        }

        void tickerHalting() {
            if (SPEW) Slog.d(TAG, "tickerHalting");
            mTicking = false;
            mIcons.setVisibility(View.VISIBLE);
            mTickerView.setVisibility(View.GONE);
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
            pw.println("  mDisplayHeight=" + mDisplayHeight);
            pw.println("  mExpandedParams: " + mExpandedParams);
            pw.println("  mExpandedView: " + viewInfo(mExpandedView));
            pw.println("  mExpandedDialog: " + mExpandedDialog);
            pw.println("  mOngoingTitle: " + viewInfo(mOngoingTitle));
            pw.println("  mOngoingItems: " + viewInfo(mOngoingItems));
            pw.println("  mLatestTitle: " + viewInfo(mLatestTitle));
            pw.println("  mLatestItems: " + viewInfo(mLatestItems));
            pw.println("  mNoNotificationsTitle: " + viewInfo(mNoNotificationsTitle));
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
        final int disph = mDisplay.getHeight();
        lp = mExpandedDialog.getWindow().getAttributes();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = getExpandedHeight();
        lp.x = 0;
        lp.y = 0;
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
    }

    void onTrackingViewAttached() {
    }

    void setDateViewVisibility(boolean visible) {
    }

    void setNotificationIconVisibility(boolean visible) {
        int old = mNotificationIcons.getVisibility();
        int v = visible ? View.VISIBLE : View.INVISIBLE;
        if (old != v) {
            mNotificationIcons.setVisibility(v);
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
                    setNotificationIconVisibility(false);
                }
            } else {
                Slog.d(TAG, "DISABLE_NOTIFICATION_ICONS: no");
                if (!mExpandedVisible) {
                    setNotificationIconVisibility(true);
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
        }
    };

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)
                    || Intent.ACTION_CLOSE_STATUSBAR_USB.equals(action)) {
                performCollapse();
            }
            else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                updateResources();
                updateClock();
            }
            else if (action.equals(Intent.ACTION_TIME_TICK) ||
                     action.equals(Intent.ACTION_TIME_CHANGED)) {
                updateClock();
            }
            else if (action.equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra("time-zone");
                mCalendar = Calendar.getInstance();
                updateClock();
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
}
