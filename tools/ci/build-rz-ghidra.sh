#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# build-rz-ghidra.sh — 构建 libcore_ghidra.so，可选地在四个 ABI 都齐全时跳过。
#
# build.yml / release.yml 原来直接 `bash build-rz-ghidra-android.sh`；test-v11.yml
# 多一层"四个 ABI 都在就跳过"的前置判断（配合它自己的 actions/cache）。两边的
# 差别只有那一层判断，所以这里用 --skip-existing 表达，默认关 = 与 build/release
# 完全一致。
set -euo pipefail

SKIP_EXISTING=0
for arg in "$@"; do
  case "$arg" in
    --skip-existing) SKIP_EXISTING=1 ;;
    *) echo "error: unknown argument '$arg'" >&2; exit 2 ;;
  esac
done

if [ "$SKIP_EXISTING" = "1" ]; then
  missing=0
  for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    test -f "app/src/main/jniLibs/$abi/libcore_ghidra.so" || missing=1
  done
  if [ "$missing" = "0" ]; then
    echo "[rz-ghidra] all four ABIs already present (cache hit); skipping"
    exit 0
  fi
fi

bash build-rz-ghidra-android.sh
