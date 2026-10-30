#!/bin/bash
# FFmpeg configure/make 的宿主编译器。
# /usr/bin/clang 是硬链接 shim，会因「Xcode 许可证未接受」拒绝工作；
# 这里直接调 CLT 真 clang，并显式给 macOS SDK sysroot（否则找不到 ctype.h 等 libc 头）。
exec /Library/Developer/CommandLineTools/usr/bin/clang \
  --sysroot=/Library/Developer/CommandLineTools/SDKs/MacOSX.sdk "$@"
