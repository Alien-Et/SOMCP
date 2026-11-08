#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# verify-apk-signing.sh — 断言 release APK 带 v2/v3 签名，缺了就补签再验。
#
# 三个构建工作流各写一份，三段几乎逐字节相同，但**各自硬编码了
# `build-tools/36.0.0`**：那是 SOMCP_BUILD_TOOLS 的一份影子副本，守卫抓不到
# （它只查 `build-tools;\d` 这种 sdkmanager 形态）。一旦把 SOMCP_BUILD_TOOLS
# 升到 36.1.0，签名校验仍然指着 36.0.0 —— 要么路径不存在直接红，要么（更糟）
# 用的是旧版 apksigner 而 sdkmanager 装的是新版，验的与签的不是同一个工具。
# 这里改成从 $SOMCP_BUILD_TOOLS 推导（`build-tools;36.0.0` -> `36.0.0`）。
#
# 签名参数的两种来源：
#   默认          $STORE_FILE / $STORE_PASSWORD / $KEY_ALIAS / $KEY_PASSWORD
#                 （build.yml / release.yml 用的形态，密钥从不落盘成明文）
#   --properties  $1 指向 keystore.properties（test-v11 的形态：fork PR 没有
#                 secrets 时会换成一次性 CI 密钥，只能从文件读）
set -euo pipefail

PROPERTIES=""
while [ $# -gt 0 ]; do
  case "$1" in
    --properties) PROPERTIES="${2:?--properties needs a path}"; shift 2 ;;
    *) echo "error: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if [ -n "$PROPERTIES" ]; then
  [ -f "$PROPERTIES" ] || { echo "::error::$PROPERTIES not found" >&2; exit 1; }
  STORE_FILE=$(grep '^storeFile=' "$PROPERTIES" | head -1 | cut -d= -f2-)
  STORE_PASSWORD=$(grep '^storePassword=' "$PROPERTIES" | head -1 | cut -d= -f2-)
  KEY_ALIAS=$(grep '^keyAlias=' "$PROPERTIES" | head -1 | cut -d= -f2-)
  KEY_PASSWORD=$(grep '^keyPassword=' "$PROPERTIES" | head -1 | cut -d= -f2-)
fi

: "${STORE_FILE:?STORE_FILE (or --properties) is required}"
: "${STORE_PASSWORD:?STORE_PASSWORD (or --properties) is required}"
: "${KEY_ALIAS:?KEY_ALIAS (or --properties) is required}"
: "${KEY_PASSWORD:?KEY_PASSWORD (or --properties) is required}"

# 从 SOMCP_BUILD_TOOLS 推导 build-tools 目录名，避免再写一份版本号。
: "${SOMCP_BUILD_TOOLS:?SOMCP_BUILD_TOOLS is required}"
BUILD_TOOLS_VERSION="${SOMCP_BUILD_TOOLS#build-tools;}"
APKSIGNER="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/apksigner"
if [ ! -x "$APKSIGNER" ]; then
  echo "::error::apksigner not found at $APKSIGNER (SOMCP_BUILD_TOOLS=$SOMCP_BUILD_TOOLS)" >&2
  exit 1
fi

shopt -s nullglob
apks=(app/build/outputs/apk/release/*.apk)
if [ ${#apks[@]} -eq 0 ]; then
  echo "::error::no release APK found under app/build/outputs/apk/release/" >&2
  exit 1
fi

for apk in "${apks[@]}"; do
  echo "=== Checking signing schemes: $(basename "$apk") ==="
  if "$APKSIGNER" verify --verbose "$apk" 2>&1 | grep -qi "NOT PRESENT"; then
    echo "::warning::v2/v3 signing NOT verified in $(basename "$apk"); re-signing with apksigner"
    "$APKSIGNER" sign \
      --ks "$STORE_FILE" \
      --ks-pass "pass:$STORE_PASSWORD" \
      --ks-key-alias "$KEY_ALIAS" \
      --key-pass "pass:$KEY_PASSWORD" \
      --v1-signing-enabled true \
      --v2-signing-enabled true \
      --v3-signing-enabled true \
      --v4-signing-enabled true \
      "$apk"
  fi
  "$APKSIGNER" verify --print-certs "$apk"
  echo "OK: $(basename "$apk") has valid v2/v3 signing (apksigner $BUILD_TOOLS_VERSION)"
done
