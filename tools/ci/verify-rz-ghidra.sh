#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# verify-rz-ghidra.sh — 断言四个 ABI 的 libcore_ghidra.so 都在 jniLibs 里。
#
# 三个构建工作流里这三段原本**逐字节相同**，所以抽出来没有语义风险；价值在于
# 以后加 ABI 或改目录只改一处。
#
# 为什么必须断言：libcore_ghidra.so 缺失不会让构建失败，只会让 read_disasm 在
# 运行时拿不到伪代码（日志里只有一行 "ghidra plugin missing"）。也就是说，没有
# 这一步，一个缺了反编译后端的 APK 可以一路绿灯发出去。
set -euo pipefail

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  test -f "app/src/main/jniLibs/$abi/libcore_ghidra.so" || {
    echo "::error::missing app/src/main/jniLibs/$abi/libcore_ghidra.so after build-rz-ghidra-android.sh"
    exit 1
  }
done
echo "libcore_ghidra.so present for all four ABIs."
