/*
 * Copyright (C) 2010 The Android Open Source Project
 * Copyright (C) 2010-2012 Freescale Semiconductor, Inc.
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

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/String8.h>
#include <utils/Vector.h>
#include <cutils/properties.h>

#include <hardware/hardware.h>

#include <cutils/log.h>

#include <EGL/egl.h>

#include "LayerBase.h"
#include "HWComposer.h"
#include "SurfaceFlinger.h"

namespace android {
// ---------------------------------------------------------------------------

HWComposer::HWComposer(const sp<SurfaceFlinger>& flinger)
    : isAllocated(0), mFlinger(flinger),
      mModule(0), mHwc(0), mList(0), mCapacity(0),
      mNumOVLayers(0), mNumFBLayers(0),
      mDpy(EGL_NO_DISPLAY), mSur(EGL_NO_SURFACE)
{
    int err = hw_get_module(HWC_HARDWARE_MODULE_ID, &mModule);
    LOGW_IF(err, "%s module not found", HWC_HARDWARE_MODULE_ID);
    if (err == 0) {
        err = hwc_open(mModule, &mHwc);
        LOGE_IF(err, "%s device failed to initialize (%s)",
                HWC_HARDWARE_COMPOSER, strerror(-err));
        if (err == 0) {
            if (mHwc->registerProcs) {
                mCBContext.hwc = this;
                mCBContext.procs.invalidate = &hook_invalidate;
                mHwc->registerProcs(mHwc, &mCBContext.procs);
            }
        }
    }
}

HWComposer::~HWComposer() {
    freeAllocatedBuffer();
    free(mList);
    if (mHwc) {
        hwc_close(mHwc);
    }
}

status_t HWComposer::initCheck() const {
    return mHwc ? NO_ERROR : NO_INIT;
}

void HWComposer::hook_invalidate(struct hwc_procs* procs) {
    reinterpret_cast<cb_context *>(procs)->hwc->invalidate();
}

void HWComposer::invalidate() {
    mFlinger->repaintEverything();
}

void HWComposer::setFrameBuffer(EGLDisplay dpy, EGLSurface sur) {
    mDpy = (hwc_display_t)dpy;
    mSur = (hwc_surface_t)sur;
}

void HWComposer::freeAllocatedBuffer()
{
    if(!mList || !isAllocated)
        return;

    for (size_t i=0 ; i<mList->numHwLayers ; i++) {
        hwc_layer_t *layer = &mList->hwLayers[i];
        if(layer->visibleRegionScreen.rects != NULL && layer->visibleRegionScreen.numRects > 0) {
            free((void *)(layer->visibleRegionScreen.rects));
            layer->visibleRegionScreen.numRects = 0;
            layer->visibleRegionScreen.rects = NULL;
        }
    }
    isAllocated = 0;
}

void HWComposer::adjustRectParam(hwc_rect_t *rect, float rateW, float rateH, int deltaW, int deltaH)
{
    rect->left = (int)((float)rect->left * rateW) + (deltaW >> 1);
    rect->top = (int)((float)rect->top * rateH) + (deltaH >> 1);
    rect->right = (rect->right * rateW) + (deltaW >> 1);
    rect->bottom = (rect->bottom * rateH) + (deltaH >> 1);
}

void HWComposer::adjustDisplayParam(hwc_rect_t *rect, int keepRate, int defaultWidth, int defaultHeight,
                         int displayWidth, int displayHeight, int* pdw, int* pdh, int orientation)
{
    float rtw, rth;
    int dlw, dlh;
    int dw = displayWidth;
    int dh = displayHeight;

    switch(keepRate) {
        case SETTING_MODE_FULL_SCREEN:

            break;

        case SETTING_MODE_KEEP_PRIMARY_RATE:
            if((float)dw >= (float)dh * (float)defaultWidth/(float)defaultHeight)
                dw = (int)((float)dh * (float)defaultWidth/(float)defaultHeight);
            else
                dh = (int)((float)dw * (float)defaultHeight/(float)defaultWidth);
            break;

        case SETTING_MODE_KEEP_16_9_RATE:
            if((float)dw/(float)dh >= (float)16/(float)9)
                dw = (int)((float)dh * (float)16/(float)9);
            else
                dh = (int)((float)dw * (float)9/(float)16);
            break;
        case SETTING_MODE_KEEP_4_3_RATE:
            if((float)dw/(float)dh >= (float)4/(float)3)
                dw = (int)((float)dh * (float)4/(float)3);
            else
                dh = (int)((float)dw * (float)3/(float)4);
            break;

        default:
            LOGI("use the defualt keep proportion rate.");
            if((float)dw >= (float)dh * (float)defaultWidth/(float)defaultHeight)
                dw = (int)((float)dh * (float)defaultWidth/(float)defaultHeight);
            else
                dh = (int)((float)dw * (float)defaultHeight/(float)defaultWidth);
            break;
    }
    *pdw = dw;
    *pdh = dh;

    rtw = (float)dw/(float)defaultWidth;
    rth = (float)dh/(float)defaultHeight;
    dlw = displayWidth - dw;
    dlh = displayHeight - dh;

    int rotate = 0;
    char property[PROPERTY_VALUE_MAX];
    if (property_get("ro.sf.hwrotation", property, NULL) > 0) {
        rotate = atoi(property);
    }

    if(((orientation & ISurfaceComposer::eOrientationSwapMask) && (rotate == 0)) ||
        (!(orientation & ISurfaceComposer::eOrientationSwapMask) && (rotate == 270 || rotate == 90)))
        adjustRectParam(rect, rth, rtw, dlh, dlw);
    else
        adjustRectParam(rect, rtw, rth, dlw, dlh);
}

void HWComposer::adjustRectScale(hwc_rect_t *rect, int dw, int dh, int xScale, int yScale, int orientation)
{
    if (xScale == 0 && yScale == 0)
        return;

    float rtw, rth;
    int dlw, dlh;

    rtw = (100.0 - (float)xScale)/100.0;
    rth = (100.0 - (float)yScale)/100.0;
    dlw = (int)((float)dw * (float)xScale / 100.0);
    dlh = (int)((float)dh * (float)yScale / 100.0);

    int rotate = 0;
    char property[PROPERTY_VALUE_MAX];
    if (property_get("ro.sf.hwrotation", property, NULL) > 0) {
        rotate = atoi(property);
    }

    if(((orientation & ISurfaceComposer::eOrientationSwapMask) && (rotate == 0)) ||
        (!(orientation & ISurfaceComposer::eOrientationSwapMask) && (rotate == 270 || rotate == 90)))
        adjustRectParam(rect, rth, rtw, dlh, dlw);
    else
        adjustRectParam(rect, rtw, rth, dlw, dlh);
}

void HWComposer::adjustGeometry(hwc_layer_t *layer, int keepRate, int defaultWidth, int defaultHeight,
                         int displayWidth, int displayHeight, int xScale, int yScale, int orientation)
{
    if(!mList)
        return;
    int dw = displayWidth;
    int dh = displayHeight;
    int fw = defaultWidth;
    int fh = defaultHeight;

    hwc_rect_t *rect = &layer->displayFrame;
    adjustDisplayParam(rect, keepRate, fw, fh, displayWidth, displayHeight, &dw, &dh, orientation);
    adjustRectScale(rect, dw, dh, xScale, yScale, orientation);

    size_t numRects = 0;
    hwc_rect_t *rects = NULL;

    numRects = layer->visibleRegionScreen.numRects;
    rects = (hwc_rect_t *)layer->visibleRegionScreen.rects;

    if(numRects > 0) {
        layer->visibleRegionScreen.rects = (hwc_rect_t const *)malloc(sizeof(hwc_rect_t) * numRects);
        memcpy((void *)(layer->visibleRegionScreen.rects), (const void*)(rects), sizeof(hwc_rect_t) * numRects);
        isAllocated = 1;
    }

    for(size_t m=0; m<layer->visibleRegionScreen.numRects; m++) {
        rect = (hwc_rect_t *)layer->visibleRegionScreen.rects + m;
        adjustDisplayParam(rect, keepRate, fw, fh, displayWidth, displayHeight, &dw, &dh, orientation);
        adjustRectScale(rect, dw, dh, xScale, yScale, orientation);
    }
}

status_t HWComposer::createWorkList(size_t numLayers) {
    if (mHwc) {
        freeAllocatedBuffer();
        if (!mList || mCapacity < numLayers) {
            free(mList);
            size_t size = sizeof(hwc_layer_list) + numLayers*sizeof(hwc_layer_t);
            mList = (hwc_layer_list_t*)malloc(size);
            mCapacity = numLayers;
        }
        mList->flags = HWC_GEOMETRY_CHANGED;
        mList->numHwLayers = numLayers;
    }
    return NO_ERROR;
}

status_t HWComposer::prepare() const {
    int err = mHwc->prepare(mHwc, mList);
    if (err == NO_ERROR) {
        size_t numOVLayers = 0;
        size_t numFBLayers = 0;
        size_t count = mList->numHwLayers;
        for (size_t i=0 ; i<count ; i++) {
            hwc_layer& l(mList->hwLayers[i]);
            if (l.flags & HWC_SKIP_LAYER) {
                l.compositionType = HWC_FRAMEBUFFER;
            }
            switch (l.compositionType) {
                case HWC_OVERLAY:
                    numOVLayers++;
                    break;
                case HWC_FRAMEBUFFER:
                    numFBLayers++;
                    break;
            }
        }
        mNumOVLayers = numOVLayers;
        mNumFBLayers = numFBLayers;
    }
    return (status_t)err;
}

size_t HWComposer::getLayerCount(int type) const {
    switch (type) {
        case HWC_OVERLAY:
            return mNumOVLayers;
        case HWC_FRAMEBUFFER:
            return mNumFBLayers;
    }
    return 0;
}

status_t HWComposer::commit() const {
    int err = mHwc->set(mHwc, mDpy, mSur, mList);
    if (mList) {
        mList->flags &= ~HWC_GEOMETRY_CHANGED;
    }
    return (status_t)err;
}

status_t HWComposer::release() const {
    if (mHwc) {
        int err = mHwc->set(mHwc, NULL, NULL, NULL);
        return (status_t)err;
    }
    return NO_ERROR;
}

status_t HWComposer::disable() {
    if (mHwc) {
        free(mList);
        mList = NULL;
        int err = mHwc->prepare(mHwc, NULL);
        return (status_t)err;
    }
    return NO_ERROR;
}

size_t HWComposer::getNumLayers() const {
    return mList ? mList->numHwLayers : 0;
}

hwc_layer_t* HWComposer::getLayers() const {
    return mList ? mList->hwLayers : 0;
}

void HWComposer::dump(String8& result, char* buffer, size_t SIZE,
        const Vector< sp<LayerBase> >& visibleLayersSortedByZ) const {
    if (mHwc && mList) {
        result.append("Hardware Composer state:\n");

        snprintf(buffer, SIZE, "  numHwLayers=%u, flags=%08x\n",
                mList->numHwLayers, mList->flags);
        result.append(buffer);
        result.append(
                "   type   |  handle  |   hints  |   flags  | tr | blend |  format  |       source crop         |           frame           name \n"
                "----------+----------+----------+----------+----+-------+----------+---------------------------+--------------------------------\n");
        //      " ________ | ________ | ________ | ________ | __ | _____ | ________ | [_____,_____,_____,_____] | [_____,_____,_____,_____]
        for (size_t i=0 ; i<mList->numHwLayers ; i++) {
            const hwc_layer_t& l(mList->hwLayers[i]);
            const sp<LayerBase> layer(visibleLayersSortedByZ[i]);
            int32_t format = -1;
            if (layer->getLayer() != NULL) {
                const sp<GraphicBuffer>& buffer(layer->getLayer()->getActiveBuffer());
                if (buffer != NULL) {
                    format = buffer->getPixelFormat();
                }
            }
            snprintf(buffer, SIZE,
                    " %8s | %08x | %08x | %08x | %02x | %05x | %08x | [%5d,%5d,%5d,%5d] | [%5d,%5d,%5d,%5d] %s\n",
                    l.compositionType ? "OVERLAY" : "FB",
                    intptr_t(l.handle), l.hints, l.flags, l.transform, l.blending, format,
                    l.sourceCrop.left, l.sourceCrop.top, l.sourceCrop.right, l.sourceCrop.bottom,
                    l.displayFrame.left, l.displayFrame.top, l.displayFrame.right, l.displayFrame.bottom,
                    layer->getName().string());
            result.append(buffer);
        }
    }
    if (mHwc && mHwc->common.version >= 1 && mHwc->dump) {
        mHwc->dump(mHwc, buffer, SIZE);
        result.append(buffer);
    }
}

// ---------------------------------------------------------------------------
}; // namespace android
