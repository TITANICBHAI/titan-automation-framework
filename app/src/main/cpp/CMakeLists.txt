cmake_minimum_required(VERSION 3.22.1)
project("titan_jni")

# ── OpenCV native include path ────────────────────────────────────────────────
# Expects opencv.aar to be extracted or OpenCV-android-sdk to be at OPENCV_DIR.
# Set via: cmake -DOPENCV_DIR=/path/to/OpenCV-android-sdk/sdk/native
# The gradle build passes this automatically if OPENCV_SDK_DIR is set.
if(DEFINED OPENCV_DIR)
    include_directories(${OPENCV_DIR}/jni/include)
    add_library(opencv_java4 SHARED IMPORTED)
    set_target_properties(opencv_java4 PROPERTIES
        IMPORTED_LOCATION "${OPENCV_DIR}/libs/${ANDROID_ABI}/libopencv_java4.so")
endif()

# ── AHardwareBuffer JNI bridge ────────────────────────────────────────────────
add_library(titan_jni SHARED
    titan_jni.cpp
)

# Android NDK system libraries
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(titan_jni
    ${log-lib}
    ${android-lib}
    # Link OpenCV only if available
    $<$<BOOL:${OPENCV_DIR}>:opencv_java4>
)

target_compile_options(titan_jni PRIVATE
    -Wall -Wextra -O2
    -fvisibility=hidden         # only JNI_OnLoad and JNIEXPORT symbols exported
    -fstack-protector-strong    # stack canaries
)
