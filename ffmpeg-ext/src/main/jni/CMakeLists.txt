#
# Copyright 2021 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# LINT.IfChange
cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)
# LINT.ThenChange(../../../build.gradle.kts)

# Enable C++11 features.
set(CMAKE_CXX_STANDARD 11)

project(libffmpegJNI C CXX)

# 自编的 LGPL FFmpeg 静态库（见 third_party/build_ffmpeg_lgpl.sh）。
# 相对本文件：jni → main → src → ffmpeg-ext → 仓库根
set(ffmpeg_prebuilt "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ffmpeg-prebuilt/${ANDROID_ABI}")
set(ffmpeg_binaries "${ffmpeg_prebuilt}/lib")

# avformat 是本项目新增：media3 原版只编解码，我们需要它来补 ASF/APE/AIFF 等容器解封装
foreach(ffmpeg_lib avutil swresample avcodec avformat)
    set(ffmpeg_lib_filename lib${ffmpeg_lib}.a)
    set(ffmpeg_lib_file_path ${ffmpeg_binaries}/${ffmpeg_lib_filename})
    add_library(
            ${ffmpeg_lib}
            STATIC
            IMPORTED)
    set_target_properties(
            ${ffmpeg_lib} PROPERTIES
            IMPORTED_LOCATION
            ${ffmpeg_lib_file_path})
endforeach()

include_directories(${ffmpeg_prebuilt}/include)
find_library(android_log_lib log)
# FFmpeg 的 ASF 解封装用 zlib 解压（configure 自动启用了 --enable-zlib）；
# Android 自带 libz，直接链上即可（zlib 许可证，无 GPL 风险）
find_library(z_lib z)

# asf_demux_jni.cc 是本项目新增：avformat 解封装桥接（media3 原版无此文件）
add_library(ffmpegJNI
            SHARED
            ffmpeg_jni.cc
            asf_demux_jni.cc)

set_target_properties(ffmpegJNI PROPERTIES
                      CXX_VISIBILITY_PRESET hidden
                      VISIBILITY_INLINES_HIDDEN ON)

target_link_libraries(ffmpegJNI
                      PRIVATE android
                      PRIVATE avformat
                      PRIVATE swresample
                      PRIVATE avcodec
                      PRIVATE avutil
                      PRIVATE ${z_lib}
                      PRIVATE ${android_log_lib})

# Additional flags needed for "arm64-v8a" from NDK 23.1.7779620 and above.
# See https://github.com/google/ExoPlayer/issues/9933#issuecomment-1029775358.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(ffmpegJNI PRIVATE "-Wl,-Bsymbolic")
endif()

