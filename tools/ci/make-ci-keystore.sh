#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# make-ci-keystore.sh — 没有 secrets 时（如 fork PR）生成一次性签名密钥。
#
# 只有 test-v11.yml 需要它：fork 的 pull_request 拿不到 repository secrets，
# 但 checks 仍要跑 assembleRelease 来证明"release 能构建"。生成的密钥每次运行
# 都不同，签出的 APK **不能**覆盖安装正式版（docs/forum-post.md 记了这条），
# 仅用于构建验证。
#
# 用法：make-ci-keystore.sh [--write-properties]
set -euo pipefail

KS_OUT="${KS_OUT:-release/so-reverse-mcp-release.jks}"
WRITE_PROPERTIES=0
for arg in "$@"; do
  case "$arg" in
    --write-properties) WRITE_PROPERTIES=1 ;;
    *) echo "error: unknown argument '$arg'" >&2; exit 2 ;;
  esac
done

mkdir -p "$(dirname "$KS_OUT")"

STORE_PASS="ci-$(openssl rand -hex 16)"
keytool -genkeypair -v \
  -storetype JKS \
  -keystore "$KS_OUT" \
  -alias so-reverse-mcp \
  -keyalg RSA \
  -keysize 2048 \
  -validity 36500 \
  -storepass "$STORE_PASS" \
  -keypass "$STORE_PASS" \
  -dname "CN=SOMCP CI, O=SoReverse MCP, C=CN"

echo "Generated CI-only signing keystore at $KS_OUT (not for distribution)."

if [ "$WRITE_PROPERTIES" = "1" ]; then
  PROPS="release/keystore.properties"
  {
    echo "storeFile=$KS_OUT"
    echo "storePassword=$STORE_PASS"
    echo "keyAlias=so-reverse-mcp"
    echo "keyPassword=$STORE_PASS"
  } > "$PROPS"
  echo "Wrote $PROPS"
fi
