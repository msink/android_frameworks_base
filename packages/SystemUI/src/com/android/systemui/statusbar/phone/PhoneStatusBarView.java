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

package com.android.systemui.statusbar.phone;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.hardware.DeviceController;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Build;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.ImageView;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.Clock;

import java.util.Timer;
import java.util.TimerTask;

public class PhoneStatusBarView extends PanelBar {
    private static final String TAG = "PhoneStatusBarView";
    private static final boolean DEBUG = PhoneStatusBar.DEBUG;

    PhoneStatusBar mBar;
    int mScrimColor;
    float mSettingsPanelDragzoneFrac;
    float mSettingsPanelDragzoneMin;

    boolean mFullWidthNotifications;
    PanelView mFadingPanel = null;
    PanelView mLastFullyOpenedPanel = null;
    PanelView mNotificationPanel, mSettingsPanel;
    private boolean mShouldFade;

    Button mbut_home;
    Button mbut_menu;
    ImageView line_image;
    ImageView mbut_sync;
    ImageView mbut_a2mode;

    private long delay;
    private Timer timer = null;
    private TimerTask mTask = null;

    public static boolean isA2Mode = false;

    private Runnable mForceEpdA2Runnable = new Runnable() {
        public void run() {
            forceEpdA2(true);
            requestEpdMode(View.EINK_MODE.EPD_A2, true);
        }
    };

    private Handler mHandler = new Handler();

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        Resources res = getContext().getResources();
        mScrimColor = res.getColor(R.color.notification_panel_scrim_color);
        mSettingsPanelDragzoneMin = res.getDimension(R.dimen.settings_panel_dragzone_min);
        try {
            mSettingsPanelDragzoneFrac = res.getFraction(R.dimen.settings_panel_dragzone_fraction, 1, 1);
        } catch (NotFoundException ex) {
            mSettingsPanelDragzoneFrac = 0f;
        }
        mFullWidthNotifications = mSettingsPanelDragzoneFrac <= 0f;
    }

    private void updateUI() {
        DeviceController ctrl = new DeviceController(getContext());
        if (ctrl.isTouchable()) {
            mbut_sync.setVisibility(View.VISIBLE);
            mbut_menu.setVisibility(View.VISIBLE);
            line_image.setVisibility(View.VISIBLE);
            mbut_a2mode.setVisibility(View.VISIBLE);
            mbut_home.setVisibility(View.VISIBLE);
        } else {
            mbut_sync.setVisibility(View.GONE);
            mbut_menu.setVisibility(View.GONE);
            line_image.setVisibility(View.GONE);
            mbut_a2mode.setVisibility(View.GONE);
            mbut_home.setVisibility(View.GONE);
        }
    }

    protected void onFinishInflate() {
        super.onFinishInflate();

        mbut_home = (Button) findViewById(R.id.status_bar_home);
        mbut_menu = (Button) findViewById(R.id.status_bar_menu);
        mbut_sync = (ImageView) findViewById(R.id.status_bar_sync);
        line_image = (ImageView) findViewById(R.id.line_status_bar);
        Clock clock = (Clock) findViewById(R.id.clock);
        mbut_a2mode = (ImageView) findViewById(R.id.status_bar_a2);

        updateUI();

        mbut_home.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sendKeyEvent(KeyEvent.KEYCODE_HOME);
            }
        });

        clock.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!mBar.mExpandedVisible) {
                    mBar.animateExpandNotificationsPanel();
                } else {
                    mBar.makeExpandedInvisible();
                }
            }
        });

        mbut_menu.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (mBar.mExpandedVisible) {
                    mBar.makeExpandedInvisible();
                    delay = 1000;
                } else {
                    delay = 0;
                }
                sendMenuKeyEvent();
            }
        });

        if (Build.BRAND.equalsIgnoreCase("Tagus")) {
            mbut_a2mode.setVisibility(View.GONE);
            mbut_sync.setVisibility(View.VISIBLE);
        } else {
            mbut_sync.setVisibility(View.GONE);
        }

        mbut_sync.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (mBar.mExpandedVisible) {
                    mBar.makeExpandedInvisible();
                }
                Intent intent = new android.content.Intent();
                intent.setClassName("com.onyx.android.bookstore",
                    "com.onyx.android.bookstore.ui.Bookshelf2Activity");
                Bundle bundle = new Bundle();
                bundle.putString("listing_type", "CLOUD");
                intent.putExtras(bundle);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
            }
        });

        mbut_a2mode.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!isA2Mode) {
                    isA2Mode = true;
                    mbut_a2mode.setImageResource(R.drawable.refresh_a2);
                    mHandler.postDelayed(mForceEpdA2Runnable, 500);
                } else {
                    isA2Mode = false;
                    mbut_a2mode.setImageResource(R.drawable.refresh);
                    forceEpdA2(false);
                    requestEpdMode(View.EINK_MODE.EPD_NULL, true);
                }
                invalidate();
            }
        });
    }

    private void sendMenuKeyEvent() {
        if (mTask != null) {
            mTask.cancel();
        }
        mTask = new TimerTask() {
            public void run() {
                sendKeyEvent(KeyEvent.KEYCODE_MENU);
            }
        };
        timer = new Timer(true);
        timer.schedule(mTask, delay);
    }

    private void sendKeyEvent(int keycode) {
        long now = android.os.SystemClock.uptimeMillis();
        injectKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                keycode, 0, 0, -1, 0, 0, 0x101));
        injectKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP,
                keycode, 0, 0, -1, 0, 0, 0x101));
    }

    private void injectKeyEvent(KeyEvent event) {
        Log.i(TAG, "injectKeyEvent: " + event);
        InputManager.getInstance().injectInputEvent(event, 2);
    }

    public void setBar(PhoneStatusBar bar) {
        mBar = bar;
    }

    public boolean hasFullWidthNotifications() {
        return mFullWidthNotifications;
    }

    @Override
    public void onAttachedToWindow() {
        for (PanelView pv : mPanels) {
            pv.setRubberbandingEnabled(!mFullWidthNotifications);
        }
    }

    @Override
    public void addPanel(PanelView pv) {
        super.addPanel(pv);
        if (pv.getId() == R.id.notification_panel) {
            mNotificationPanel = pv;
        } else if (pv.getId() == R.id.settings_panel){
            mSettingsPanel = pv;
        }
        pv.setRubberbandingEnabled(!mFullWidthNotifications);
    }

    @Override
    public boolean panelsEnabled() {
        return ((mBar.mDisabled & StatusBarManager.DISABLE_EXPAND) == 0);
    }

    @Override
    public boolean onRequestSendAccessibilityEvent(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEvent(child, event)) {
            // The status bar is very small so augment the view that the user is touching
            // with the content of the status bar a whole. This way an accessibility service
            // may announce the current item as well as the entire content if appropriate.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }

    @Override
    public PanelView selectPanelForTouch(MotionEvent touch) {
        final float x = touch.getX();

        if (mFullWidthNotifications) {
            // No double swiping. If either panel is open, nothing else can be pulled down.
            return ((mSettingsPanel == null ? 0 : mSettingsPanel.getExpandedHeight()) 
                        + mNotificationPanel.getExpandedHeight() > 0) 
                    ? null 
                    : mNotificationPanel;
        }

        // We split the status bar into thirds: the left 2/3 are for notifications, and the
        // right 1/3 for quick settings. If you pull the status bar down a second time you'll
        // toggle panels no matter where you pull it down.

        final float w = getMeasuredWidth();
        float region = (w * mSettingsPanelDragzoneFrac);

        if (DEBUG) {
            Slog.v(TAG, String.format(
                "w=%.1f frac=%.3f region=%.1f min=%.1f x=%.1f w-x=%.1f",
                w, mSettingsPanelDragzoneFrac, region, mSettingsPanelDragzoneMin, x, (w-x)));
        }

        if (region < mSettingsPanelDragzoneMin) region = mSettingsPanelDragzoneMin;

        return (w - x < region) ? mSettingsPanel : mNotificationPanel;
    }

    @Override
    public void onPanelPeeked() {
        super.onPanelPeeked();
        mBar.makeExpandedVisible(true);
    }

    @Override
    public void startOpeningPanel(PanelView panel) {
        super.startOpeningPanel(panel);
        // we only want to start fading if this is the "first" or "last" panel,
        // which is kind of tricky to determine
        mShouldFade = (mFadingPanel == null || mFadingPanel.isFullyExpanded());
        if (DEBUG) {
            Slog.v(TAG, "start opening: " + panel + " shouldfade=" + mShouldFade);
        }
        mFadingPanel = panel;
    }

    @Override
    public void onAllPanelsCollapsed() {
        super.onAllPanelsCollapsed();
        // give animations time to settle
        mBar.makeExpandedInvisibleSoon();
        mFadingPanel = null;
        mLastFullyOpenedPanel = null;
    }

    @Override
    public void onPanelFullyOpened(PanelView openPanel) {
        super.onPanelFullyOpened(openPanel);
        if (openPanel != mLastFullyOpenedPanel) {
            openPanel.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        }
        mFadingPanel = openPanel;
        mLastFullyOpenedPanel = openPanel;
        mShouldFade = true; // now you own the fade, mister
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mBar.interceptTouchEvent(event) || super.onInterceptTouchEvent(event);
    }

    @Override
    public void panelExpansionChanged(PanelView panel, float frac) {
        super.panelExpansionChanged(panel, frac);

        if (DEBUG) {
            Slog.v(TAG, "panelExpansionChanged: f=" + frac);
        }

        if (panel == mFadingPanel && mScrimColor != 0 && ActivityManager.isHighEndGfx()) {
            if (mShouldFade) {
                frac = mPanelExpandedFractionSum; // don't judge me
                // let's start this 20% of the way down the screen
                frac = frac * 1.2f - 0.2f;
                if (frac <= 0) {
                    mBar.mStatusBarWindow.setBackgroundColor(0);
                } else {
                    // woo, special effects
                    final float k = (float)(1f-0.5f*(1f-Math.cos(3.14159f * Math.pow(1f-frac, 2f))));
                    // attenuate background color alpha by k
                    final int color = (int) ((mScrimColor >>> 24) * k) << 24 | (mScrimColor & 0xFFFFFF);
                    mBar.mStatusBarWindow.setBackgroundColor(color);
                }
            }
        }

        // fade out the panel as it gets buried into the status bar to avoid overdrawing the
        // status bar on the last frame of a close animation
        final int H = mBar.getStatusBarHeight();
        final float ph = panel.getExpandedHeight() + panel.getPaddingBottom();
        float alpha = 1f;
        if (ph < 2*H) {
            if (ph < H) alpha = 0f;
            else alpha = (ph - H) / H;
            alpha = alpha * alpha; // get there faster
        }
        if (panel.getAlpha() != alpha) {
            panel.setAlpha(alpha);
        }

        mBar.updateCarrierLabelVisibility(false);
    }

    public void adjustVolume(boolean opition) {
        AudioManager audioManager = (AudioManager)
            getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            if (opition) {
                audioManager.adjustSuggestedStreamVolume(
                    AudioManager.ADJUST_RAISE,
                    AudioManager.USE_DEFAULT_STREAM_TYPE,
                    AudioManager.FLAG_SHOW_UI |
                    AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            } else {
                audioManager.adjustSuggestedStreamVolume (
                    AudioManager.ADJUST_LOWER,
                    AudioManager.USE_DEFAULT_STREAM_TYPE,
                    AudioManager.FLAG_SHOW_UI |
                    AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            }
        }
    }
}
