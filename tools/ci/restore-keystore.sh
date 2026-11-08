#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# restore-keystore.sh — 把 base64 的 release keystore 还原到 release/ 并校验可用。
#
# build.yml 与 release.yml 各写一份，两份**已经分叉**：
#
#   build.yml    printf '%s' | tr -d '[:space:]' | base64 -d   + keytool 校验
#   release.yml  printf '%s' | tr -d '[:space:]' | base64 -d   + keytool 校验
#   test-v11     echo "${{ secrets.KEYSTORE }}" | base64 -d    ← 没有 tr -d
#
# 也就是说 checks 走的那条路径少了去空白，而这正是历史上踩过的坑：
# secrets 里的尾随换行让 base64 -d 报 `invalid input`（docs/17 记录了这条）。
# 三处统一成带 tr -d 的版本。
#
# 环境变量（由调用方从 secrets 注入，绝不落盘成明文）：
#   KEYSTORE_B64   base64 的 keystore
#   KS_ALIAS       key alias
#   KS_ALIAS_PASS  key 密码
#   KS_STORE_PASS  store 密码
#   KS_OUT         输出路径（默认 release/so-reverse-mcp-release.jks）
#
# 另外导出 KEYSTORE_PROPERTIES（写了 storeFile/storePassword/keyAlias/keyPassword
# 的临时文件路径），供 --write-properties 使用 —— 只有 test-v11 的 apksigner
# 校验步骤需要它（它从文件读签名参数），build/release 直接用 env var。
set -euo pipefail

: "${KEYSTORE_B64:?KEYSTORE_B64 is required}"
: "${KS_ALIAS:?KS_ALIAS is required}"
: "${KS_ALIAS_PASS:?KS_ALIAS_PASS is required}"
: "${KS_STORE_PASS:?KS_STORE_PASS is required}"
KS_OUT="${KS_OUT:-release/so-reverse-mcp-release.jks}"
WRITE_PROPERTIES=0
for arg in "$@"; do
  case "$arg" in
    --write-properties) WRITE_PROPERTIES=1 ;;
    *) echo "error: unknown argument '$arg'" >&2; exit 2 ;;
  esac
done

mkdir -p "$(dirname "$KS_OUT")"

# 去空白是必需的：GitHub secrets 可能带尾随换行/空格，base64 -d 会
# `invalid input`。漏了这一句就是历史上那个 bug。
printf '%s' "$KEYSTORE_B64" | tr -d '[:space:]' | base64 -d > "$KS_OUT"

if ! keytool -list -keystore "$KS_OUT" \
     -storepass "$KS_STORE_PASS" -alias "$KS_ALIAS" >/dev/null 2>&1; then
  echo "::error::KEYSTORE 解码后校验失败：base64 不完整/被截断，或 ALIAS/PASSWORD 与密钥库不匹配。"
  echo "::error::KEYSTORE 字符数（去空白后）: $(printf '%s' "$KEYSTORE_B64" | tr -d '[:space:]' | wc -c)"
  exit 1
fi
echo "Restored and verified release signing keystore at $KS_OUT."

if [ "$WRITE_PROPERTIES" = "1" ]; then
  PROPS="release/keystore.properties"
  {
    echo "storeFile=$KS_OUT"
    echo "storePassword=$KS_STORE_PASS"
    echo "keyAlias=$KS_ALIAS"
    echo "keyPassword=$KS_ALIAS_PASS"
  } > "$PROPS"
  echo "Wrote $PROPS"
fi
