/* 
 *   Copyright 2009-2011 Freescale Semiconductor, Inc. All Rights Reserved. 
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

//#define LOG_NDEBUG 0
#define LOG_TAG "VPUEncoder"
#include <utils/Log.h>

#include "VPUEncoder.h"
#include "OMX_Video.h"

#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>

#define STREAM_ENC_PIC_RESET 1
#define STREAM_BUF_SIZE		0x200000

enum PIC_TYPE{
	I_FRAME=0,
	P_FRAME,
	B_FRAME
};

namespace android {

inline static void ConvertYUV420SemiPlanarToYUV420Planar(
        uint8_t *inyuv, uint8_t* outyuv,
        int32_t width, int32_t height) {

    int32_t outYsize = width * height;
    uint32_t *outy =  (uint32_t *) outyuv;
    uint16_t *outcb = (uint16_t *) (outyuv + outYsize);
    uint16_t *outcr = (uint16_t *) (outyuv + outYsize + (outYsize >> 2));

    /* Y copying */
    memcpy(outy, inyuv, outYsize);

    /* U & V copying */
    uint32_t *inyuv_4 = (uint32_t *) (inyuv + outYsize);
    for (int32_t i = height >> 1; i > 0; --i) {
        for (int32_t j = width >> 2; j > 0; --j) {
            uint32_t temp = *inyuv_4++;
            uint32_t tempU = temp & 0xFF;
            tempU = tempU | ((temp >> 8) & 0xFF00);

            uint32_t tempV = (temp >> 8) & 0xFF;
            tempV = tempV | ((temp >> 16) & 0xFF00);

            // Flip U and V
            *outcb++ = tempV;
            *outcr++ = tempU;
        }
    }
}

enum {
    MODE420 = 0,
    MODE422 = 1,
    MODE224 = 2,
    MODE444 = 3,
    MODE400 = 4
};

class VPUMemory{
private:
	VPUMemory();	
public:	
	VPUMemory(int size);
	~VPUMemory();
	vpu_mem_desc mem_desc;
	bool mAllocated;
};

VPUMemory::VPUMemory(int size):mAllocated(false)
{
	int ret,ret_ptr;
	mem_desc.size = size;
	ret = IOGetPhyMem(&mem_desc);
	if (ret) {
		LOGE("Unable to obtain physical mem\n");
		return;
	}

	ret_ptr = IOGetVirtMem(&mem_desc);
	if (ret_ptr <= 0) {
		LOGE("Unable to obtain virtual mem\n");
		IOFreePhyMem(&mem_desc);
		return;
	}
	mAllocated = true;
}

VPUMemory::~VPUMemory()
{
	if(mAllocated){
		IOFreeVirtMem(&mem_desc);
		IOFreePhyMem(&mem_desc);
	}
}

class VPUFrameBuffer{
	private:
		VPUFrameBuffer(){};		
		bool mFrameBufferAllocated;
	public:
		VPUFrameBuffer(int Standard, int ColorFormat,int width, int height);
		~VPUFrameBuffer();	
		vpu_mem_desc mMemoryDescription;
		FrameBuffer mFrameBuffer;
};

VPUFrameBuffer::VPUFrameBuffer(int Standard, int ColorFormat,int width, int height):
	mFrameBufferAllocated(false)
{
	int divX, divY;
	int err;
	divX = (ColorFormat == MODE420 || ColorFormat == MODE422) ? 2 : 1;
	divY = (ColorFormat == MODE420 || ColorFormat == MODE224) ? 2 : 1;

	memset(&mMemoryDescription, 0, sizeof(mMemoryDescription));
	mMemoryDescription.size = (width * height + width / divX * height / divY * 2);
	if (cpu_is_mx5x())
		mMemoryDescription.size += width / divX * height / divY;

	err = IOGetPhyMem(&mMemoryDescription);
	if (err) {
		LOGE("Frame buffer allocation failure\n");
		return;
	}

	mFrameBuffer.bufY= mMemoryDescription.phy_addr;
	mFrameBuffer.bufCb= mFrameBuffer.bufY + width* height;
	mFrameBuffer.bufCr= mFrameBuffer.bufCb + width/ divX * height / divY;
	mFrameBuffer.strideY = width;
	mFrameBuffer.strideC =  width/ divX;

	if (cpu_is_mx5x()) {
		if (Standard==STD_MJPG)
			mFrameBuffer.bufMvCol = mFrameBuffer.bufCr;
		else
			mFrameBuffer.bufMvCol = mFrameBuffer.bufCr + width / divX * height / divY;
	}
	mMemoryDescription.virt_uaddr = IOGetVirtMem(&(mMemoryDescription));
	if (mMemoryDescription.virt_uaddr <= 0) {
		IOFreePhyMem(&mMemoryDescription);
		return ;
	}
	mFrameBufferAllocated = true;
}

VPUFrameBuffer::~VPUFrameBuffer()
{
	if (mFrameBufferAllocated) {
		IOFreeVirtMem(&mMemoryDescription);
		IOFreePhyMem(&mMemoryDescription);
	}
}

VPUEncoder::VPUEncoder(
        const sp<MediaSource>& source)
    : mSource(source),
      mNumInputFrames(-1),
      mPrevTimestampUs(-1),
      mStarted(false),
      mInputBuffer(NULL),
      mInputFrameData(NULL),
      mGroup(NULL) {
	int err;
	vpu_versioninfo ver;
	err = vpu_Init(NULL);
	if (err) {
		LOGE("VPU Init Failure.\n");
		return;
	}

	err = vpu_GetVersionInfo(&ver);
	if (err) {
		LOGE("Cannot get version info\n");
		vpu_UnInit();
		return;
	}
	
	LOGI("VPU firmware version: %d.%d.%d\n", ver.fw_major, ver.fw_minor,
						ver.fw_release);
	LOGI("VPU library version: %d.%d.%d\n", ver.lib_major, ver.lib_minor,
						ver.lib_release);

	mMeta = mSource->getFormat();
	// XXX: walkaround for meta data 
	mMeta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_AVC);
    mInitCheck = initCheck(mMeta);
}

VPUEncoder::VPUEncoder(
        const sp<MediaSource>& source,
        const sp<MetaData>& meta)
    : mSource(source),
      mMeta(meta),
      mNumInputFrames(-1),
      mPrevTimestampUs(-1),
      mStarted(false),
      mInputBuffer(NULL),
      mInputFrameData(NULL),
      mGroup(NULL) {
	int err;
	vpu_versioninfo ver;
	err = vpu_Init(NULL);
	if (err) {
		LOGE("VPU Init Failure.\n");
		return;
	}

	err = vpu_GetVersionInfo(&ver);
	if (err) {
		LOGE("Cannot get version info\n");
		vpu_UnInit();
		return;
	}
	
	LOGI("VPU firmware version: %d.%d.%d\n", ver.fw_major, ver.fw_minor,
						ver.fw_release);
	LOGI("VPU library version: %d.%d.%d\n", ver.lib_major, ver.lib_minor,
						ver.lib_release);
	
    mInitCheck = initCheck(meta);
	
}

VPUEncoder::~VPUEncoder() {
    LOGV("Destruct VPUEncoder");
    if (mStarted) {
        stop();
    }
    delete mBitStreamBuffer;
    delete mEncOpenParam;
    delete mEncParam;
    delete mEncOutputInfo;
    vpu_UnInit();
}

status_t VPUEncoder::initCheck(const sp<MetaData>& meta) {
    LOGV("initCheck");
    CHECK(meta->findInt32(kKeyWidth, &mVideoWidth));
    CHECK(meta->findInt32(kKeyHeight, &mVideoHeight));
    CHECK(meta->findInt32(kKeyFrameRate, &mVideoFrameRate));
    CHECK(meta->findInt32(kKeyBitRate, &mVideoBitRate));

    // XXX: Add more color format support
    CHECK(meta->findInt32(kKeyColorFormat, &mVideoColorFormat));
	LOGI("initCheck color: %d\n", mVideoColorFormat);
    if (mVideoColorFormat != OMX_COLOR_FormatYUV420Planar) {
        if (mVideoColorFormat != OMX_COLOR_FormatYUV420SemiPlanar) {
            LOGE("Color format %d is not supported", mVideoColorFormat);
            return BAD_VALUE;
        }
    }
	
    // XXX: Remove this restriction
    if (mVideoWidth % 16 != 0 || mVideoHeight % 16 != 0) {
        LOGE("Video frame size %dx%d must be a multiple of 16",
            mVideoWidth, mVideoHeight);
        return BAD_VALUE;
    }
	mRotateEnabled = false;
	mSaveEncHeader = false;
	mRotationAngle = 0;
	mMirrorDirection = 0;
	
	mEncOpenParam = new EncOpenParam;
	memset(mEncOpenParam, 0, sizeof(EncOpenParam));
	const char *mime;
	CHECK(meta->findCString(kKeyMIMEType, &mime));
	CHECK(
		!strcmp(mime, MEDIA_MIMETYPE_VIDEO_AVC) ||
		!strcmp(mime, MEDIA_MIMETYPE_VIDEO_MPEG4) ||
		 !strcmp(mime, MEDIA_MIMETYPE_VIDEO_H263));

	if (!strcmp(mime, MEDIA_MIMETYPE_VIDEO_AVC)) {
	   mEncOpenParam->bitstreamFormat = STD_AVC;
	} else if (!strcmp(mime, MEDIA_MIMETYPE_VIDEO_MPEG4)){
	   mEncOpenParam->bitstreamFormat = STD_MPEG4;
	} else if (!strcmp(mime, MEDIA_MIMETYPE_VIDEO_H263)){
	    mEncOpenParam->bitstreamFormat = STD_H263;
	} 
	
	/* Fill up parameters for encoding */
	mEncOpenParam->bitstreamBufferSize = STREAM_BUF_SIZE;
	mBitStreamBuffer = new VPUMemory(mEncOpenParam->bitstreamBufferSize);
	mEncOpenParam->bitstreamBuffer = mBitStreamBuffer->mem_desc.phy_addr;
	mMemoryOffset = mBitStreamBuffer->mem_desc.virt_uaddr - mBitStreamBuffer->mem_desc.phy_addr;
	
	/* If rotation angle is 90 or 270, pic width and height are swapped */
	if (mRotationAngle == 90 || mRotationAngle == 270) {
		mEncOpenParam->picWidth = mVideoHeight;
		mEncOpenParam->picHeight = mVideoWidth;
	} else {
		mEncOpenParam->picWidth = mVideoWidth;
		mEncOpenParam->picHeight = mVideoHeight;
	}

	/*Note: Frame rate cannot be less than 15fps per H.263 spec */
	mEncOpenParam->frameRateInfo = mVideoFrameRate;
	mEncOpenParam->bitRate = mVideoBitRate/1000;
	mEncOpenParam->slicemode.sliceMode = 0;	/* 0: 1 slice per picture; 1: Multiple slices per picture */
	mEncOpenParam->slicemode.sliceSizeMode = 0; /* 0: silceSize defined by bits; 1: sliceSize defined by MB number*/
	mEncOpenParam->slicemode.sliceSize = 4000;  /* Size of a slice in bits or MB numbers */

	mEncOpenParam->initialDelay = 0;
	mEncOpenParam->vbvBufferSize = 0;        /* 0 = ignore 8 */
	mEncOpenParam->intraRefresh = 0;
	mEncOpenParam->sliceReport = 0;
	mEncOpenParam->mbReport = 0;
	mEncOpenParam->mbQpReport = 0;
	mEncOpenParam->rcIntraQp = -1;
	mEncOpenParam->userQpMax = 31;
	mEncOpenParam->userQpMin = 0;
	mEncOpenParam->userQpMinEnable = 0;
	mEncOpenParam->userQpMaxEnable = 0;

	mEncOpenParam->userGamma = (Uint32)(0.75*32768);         /*  (0*32768 <= gamma <= 1*32768) */
	//mEncOpenParam->RcIntervalMode= 1;        /* 0:normal, 1:frame_level, 2:slice_level, 3: user defined Mb_level */
	//mEncOpenParam->MbInterval = 0;
	mEncOpenParam->RcIntervalMode= 0;
	mEncOpenParam->MbInterval = 90;
	mEncOpenParam->avcIntra16x16OnlyModeEnable = 0;

	mEncOpenParam->ringBufferEnable = 0;
	mEncOpenParam->dynamicAllocEnable = 0;

    // Set I frame interval
    int32_t iFramesIntervalSec;
    CHECK(meta->findInt32(kKeyIFramesInterval, &iFramesIntervalSec));
    if (iFramesIntervalSec < 0) {
        mEncOpenParam->gopSize = 0;  //TODO: 
    } else if (iFramesIntervalSec == 0) {
		mEncOpenParam->gopSize = 1; 
    } else {
        mEncOpenParam->gopSize =
            (iFramesIntervalSec * mVideoFrameRate);
    }
    LOGI("idr_period: %d, I-frames interval: %d seconds, and frame rate: %d",
        mEncOpenParam->gopSize, iFramesIntervalSec, mVideoFrameRate);

	if (mVideoColorFormat == OMX_COLOR_FormatYUV420Planar) {
		mEncOpenParam->chromaInterleave = 0;
	}else if (mVideoColorFormat == OMX_COLOR_FormatYUV420SemiPlanar) {
		mEncOpenParam->chromaInterleave = 1;
	}

	if ( mEncOpenParam->bitstreamFormat== STD_MPEG4) {
		mEncOpenParam->EncStdParam.mp4Param.mp4_dataPartitionEnable = 0;
		mEncOpenParam->EncStdParam.mp4Param.mp4_reversibleVlcEnable = 0;
		mEncOpenParam->EncStdParam.mp4Param.mp4_intraDcVlcThr = 0;
		mEncOpenParam->EncStdParam.mp4Param.mp4_hecEnable = 0;
		mEncOpenParam->EncStdParam.mp4Param.mp4_verid = 2;
	} else if ( mEncOpenParam->bitstreamFormat == STD_H263) {
		mEncOpenParam->rcIntraQp = 10;
		mEncOpenParam->EncStdParam.h263Param.h263_annexJEnable = 1;
		mEncOpenParam->EncStdParam.h263Param.h263_annexKEnable = 0;
		mEncOpenParam->EncStdParam.h263Param.h263_annexTEnable = 0;
	} else if ( mEncOpenParam->bitstreamFormat == STD_AVC) {
		mEncOpenParam->rcIntraQp = 36;
		mEncOpenParam->EncStdParam.avcParam.avc_constrainedIntraPredFlag = 0;
		mEncOpenParam->EncStdParam.avcParam.avc_disableDeblk = 0;
		mEncOpenParam->EncStdParam.avcParam.avc_deblkFilterOffsetAlpha = 6;
		mEncOpenParam->EncStdParam.avcParam.avc_deblkFilterOffsetBeta = 0;
		mEncOpenParam->EncStdParam.avcParam.avc_chromaQpOffset = 10;
		mEncOpenParam->EncStdParam.avcParam.avc_audEnable = 0;
		mEncOpenParam->EncStdParam.avcParam.avc_fmoEnable = 0;
		mEncOpenParam->EncStdParam.avcParam.avc_fmoType = 0;
		mEncOpenParam->EncStdParam.avcParam.avc_fmoSliceNum = 1;
		mEncOpenParam->EncStdParam.avcParam.avc_fmoSliceSaveBufSize = 32; /* FMO_SLICE_SAVE_BUF_SIZE */
	} 

	RetCode ret = vpu_EncOpen( &mEncHandle, mEncOpenParam);
	if (ret != RETCODE_SUCCESS) {
		LOGE("Encoder open failed %d\n", ret);
		return -1;
	}
	LOGI("Width %d, height %d, bitrate %d, framerate %d, color %d,mime type %s",
		mVideoWidth,mVideoHeight,mVideoBitRate,mVideoFrameRate,mVideoColorFormat,mime);
    mFormat = new MetaData;
    mFormat->setInt32(kKeyWidth, mVideoWidth);
    mFormat->setInt32(kKeyHeight, mVideoHeight);
    mFormat->setInt32(kKeyBitRate, mVideoBitRate);
    mFormat->setInt32(kKeyFrameRate, mVideoFrameRate);
    mFormat->setInt32(kKeyColorFormat, mVideoColorFormat);
    mFormat->setCString(kKeyMIMEType, mime);
    mFormat->setCString(kKeyDecoderComponent, "VPUEncoder");
    return OK;
}

status_t VPUEncoder::start(MetaData *params) {
    LOGV("start");
    if (mInitCheck != OK) {
        return mInitCheck;
    }

    if (mStarted) {
        LOGW("Call start() when encoder already started");
        return OK;
    }

	RetCode ret;
	SearchRamParam search_pa = {0};
	EncInitialInfo initinfo = {0};

	if (cpu_is_mx27()) {
		search_pa.searchRamAddr = 0xFFFF4C00;
		ret = vpu_EncGiveCommand(mEncHandle, ENC_SET_SEARCHRAM_PARAM, &search_pa);
		if (ret != RETCODE_SUCCESS) {
			LOGE("Encoder SET_SEARCHRAM_PARAM failed\n");
			return UNKNOWN_ERROR;
		}
	}

	if (mRotateEnabled) {
		vpu_EncGiveCommand(mEncHandle, ENABLE_ROTATION, 0);
		vpu_EncGiveCommand(mEncHandle, ENABLE_MIRRORING, 0);
		vpu_EncGiveCommand(mEncHandle, SET_ROTATION_ANGLE, &mRotationAngle);
		vpu_EncGiveCommand(mEncHandle, SET_MIRROR_DIRECTION, &mMirrorDirection);
	}

	ret = vpu_EncGetInitialInfo(mEncHandle, &initinfo);
	if (ret != RETCODE_SUCCESS) {
		LOGE("Encoder GetInitialInfo failed\n");
		return UNKNOWN_ERROR;
	}
	LOGI("minFrameBufferCount %d\n",initinfo.minFrameBufferCount);

	if (mSaveEncHeader) {
		if (mEncOpenParam->bitstreamBuffer == STD_MPEG4) {
			SaveGetEncodeHeader(mEncHandle, ENC_GET_VOS_HEADER, (char *)"mp4_vos_header.dat");
			SaveGetEncodeHeader(mEncHandle, ENC_GET_VO_HEADER, (char *)"mp4_vo_header.dat");
			SaveGetEncodeHeader(mEncHandle, ENC_GET_VOL_HEADER, (char *)"mp4_vol_header.dat");
		} else if (mEncOpenParam->bitstreamBuffer  == STD_AVC) {
			SaveGetEncodeHeader(mEncHandle, ENC_GET_SPS_RBSP, (char *)"avc_sps_header.dat");
			SaveGetEncodeHeader(mEncHandle, ENC_GET_PPS_RBSP, (char *)"avc_pps_header.dat");
		}
	}

	int enc_stride,src_stride;
	/* Must be a multiple of 16 */
	enc_stride = src_stride = ( mEncOpenParam->picWidth + 15 ) & ~15;
	allocFrameBuffers(initinfo.minFrameBufferCount +1);
	ret = vpu_EncRegisterFrameBuffer(mEncHandle, 
		mFrameBufferArray, initinfo.minFrameBufferCount, enc_stride, src_stride, 0, 0, 0);
	if (ret != RETCODE_SUCCESS) {
		LOGE("Register frame buffer failed\n");

	}

	mEncParam = new EncParam;
	memset(mEncParam,0,sizeof(EncParam));
	mEncParam->sourceFrame = &mFrameBufferArray[initinfo.minFrameBufferCount];
	mInputFrameData = (uint8_t *)(mEncParam->sourceFrame->bufY + 
						mFrameBuffers[initinfo.minFrameBufferCount]->mMemoryDescription.virt_uaddr - 
						mFrameBuffers[initinfo.minFrameBufferCount]->mMemoryDescription.phy_addr);
	mEncParam->quantParam = 23;
	mEncParam->forceIPicture = 0;
	mEncParam->skipPicture = 0;
	mEncParam->enableAutoSkip = 1;
	
	mEncParam->encLeftOffset = 0;
	mEncParam->encTopOffset = 0;
	if ((mEncParam->encLeftOffset + mVideoWidth) > mEncOpenParam->picWidth) {
		LOGE("Configure is failure for width and left offset\n");
		return UNKNOWN_ERROR;
	}
	if ((mEncParam->encTopOffset + mVideoHeight) > mEncOpenParam->picHeight) {
		LOGE("Configure is failure for height and top offset\n");
		return UNKNOWN_ERROR;
	}

	mEncOutputInfo = new EncOutputInfo;
	memset(mEncOutputInfo,0,sizeof(EncOutputInfo));
    mGroup = new MediaBufferGroup();
    //mGroup->add_buffer(new MediaBuffer(
    //	(void *) mBitStreamBuffer->mem_desc.virt_uaddr,
    //	mBitStreamBuffer->mem_desc.size));
    mGroup->add_buffer(new MediaBuffer(mBitStreamBuffer->mem_desc.size));
    mSource->start(params);

    mStarted = true;
	if( STD_AVC == mEncOpenParam->bitstreamFormat){
		mNumInputFrames = -1;
	}
    mReadyForNextFrame = true;
    mIsIDRFrame = 0;
#ifdef DUMP_BUFFER
	mInputFile = fopen("/mnt/sdcard/DCIM/Camera/vpu_enc_input.dat","wb");
	if(mInputFile == NULL){
		LOGE("Can not open dump file for input\n");
		return UNKNOWN_ERROR;
	}
	mOutputFile = fopen("/mnt/sdcard/DCIM/Camera/vpu_enc_output.dat","wb");
	if(mOutputFile == NULL){
		LOGE("Can not open dump file for output\n");
		return UNKNOWN_ERROR;
	}
#endif
    return OK;
}

FrameBuffer * VPUEncoder::allocFrameBuffers(unsigned int numBuffers) {	
	mFrameBufferArray = new FrameBuffer[numBuffers];
	if (mFrameBufferArray == NULL) {
		LOGE("Failed to allocate frame buffers\n");
		return NULL;
	}

	for (unsigned int i = 0; i < numBuffers; ++i) {
		VPUFrameBuffer *framebuffer = new VPUFrameBuffer(mEncOpenParam->bitstreamFormat, 
											MODE420,
											mVideoWidth,
											mVideoHeight);
		mFrameBuffers.push(framebuffer);
		mFrameBufferArray[i].bufY = framebuffer->mFrameBuffer.bufY;
		mFrameBufferArray[i].bufCb = framebuffer->mFrameBuffer.bufCb;
		mFrameBufferArray[i].bufCr = framebuffer->mFrameBuffer.bufCr;
		mFrameBufferArray[i].strideY = framebuffer->mFrameBuffer.strideY;
		mFrameBufferArray[i].strideC = framebuffer->mFrameBuffer.strideC;
	}
    return mFrameBufferArray;
}

void VPUEncoder::freeFrameBuffers() {	
	if(mFrameBufferArray){
		delete mFrameBufferArray;
	}
	
	for (unsigned int i = 0; i < mFrameBuffers.size(); ++i) {
		VPUFrameBuffer *framebuffer = mFrameBuffers.editItemAt(i);
		delete framebuffer;
	}
	mFrameBuffers.clear();
}


status_t VPUEncoder::stop() {
    LOGV("stop");
    if (!mStarted) {
        LOGW("Call stop() when encoder has not started");
        return OK;
    }

    if (mInputBuffer) {
        mInputBuffer->release();
        mInputBuffer = NULL;
    }

    if (mGroup) {
        delete mGroup;
        mGroup = NULL;
    }

//    if (mInputFrameData) {
//        delete mInputFrameData;
//        mInputFrameData = NULL;
//    }
	
    freeFrameBuffers();
	RetCode ret;

	ret = vpu_EncClose(mEncHandle);
	if (ret == RETCODE_FRAME_NOT_COMPLETE) {
		EncOutputInfo outinfo;
		vpu_EncGetOutputInfo(mEncHandle, &outinfo);
		vpu_EncClose(mEncHandle);
	}

    mSource->stop();
    //releaseOutputBuffers(); 
    mStarted = false;
#ifdef DUMP_BUFFER
	if(mInputFile){
		fclose(mInputFile);
		mInputFile = NULL;
	}
	if(mOutputFile){
		fclose(mOutputFile);
		mOutputFile = NULL;
	}
#endif

    return OK;
}

sp<MetaData> VPUEncoder::getFormat() {
    LOGV("getFormat");
    return mFormat;
}

uint32_t VPUEncoder::FillHeaders(MediaBuffer *outputBuffer)
{
	EncHeaderParam enchdr_param = {0};
	uint32_t filled_size = 0;

	Uint32 vbuf;
	Uint32 phy_bsbuf  = mBitStreamBuffer->mem_desc.phy_addr;
	Uint32 virt_bsbuf = mBitStreamBuffer->mem_desc.virt_uaddr;


	/* Must put encode header before encoding */
	if (mEncOpenParam->bitstreamFormat == STD_MPEG4) {
		enchdr_param.headerType = VOS_HEADER;
		/*
		 * Please set userProfileLevelEnable to 0 if you need to generate
	         * user profile and level automaticaly by resolution, here is one
		 * sample of how to work when userProfileLevelEnable is 1.
		 */
		int mbPicNum;
		mbPicNum = ((mVideoWidth + 15) / 16) *((mVideoHeight + 15) / 16);
		if (mVideoWidth <= 176 && mVideoHeight <= 144 &&
		    mbPicNum * mVideoFrameRate <= 1485)
			enchdr_param.userProfileLevelIndication = 8; /* L1 */
		/* Please set userProfileLevelIndication to 8 if L0 is needed */
		else if (mVideoWidth <= 352 && mVideoHeight <= 288 &&
			 mbPicNum * mVideoFrameRate<= 5940)
			enchdr_param.userProfileLevelIndication = 2; /* L2 */
		else if (mVideoWidth <= 352 && mVideoHeight <= 288 &&
			 mbPicNum * mVideoFrameRate <= 11880)
			enchdr_param.userProfileLevelIndication = 3; /* L3 */
		else if (mVideoWidth <= 640 && mVideoHeight <= 480 &&
			 mbPicNum * mVideoFrameRate <= 36000)
			enchdr_param.userProfileLevelIndication = 4; /* L4a */
		else if (mVideoWidth <= 720 && mVideoHeight <= 576 &&
			 mbPicNum * mVideoFrameRate <= 40500)
			enchdr_param.userProfileLevelIndication = 5; /* L5 */
		else
			enchdr_param.userProfileLevelIndication = 6; /* L6 */

		vpu_EncGiveCommand(mEncHandle, ENC_PUT_MP4_HEADER, &enchdr_param);
		vbuf = (virt_bsbuf + enchdr_param.buf - phy_bsbuf);
		memcpy(outputBuffer->data(), (void *)vbuf, enchdr_param.size);
		filled_size += enchdr_param.size;
		outputBuffer->set_range(0, filled_size);

		enchdr_param.headerType = VIS_HEADER;
		vpu_EncGiveCommand(mEncHandle, ENC_PUT_MP4_HEADER, &enchdr_param);
		vbuf = (virt_bsbuf + enchdr_param.buf - phy_bsbuf);
		memcpy(outputBuffer->data()+ outputBuffer->range_length(), (void *)vbuf, enchdr_param.size);
		filled_size += enchdr_param.size;
		outputBuffer->set_range(0, filled_size);


		enchdr_param.headerType = VOL_HEADER;
		vpu_EncGiveCommand(mEncHandle, ENC_PUT_MP4_HEADER, &enchdr_param);
		vbuf = (virt_bsbuf + enchdr_param.buf - phy_bsbuf);
		memcpy(outputBuffer->data()+ outputBuffer->range_length(), (void *)vbuf, enchdr_param.size);
		filled_size += enchdr_param.size;
		outputBuffer->set_range(0, filled_size);

	} else if (mEncOpenParam->bitstreamFormat== STD_AVC) {
		enchdr_param.headerType = SPS_RBSP;
		vpu_EncGiveCommand(mEncHandle, ENC_PUT_AVC_HEADER, &enchdr_param);
		vbuf = (virt_bsbuf + enchdr_param.buf - phy_bsbuf);
		memcpy(outputBuffer->data(), (void *)vbuf, enchdr_param.size);
		outputBuffer->set_range(0, enchdr_param.size);
		filled_size += enchdr_param.size;

		enchdr_param.headerType = PPS_RBSP;
		vpu_EncGiveCommand(mEncHandle, ENC_PUT_AVC_HEADER, &enchdr_param);
		vbuf = (virt_bsbuf + enchdr_param.buf - phy_bsbuf);
		memcpy(outputBuffer->data()+ outputBuffer->range_length(), (void *)vbuf, enchdr_param.size);
		filled_size += enchdr_param.size;
		outputBuffer->set_range(0, filled_size);
	}
	
	outputBuffer->meta_data()->setInt32(kKeyIsCodecConfig, 1);
	outputBuffer->meta_data()->setInt64(kKeyTime, 0);
	return filled_size;
}

status_t VPUEncoder::read(
        MediaBuffer **out, const ReadOptions *options) {

    CHECK(!options);
    *out = NULL;

    MediaBuffer *outputBuffer;
    CHECK_EQ(OK, mGroup->acquire_buffer(&outputBuffer));
    uint8_t *outPtr = (uint8_t *) outputBuffer->data();
    uint32_t dataLength = 0;

    if (mNumInputFrames < 0) {			
		dataLength = FillHeaders(outputBuffer);
		++mNumInputFrames;
		*out = outputBuffer;
#ifdef DUMP_BUFFER
		fwrite(outputBuffer->data(),1,outputBuffer->range_length(),mOutputFile);
#endif		
		return OK;
    }
    // Get next input video frame
    if (mReadyForNextFrame) {
        if (mInputBuffer) {
            mInputBuffer->release();
            mInputBuffer = NULL;
        }
        status_t err = mSource->read(&mInputBuffer, options);
        if (err != OK) {
            LOGE("Failed to read input video frame %lld, err %d",mNumInputFrames,err);
            outputBuffer->release();
            return err;
        }

        if (mInputBuffer->size() - ((mVideoWidth * mVideoHeight * 3) >> 1) != 0) {
            outputBuffer->release();
            mInputBuffer->release();
            mInputBuffer = NULL;
            return UNKNOWN_ERROR;
        }

        int64_t timeUs;
        CHECK(mInputBuffer->meta_data()->findInt64(kKeyTime, &timeUs));
        outputBuffer->meta_data()->setInt64(kKeyTime, timeUs);

        if (mNumInputFrames >= 1 && mPrevTimestampUs == timeUs) {
            mInputBuffer->release();
            mInputBuffer = NULL;
            outputBuffer->set_range(0, 0);
            *out = outputBuffer;
            return OK;
        }

        CHECK(mPrevTimestampUs < timeUs);
        mPrevTimestampUs = timeUs;

		RetCode ret;		

		//Read input data
		memcpy((void *)mInputFrameData,
				mInputBuffer->data(),
				mInputBuffer->range_length());

#ifdef DUMP_BUFFER
		fwrite((void *)mInputFrameData,1,mInputBuffer->range_length(),mInputFile);
#endif		
		
		ret = vpu_EncStartOneFrame(mEncHandle,mEncParam);
		if (ret != RETCODE_SUCCESS) {
			LOGE("vpu_EncStartOneFrame failed Err code:%d\n",ret);
			return UNKNOWN_ERROR;
		}
		uint32_t WaitCount =50;
		while (vpu_IsBusy()) {			
			vpu_WaitForInt(200);
			WaitCount--;
			if(WaitCount ==0){
				return UNKNOWN_ERROR;
			}
		}
		
		ret = vpu_EncGetOutputInfo(mEncHandle,mEncOutputInfo);
		if (ret != RETCODE_SUCCESS) {
			LOGE("vpu_EncGetOutputInfo failed Err code: %d\n",ret);
			return UNKNOWN_ERROR;
		}
		//LOGD("Encode frame %lld, size %d, type %d, skipped %d",
		//	mNumInputFrames,mEncOutputInfo->bitstreamSize,mEncOutputInfo->picType,mEncOutputInfo->skipEncoded);
		
		if (mEncOutputInfo->skipEncoded)
			LOGI("Skip encoding one Frame!\n");

		if(I_FRAME == mEncOutputInfo->picType)
			outputBuffer->meta_data()->setInt32(kKeyIsSyncFrame, 1);
		

		memcpy(outputBuffer->data()+ dataLength, 
				(void *)mEncOutputInfo->bitstreamBuffer+mMemoryOffset, 
				mEncOutputInfo->bitstreamSize);
		outputBuffer->set_range(0, dataLength + mEncOutputInfo->bitstreamSize);

#ifdef DUMP_BUFFER
		fwrite(outputBuffer->data(),1,outputBuffer->range_length(),mOutputFile);
#endif		

		++mNumInputFrames;
	}
		
	*out = outputBuffer;	
	return OK;

}

void VPUEncoder::signalBufferReturned(MediaBuffer *buffer) {
}
}  // namespace android

