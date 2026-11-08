#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# build-unidbg-natives.sh — 四个 ABI 的 capstone / keystone / unicorn，外加产出自检。
#
# 三个构建工作流原来各写一份这段循环，而且**已经分叉**：
#
#   build.yml      裸循环 + 自检，无跳过
#   release.yml    裸循环 + 自检，无跳过
#   test-v11.yml   带"该 ABI 产物齐全就跳过"的缓存命中逻辑 + 自检
#
# 同一件事三份实现，意味着 checks 验证的是"带跳过的那条路径"，而 release 跑的是
# "不带跳过的另一条路径"——checks 的绿灯证明不了 release 走的是同一条代码。
# 这里统一成一份，跳过逻辑用 --skip-existing 开关控制（默认关，与 build/release
# 原来完全一致；test-v11 传 --skip-existing）。
#
# 为什么默认关：build.yml / release.yml 没有 jniLibs 的 actions/cache 步骤，
# 全新 checkout 里这些 .so 一定不存在（.gitignore 只放行 libblutter_*.so 一个），
# 所以 --skip-existing 对它们也是空操作；默认关只是让"不做决定"这件事显式。
#
# 为什么不能省掉自检：unidbg 0.9.9 的 Maven 产物只带 linux_64 / linux_aarch64，
# 缺了这些 .so 的 APK 照样能签名成功，只在真机上 emulate_* 报
# EMULATOR_UNAVAILABLE。打包前必须断言。
set -euo pipefail

SKIP_EXISTING=0
for arg in "$@"; do
  case "$arg" in
    --skip-existing) SKIP_EXISTING=1 ;;
    *) echo "error: unknown argument '$arg'" >&2; exit 2 ;;
  esac
done

ALL_ABIS=(arm64-v8a armeabi-v7a x86 x86_64)

# unicorn (QEMU, __uint128_t) 只编 64 位 ABI；32 位 ABI 只有 capstone/keystone，
# Unidbg 在那里降级。
needed() {
  case "$1" in
    arm64-v8a|x86_64) echo "capstone keystone unicorn" ;;
    *)                echo "capstone keystone" ;;
  esac
}

for abi in "${ALL_ABIS[@]}"; do
  if [ "$SKIP_EXISTING" = "1" ]; then
    complete=1
    for lib in $(needed "$abi"); do
      test -f "app/src/main/jniLibs/$abi/lib$lib.so" || complete=0
    done
    if [ "$complete" = "1" ]; then
      echo "[unidbg-native] $abi already present (cache hit); skipping"
      continue
    fi
  fi
  bash build-unidbg-native.sh --abi "$abi"
done

for abi in arm64-v8a x86_64; do
  for lib in capstone keystone unicorn; do
    test -f "app/src/main/jniLibs/$abi/lib$lib.so" || {
      echo "::error::missing app/src/main/jniLibs/$abi/lib$lib.so after build-unidbg-native.sh"
      exit 1
    }
  done
done
for abi in armeabi-v7a x86; do
  for lib in capstone keystone; do
    test -f "app/src/main/jniLibs/$abi/lib$lib.so" || {
      echo "::error::missing app/src/main/jniLibs/$abi/lib$lib.so after build-unidbg-native.sh"
      exit 1
    }
  done
done
echo "Unidbg native libs present for required ABIs."
