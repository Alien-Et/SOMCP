#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# install-build-tools.sh — 原生构建所需的 apt 工具，一处定义。
#
# 四个工作流（build / release / test-v11 / blutter-engines）原来各写一份
# `apt-get install` 列表，而且**内容不同**：build.yml 带 cmake，其余三个不带；
# blutter-engines.yml 的包顺序又是另一种。三个构建工作流本该用同一套工具，
# 少一个包就会在某个步骤才炸（这正是 test-v11 曾经漏装 cmake;3.22.1 的同类
# 问题：装是装了，只是各装各的）。
#
# 这里的列表是四处的并集，语义上是"原生构建需要什么"，不是"某个工作流顺手
# 装过什么"：
#
#   meson ninja-build    Rizin / rz-ghidra 的构建系统
#   gcc g++ pkg-config   宿主侧编译与 pkg-config 探测
#   cmake                Blutter runner 与部分第三方树直接调用 PATH 上的 cmake
#
# 注意 cmake 这一项与 SDK 里的 cmake;3.22.1 不冲突也不重复：build-unidbg-native.sh
# 会**显式优先** $ANDROID_HOME/cmake/3.22.1/bin/cmake（见该脚本的 -CMake 解析），
# 只有 build-native-android.sh / build-rz-ghidra-android.sh 走 PATH —— 它们同时
# 用 tools/cmake-compat.sh 规范化老 CMakeLists 并传 -DCMAKE_POLICY_VERSION_MINIMUM，
# 所以 PATH 上是 4.x 也能过。apt 的 cmake 只是保证 PATH 上一定有一个可用版本。
set -euo pipefail

PACKAGES=(
  meson
  ninja-build
  gcc
  g++
  pkg-config
  cmake
)

echo "[ci] installing native build tools: ${PACKAGES[*]}"
sudo apt-get update
sudo apt-get install -y --no-install-recommends "${PACKAGES[@]}"
echo "[ci] native build tools ready"