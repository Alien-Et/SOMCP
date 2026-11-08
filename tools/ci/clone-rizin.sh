#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# clone-rizin.sh — 取 rizin 最新 release tag 并浅克隆到 third_party/rizin-src。
#
# 三个构建工作流原来各写一份，而且**只有 build.yml 带重试**：release.yml 与
# test-v11.yml 是裸 `git clone`，一次网络抖动就让整条流水线红。同一个网络动作
# 不该有两种可靠性。这里统一成带重试的版本（成功路径完全不变，失败路径从
# "立即失败"变成"三次 + 15 秒间隔后失败，并给出可操作提示"）。
#
# 为什么**不 pin** 版本：rizin master 是移动靶，2026-09-15 的 #6736 已破坏
# rz-ghidra（RzCons* 参数变更）；rz-ghidra 只跟踪 rizin release，所以这里永远
# 取最新 release tag。pin 反而会让两边错位。
set -euo pipefail

RIZIN_REPO="https://github.com/rizinorg/rizin.git"
DEST="third_party/rizin-src"
ATTEMPTS=3

LATEST_TAG=$(git ls-remote --tags --sort=-v:refname "$RIZIN_REPO" 2>/dev/null \
  | grep -oE 'refs/tags/v[0-9]+\.[0-9]+\.[0-9]+$' \
  | head -1 | sed 's|refs/tags/||' || true)

if [ -z "$LATEST_TAG" ]; then
  echo "::error::无法获取 rizin 最新 release tag（git ls-remote 失败）"
  exit 1
fi
echo "::notice::Rizin 最新版本: $LATEST_TAG"

if [ -d "$DEST/librz/include" ]; then
  echo "[ci] $DEST already present; reusing"
  exit 0
fi

for attempt in $(seq 1 "$ATTEMPTS"); do
  if git clone --depth 1 --branch "$LATEST_TAG" "$RIZIN_REPO" "$DEST"; then
    echo "[ci] cloned rizin $LATEST_TAG"
    exit 0
  fi
  rm -rf "$DEST"
  if [ "$attempt" -ge "$ATTEMPTS" ]; then
    echo "::error::git clone rizin failed after $ATTEMPTS attempts (network unreachable from this runner; configure a proxy/mirror or pre-populate $DEST)"
    exit 1
  fi
  echo "[ci] rizin clone attempt $attempt failed; retrying in 15s"
  sleep 15
done
