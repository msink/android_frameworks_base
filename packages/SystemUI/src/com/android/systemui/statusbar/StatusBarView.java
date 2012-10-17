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

package com.android.systemui.statusbar;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.media.AudioManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.ServiceManager;
import android.os.Message;
import android.util.AttributeSet;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.systemui.R;

public class StatusBarView extends RelativeLayout {
    private static final String TAG = "StatusBarView";

    static final int DIM_ANIM_TIME = 400;
    
    StatusBarService mService;
    boolean mTracking;
    int mStartX, mStartY;
    ViewGroup mNotificationIcons;
    ViewGroup mStatusIcons;
    View mDate;
    FixedSizeDrawable mBackground;
    
    private boolean is_touch_button = false;
    TextView mbut_bac;
    TextView mbut_home;
    TextView mbut_menu;
    TextView mbut_add;
    TextView mbut_sub;
    TextView mbut_left;
    TextView mbut_right;

    boolean is_down = false;
    final private int ADJUST_VOLUME_DELAY = 250;

    final IWindowManager windowManager = IWindowManager.Stub
               .asInterface(ServiceManager.getService(Context.WINDOW_SERVICE));

    public StatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    Intent mHomeIntent = new Intent(Intent.ACTION_MAIN, null);

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mNotificationIcons = (ViewGroup)findViewById(R.id.notificationIcons);
        mStatusIcons = (ViewGroup)findViewById(R.id.statusIcons);

        mbut_home = (TextView) findViewById(R.id.status_bar_home);
        mbut_menu = (TextView) findViewById(R.id.status_bar_menu);
        mbut_bac = (TextView) findViewById(R.id.status_bar_back);
        mbut_add = (TextView) findViewById(R.id.status_bar_add);
        mbut_sub = (TextView) findViewById(R.id.status_bar_sub);
        mbut_left = (TextView) findViewById(R.id.status_bar_left);
        mbut_right = (TextView) findViewById(R.id.status_bar_right);

        mbut_left.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    windowManager.setRotation(-1, false, 0);
                } catch (Exception e) {
                }
            }
        });

        mbut_right.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    windowManager.setRotation(-2, false, 0);
                } catch (Exception e) {
                }
            }
        });

        mbut_home.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    sendKeyEvent(KeyEvent.KEYCODE_HOME, 102, true);
                } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    sendKeyEvent(KeyEvent.KEYCODE_HOME, 102, false);
                }
                return true;
            }
        });

        mbut_menu.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    sendKeyEvent(KeyEvent.KEYCODE_MENU, 59, true);
                } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    sendKeyEvent(KeyEvent.KEYCODE_MENU, 59, false);
                }
                return true;
            }
        });

        mbut_bac.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    sendKeyEvent(KeyEvent.KEYCODE_BACK, 158, true);
                } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    sendKeyEvent(KeyEvent.KEYCODE_BACK, 158, false);
                }
                return true;
            }
        });

        mbut_add.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    is_down = true;
                    Adjust_Volume(true);
                    maddHandler.postDelayed(maddRun, 500);
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    is_down = false;
                    maddHandler.removeCallbacks(maddRun);
                } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    if (event.getX() < 0 || event.getY() < 0 ||
                                            event.getX() > v.getWidth() ||
                                            event.getY() > v.getHeight()) {
                        is_down = false;
                        maddHandler.removeCallbacks(maddRun);
                    }
                }
                return true;
            }
        });

        mbut_sub.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    is_down = true;
                    Adjust_Volume(false);
                    msubHandler.postDelayed(msubRun, 500);
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    is_down = false;
                    msubHandler.removeCallbacks(msubRun);
                }
                else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    if (event.getX() < 0 || event.getY() < 0 ||
                                            event.getX() > v.getWidth() ||
                                            event.getY() > v.getHeight()) {
                        is_down = false;
                        msubHandler.removeCallbacks(msubRun);
                    }
                }
                return true;
            }
        });
    }

    public void Adjust_Volume(boolean opition) {
        AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
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

    private Handler maddHandler = new Handler();
    private Runnable maddRun = new Runnable() {
       public void run() {
           maddHandler.removeCallbacks(maddRun);
           if (is_down) {
               Adjust_Volume(true);
               maddHandler.postDelayed(maddRun, ADJUST_VOLUME_DELAY);
           }
       }
    };

    private Handler msubHandler = new Handler();
    private Runnable msubRun = new Runnable () {
        public void run () {
            msubHandler.removeCallbacks (msubRun);
            if (is_down) {
                Adjust_Volume(false);
                msubHandler.postDelayed(msubRun, ADJUST_VOLUME_DELAY);
            }
        }
    };

    private Handler removerButtonHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            is_down = false;
            msubHandler.removeCallbacks(msubRun);
            mbut_sub.setBackgroundResource(R.drawable.sub_normal);
            maddHandler.removeCallbacks (maddRun);
            mbut_add.setBackgroundResource(R.drawable.add_normal);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mService.onBarViewAttached();

        requestUnion(this);
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mService.performCollapse();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        b = 40;
        super.onLayout(changed, l, t, r, b);
    }

    /**
     * Gets the left position of v in this view.  Throws if v is not
     * a child of this.
     */
    private int getViewOffset(View v) {
        int offset = 0;
        while (v != this) {
            offset += v.getLeft();
            ViewParent p = v.getParent();
            if (v instanceof View) {
                v = (View)p;
            } else {
                throw new RuntimeException(v + " is not a child of " + this);
            }
        }
        return offset;
    }

    private int getDateSize(ViewGroup g, int w, int offset) {
        final int N = g.getChildCount();
        for (int i=0; i<N; i++) {
            View v = g.getChildAt(i);
            int l = v.getLeft() + offset;
            int r = v.getRight() + offset;
            if (w >= l && w <= r) {
                return r;
            }
        }
        return -1;
    }

    private boolean inTouchButtonArea(android.view.MotionEvent event) {
         int left = findViewById(R.id.button_layout_Home).getRight();
         int right = findViewById(R.id.button_layout).getLeft();
         float x = event.getX();
         int EXTRA = 5;
         return (x < (left + 5)) || (x > (right - 5));
    }

    /**
     * Ensure that, if there is no target under us to receive the touch,
     * that we process it ourself.  This makes sure that onInterceptTouchEvent()
     * is always called for the entire gesture.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!is_touch_button) {
            mService.interceptTouchEvent(event);
        }
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        removerButtonHandler.removeMessages(0);
        removerButtonHandler.sendEmptyMessageDelayed(0, 500);
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            is_touch_button = inTouchButtonArea(event);
        }
        return super.onInterceptTouchEvent(event);
    }

    private void sendKeyEvent(int code, int event, boolean down) {
        try {
            KeyEvent ev = new KeyEvent(0, 0,
                down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                code, 0, 0, 0, event, KeyEvent.FLAG_FROM_SYSTEM);
            IWindowManager.Stub
                .asInterface(ServiceManager.getService(Context.WINDOW_SERVICE))
                .injectKeyEvent_status_bar(ev, true);
        } catch (RemoteException e) {
        }
    }
}

