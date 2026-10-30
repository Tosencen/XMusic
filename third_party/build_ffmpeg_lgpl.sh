#!/bin/bash
# 编译 LGPL 版 FFmpeg（Android arm64-v8a）。
#
# 为什么是 LGPL：强制 --disable-gpl --disable-nonfree。已逐文件核对——
# WMA 解码（wmadec/wmaprodec/wmalosslessdec）与 ASF 解封装（asfdec_o/asfdec_f/asf）
# 全是 LGPL，所以这个配置**仍然包含它们**，不必为了播 WMA 把应用拖进 GPL。
#
# 为什么不开网络协议：媒体字节由我们自己的 DataSource/OkHttp 读出，
# 再经 avio 自定义回调喂给 FFmpeg，因此这里关掉 network，
# 不引入 openssl/mbedtls（省体积，也少一层许可证考量）。
set -euo pipefail

# /usr/bin/{make,cc,clang,install} 都是同一个硬链接 shim，会因「Xcode 许可证未接受」
# 拒绝工作。真实工具在 Command Line Tools 里，直接调用即可，无需 sudo 改系统配置。
CLT=/Library/Developer/CommandLineTools/usr/bin
[ -x "$CLT/make" ] || { echo "找不到 CLT make: $CLT/make"; exit 1; }
export PATH="$CLT:$PATH"

SRC=/Users/chen/Desktop/XMMusic/third_party/ffmpeg-7.1.1
OUT=/Users/chen/Desktop/XMMusic/third_party/ffmpeg-prebuilt
NDK=/Users/chen/Library/Android/sdk/ndk/27.0.12077973
API=26
ABI=arm64-v8a
JOBS=$(sysctl -n hw.ncpu)

TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/darwin-x86_64
CC=$TOOLCHAIN/bin/aarch64-linux-android${API}-clang
CXX=$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++
# 宿主编译器：CLT 真 clang + 显式 macOS SDK sysroot（见 host-cc.sh 注释）
HOSTCC=/Users/chen/Desktop/XMMusic/third_party/host-cc.sh

[ -x "$CC" ] || { echo "找不到 NDK clang: $CC"; exit 1; }
[ -x "$HOSTCC" ] || { echo "找不到宿主编译器: $HOSTCC"; exit 1; }

mkdir -p "$OUT/$ABI"
cd "$SRC"
make distclean >/dev/null 2>&1 || true

echo "=== configure (LGPL / $ABI / API $API) ==="
./configure \
  --prefix="$OUT/$ABI" \
  --target-os=android \
  --arch=aarch64 \
  --cpu=armv8-a \
  --enable-cross-compile \
  --cc="$CC" \
  --cxx="$CXX" \
  --host-cc="$HOSTCC" \
  --nm="$TOOLCHAIN/bin/llvm-nm" \
  --ar="$TOOLCHAIN/bin/llvm-ar" \
  --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
  --strip="$TOOLCHAIN/bin/llvm-strip" \
  --sysroot="$TOOLCHAIN/sysroot" \
  --enable-pic \
  --enable-static \
  --disable-shared \
  --disable-programs \
  --disable-doc \
  --disable-debug \
  --disable-symver \
  --enable-small \
  --disable-gpl \
  --disable-nonfree \
  --disable-avdevice \
  --disable-swscale \
  --disable-postproc \
  --disable-avfilter \
  --disable-everything \
  --enable-avutil \
  --enable-avcodec \
  --enable-avformat \
  --enable-swresample \
  --disable-network \
  --enable-demuxer=asf --enable-demuxer=asf_o \
  --enable-demuxer=mov --enable-demuxer=flac --enable-demuxer=wav \
  --enable-demuxer=ogg --enable-demuxer=mp3 --enable-demuxer=aac \
  --enable-demuxer=ape --enable-demuxer=aiff --enable-demuxer=wv \
  --enable-demuxer=tak --enable-demuxer=tta \
  --enable-decoder=wmav1 --enable-decoder=wmav2 --enable-decoder=wmapro \
  --enable-decoder=wmalossless \
  --enable-decoder=ape --enable-decoder=flac --enable-decoder=mp3float \
  --enable-decoder=aac --enable-decoder=vorbis --enable-decoder=opus \
  --enable-decoder=alac --enable-decoder=tta --enable-decoder=wavpack \
  --enable-decoder=tak \
  --enable-decoder=pcm_s16le --enable-decoder=pcm_s16be \
  --enable-decoder=pcm_s24le --enable-decoder=pcm_s24be \
  --enable-decoder=pcm_s32le --enable-decoder=pcm_s32be \
  --enable-decoder=pcm_f32le --enable-decoder=pcm_f32be \
  --enable-decoder=pcm_u8 --enable-decoder=pcm_s8 \
  --enable-parser=flac \
  --enable-parser=aac --enable-parser=mpegaudio --enable-parser=vorbis \
  --enable-parser=opus \
  --enable-protocol=file

echo
echo "=== 许可证确认（必须 LGPL，不能 GPL/nonfree） ==="
grep -E "FFMPEG_LICENSE|FFMPEG_CONFIGURATION" config.h | sed 's/^/  /'
LICENSE_LINE=$(grep "FFMPEG_LICENSE" config.h || true)
case "$LICENSE_LINE" in
  *LGPL*) echo "  → LGPL 校验通过" ;;
  *) echo "  !! 配置结果不是 LGPL（$LICENSE_LINE），中止"; exit 1 ;;
esac

echo
echo "=== make -j$JOBS ==="
make -j"$JOBS"
make install

echo
echo "=== 产物 ==="
ls -la "$OUT/$ABI/lib/"
echo "头文件:"; ls "$OUT/$ABI/include/"
echo
echo "完成: $OUT/$ABI"
