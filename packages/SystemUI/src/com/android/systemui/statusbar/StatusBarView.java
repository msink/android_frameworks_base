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
    }

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
        super.onLayout(changed, l, t, r, b);
    }

    /**
     * Ensure that, if there is no target under us to receive the touch,
     * that we process it ourself.  This makes sure that onInterceptTouchEvent()
     * is always called for the entire gesture.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return true;
    }
}

