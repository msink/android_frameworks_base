/* 
 *   Copyright 2009-2010 Freescale Semiconductor, Inc. All Rights Reserved. 
 */

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

#ifndef VPU_ENCODER_H_

#define VPU_ENCODER_H_

#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaSource.h>
#include <utils/Vector.h>
#ifdef __cplusplus
extern "C"
{
#endif
#include <vpu_lib.h>
#include <vpu_io.h>
#ifdef __cplusplus
}
#endif

//#define DUMP_BUFFER

namespace android {

struct MediaBuffer;
struct MediaBufferGroup;

struct VPUMemory;
struct VPUFrameBuffer;

struct VPUEncoder : public MediaSource,
                    public MediaBufferObserver {
    VPUEncoder(const sp<MediaSource> &source);                
    VPUEncoder(const sp<MediaSource> &source,
            const sp<MetaData>& meta);

    virtual status_t start(MetaData *params);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options);

    virtual void signalBufferReturned(MediaBuffer *buffer);


protected:
    virtual ~VPUEncoder();

private:
    sp<MediaSource> mSource;
    sp<MetaData>    mFormat;
    sp<MetaData>    mMeta;

    int32_t  mVideoWidth;
    int32_t  mVideoHeight;
    int32_t  mVideoFrameRate;
    int32_t  mVideoBitRate;
    int32_t  mVideoColorFormat;
	int32_t  mRotationAngle;
		
    int64_t  mNumInputFrames;
    int64_t  mPrevTimestampUs;
    status_t mInitCheck;
    bool     mStarted;
    //bool     mSpsPpsHeaderReceived;
    bool     mReadyForNextFrame;
	bool     mRotateEnabled;
	bool     mSaveEncHeader;
	int32_t  mMirrorDirection;
    int32_t  mIsIDRFrame;  // for set kKeyIsSyncFrame

	EncHandle mEncHandle;
	EncOpenParam *mEncOpenParam;
	EncParam     *mEncParam;
	EncOutputInfo *mEncOutputInfo;

	VPUMemory *mBitStreamBuffer;
	VPUMemory *mSliceBuffer;	
	VPUMemory *mPSBuffer;	
	Vector<VPUFrameBuffer*> mFrameBuffers;
	FrameBuffer* mFrameBufferArray;
	
    MediaBuffer           *mInputBuffer;
    uint8_t               *mInputFrameData;
	int32_t				  mMemoryOffset;
    MediaBufferGroup      *mGroup;
    Vector<MediaBuffer *> mOutputBuffers;
#ifdef DUMP_BUFFER
	FILE *mInputFile;
	FILE *mOutputFile;
#endif

    status_t initCheck(const sp<MetaData>& meta);
	FrameBuffer * allocFrameBuffers(unsigned int numBuffers);
    void freeFrameBuffers();
	uint32_t FillHeaders(MediaBuffer *outputBuffer);

    VPUEncoder(const VPUEncoder &);
    VPUEncoder &operator=(const VPUEncoder &);
};

}  // namespace android

#endif  // VPU_ENCODER_H_

