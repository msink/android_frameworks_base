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
/* Copyright 2012 Freescale Semiconductor, Inc. */

package android.os;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/** @hide */
interface IDisplayManager
{

    String[] getDisplayModeList(int dispid);

    boolean setDisplayMode(int dispid, String disp_mode);

    boolean setDisplayEnable(int dispid, boolean enable);

    boolean setDisplayMirror(int dispid, boolean enable);

    boolean setDisplayXOverScan(int dispid, int xOverscan);
    boolean setDisplayYOverScan(int dispid, int yOverscan);

    boolean setDisplayRotation(int dispid, boolean enable);

    boolean setDisplayColorDepth(int dispid, int colordepth);

    boolean setDisplayKeepRate(int dispid, int keepRate);

    boolean getDisplayEnable(int dispid);
    boolean getDisplayMirror(int dispid);
    boolean getDisplayRotation(int dispid);
    String getDisplayMode(int dispid);
    String getDisplayName(int dispid);
    int getDisplayXOverScan(int dispid);
    int getDisplayYOverScan(int dispid);
    int getDisplayColorDepth(int dispid);
    int getDisplayKeepRate(int dispid);

    boolean rebootSystem();

}
