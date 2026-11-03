LOCAL_SRC_FILES := $(SF_SRC_FILES)
LOCAL_CFLAGS    := -std=c++17 -O3 -DNDEBUG -fno-exceptions -fconstexpr-steps=500000000 \
                   -DNNUE_EMBEDDING_OFF -DUSE_PTHREADS \
                   -fPIE $(MY_ARCH_DEF) -s -flto=thin
LOCAL_LDFLAGS	+= -fPIE -s -flto=thin
include $(BUILD_EXECUTABLE)
