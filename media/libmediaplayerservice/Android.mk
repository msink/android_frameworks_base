LOCAL_PATH:= $(call my-dir)

#
# libmediaplayerservice
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
    MediaRecorderClient.cpp     \
    MediaPlayerService.cpp      \
    MetadataRetrieverClient.cpp \
    TestPlayerStub.cpp          \
    MidiMetadataRetriever.cpp   \
    MidiFile.cpp                \
    StagefrightPlayer.cpp       \
    StagefrightRecorder.cpp

LOCAL_SHARED_LIBRARIES :=     		\
	libcutils             			\
	libutils              			\
	libbinder             			\
	libvorbisidec         			\
	libsonivox            			\
	libmedia              			\
	libcamera_client      			\
	libandroid_runtime    			\
	libstagefright        			\
	libstagefright_omx    			\
	libstagefright_foundation       \
	libgui                          \
	libdl                                \
	libstagefright_foundation               \
	libsurfaceflinger_client                \
	libgui                                  \
	libdrmframework  \

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

LOCAL_STATIC_LIBRARIES := \
        libstagefright_nuplayer                 \
        libstagefright_rtsp                     \

LOCAL_C_INCLUDES :=                                                 \
	$(JNI_H_INCLUDE)                                                \
	$(call include-path-for, graphics corecg)                       \
	$(TOP)/frameworks/base/include/media/stagefright/openmax \
	$(TOP)/frameworks/base/media/libstagefright/include             \
	$(TOP)/frameworks/base/media/libstagefright/rtsp                \
        $(TOP)/external/tremolo/Tremolo \

ifeq ($(HAVE_FSL_IMX_CODEC),true)
LOCAL_CFLAGS += -DFSL_GM_PLAYER
endif

ifeq ($(findstring x4.,x$(PLATFORM_VERSION)), x4.)
LOCAL_CFLAGS += -DICS
endif

ifeq ($(TARGET_BOARD_PLATFORM), imx6)
	LOCAL_CFLAGS += -DMX6X
endif

LOCAL_MODULE:= libmediaplayerservice

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))

