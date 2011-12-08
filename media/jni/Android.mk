LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    android_media_MediaPlayer.cpp \
    android_media_MediaRecorder.cpp \
    android_media_MediaScanner.cpp \
    android_media_MediaMetadataRetriever.cpp \
    android_media_ResampleInputStream.cpp \
    android_media_MediaProfiles.cpp \
    android_media_AmrInputStream.cpp \
    android_media_Utils.cpp \
    android_mtp_MtpDatabase.cpp \
    android_mtp_MtpDevice.cpp \
    android_mtp_MtpServer.cpp \

LOCAL_SHARED_LIBRARIES := \
    libandroid_runtime \
    libnativehelper \
    libutils \
    libbinder \
    libmedia \
    libskia \
    libui \
    libcutils \
    libgui \
    libstagefright \
    libcamera_client \
    libsqlite \
    libmtp \
    libusbhost \
    libexif    \

ifeq ($(HAVE_FSL_IMX_CODEC),true)
LOCAL_SHARED_LIBRARIES +=                       \
	lib_omx_player_arm11_elinux \
	lib_omx_osal_v2_arm11_elinux \
	lib_omx_client_arm11_elinux \
	lib_omx_utils_v2_arm11_elinux \
	lib_omx_core_mgr_v2_arm11_elinux \
	lib_omx_res_mgr_v2_arm11_elinux \
	lib_id3_parser_arm11_elinux
endif

LOCAL_C_INCLUDES += \
    external/jhead \
    external/tremor/Tremor \
    frameworks/base/core/jni \
    frameworks/base/media/libmedia \
    frameworks/base/media/libstagefright/codecs/amrnb/enc/src \
    frameworks/base/media/libstagefright/codecs/amrnb/common \
    frameworks/base/media/libstagefright/codecs/amrnb/common/include \
    frameworks/base/media/mtp \
    $(PV_INCLUDES) \
    $(JNI_H_INCLUDE) \
    $(call include-path-for, corecg graphics)

LOCAL_CFLAGS += -DBUILD_WITH_FULL_STAGEFRIGHT

ifeq ($(HAVE_FSL_IMX_CODEC),true)
LOCAL_CFLAGS += -DFSL_GM_PLAYER
endif

ifeq ($(findstring x4.,x$(PLATFORM_VERSION)), x4.)
LOCAL_CFLAGS += -DICS
endif

LOCAL_LDLIBS := -lpthread

LOCAL_MODULE:= libmedia_jni

include $(BUILD_SHARED_LIBRARY)

# build libsoundpool.so
# build libaudioeffect_jni.so
include $(call all-makefiles-under,$(LOCAL_PATH))
