LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        VPUEncoder.cpp	


LOCAL_MODULE := libstagefright_vpuenc

LOCAL_MODULE_TAGS := eng

LOCAL_C_INCLUDES := \
        $(TOP)/frameworks/base/media/libstagefright/include \
        $(TOP)/frameworks/base/include/media/stagefright/openmax \
        $(TOP)/external/linux-lib/vpu

LOCAL_CFLAGS := \
    -D__arm__ \
    -DOSCL_IMPORT_REF= -DOSCL_UNUSED_ARG= -DOSCL_EXPORT_REF=

include $(BUILD_STATIC_LIBRARY)
