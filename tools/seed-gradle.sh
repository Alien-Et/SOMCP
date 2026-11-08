#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# seed-gradle.sh — 预置 Gradle 发行包,使 gradle/actions/setup-gradle 不必联网下载。
#
# 背景(实测 run 42469477147 / 42466197798):
#
#   Gradle 发行包的下载链是
#     https://services.gradle.org/distributions/gradle-<v>-bin.zip
#       --307--> https://github.com/gradle/gradle-distributions/releases/download/v<v>/gradle-<v>-bin.zip
#   而 GitHub release 资产的 CDN 会整段时间返回 504。setup-gradle 自己只重试
#   两次(约 35 秒)就把 job 判死,整条流水线在我们第一个业务步骤之前就红了;
#   「再放一条同样的 setup-gradle 步骤」也救不了 —— 第二次仍会重新 Provision,
#   打到同一个 504 的地址上(日志实测:第二次又连续两次 504)。
#
# 所以这里改成「我们自己把包取回来,并让 setup-gradle 直接复用」:
#
#   1. 从镜像取(默认腾讯云;可用 --mirror 指定),失败再回落官方地址;
#   2. 用**仓库里 pin 住的官方 sha256** 校验(不是从网络取 sha256 —— 那样等于
#      信任同一个投递通道)。镜像内容与官方哈希一致,已在
#      https://services.gradle.org/distributions/gradle-<v>-bin.zip.sha256 上核对过,
#      所以镜像只是一个更快、更少抖动的字节来源,完整性由哈希本身保证;
#   3. 解到 setup-gradle 自己的安装目录
#        $RUNNER_TEMP/.gradle-actions/gradle-installations/installs/gradle-<v>
#      并把 bin/ 追加进 $GITHUB_PATH。之后 action 的 installGradleVersion()
#      第一步 which gradle 就能命中同版本,直接打印
#      "Gradle version <v> is already available on PATH. Not installing.",
#      一个字节都不会去 services.gradle.org 拉;即使 PATH 命中失败,它退到
#      locateGradleAndDownloadIfRequired() 时也只会看到已存在的安装目录而复用。
#
# 用法(三个构建工作流用完全相同的调用行):
#   bash tools/seed-gradle.sh <version> <mirror-base-or-empty> <sha256>
#
# 幂等:目标目录里已有可执行的 bin/gradle 时直接复用,不重新下载。
set -euo pipefail

VERSION="${1:-}"
MIRROR="${2:-}"
SHA256="${3:-}"

if [[ -z "$VERSION" ]]; then
  echo "error: usage: seed-gradle.sh <version> <mirror-base-or-empty> <sha256>" >&2
  exit 2
fi
if [[ ! "$SHA256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "error: expected a 64-char lowercase hex sha256, got '${SHA256}'" >&2
  echo "       integrity verification is required, not optional" >&2
  exit 2
fi

ZIP_NAME="gradle-${VERSION}-bin.zip"

# 与 setup-gradle 的 getProvisionDir() 完全一致(RUNNER_TEMP 缺席时为 os.tmpdir())。
PROVISION_ROOT="${RUNNER_TEMP:-/tmp}/.gradle-actions/gradle-installations"
INSTALL_DIR="$PROVISION_ROOT/installs/gradle-${VERSION}"
DOWNLOAD_DIR="$PROVISION_ROOT/downloads"

if [[ -x "$INSTALL_DIR/bin/gradle" ]]; then
  echo "Gradle ${VERSION} already seeded at $INSTALL_DIR — skipping download."
  if [[ -n "${GITHUB_PATH:-}" ]]; then
    echo "$INSTALL_DIR/bin" >> "$GITHUB_PATH"
  fi
  exit 0
fi

mkdir -p "$DOWNLOAD_DIR" "$PROVISION_ROOT/installs"
ZIP_PATH="$DOWNLOAD_DIR/$ZIP_NAME"
rm -f "$ZIP_PATH"

# 候选顺序:镜像优先(实测 1.9s/20MB,稳定),官方兜底(会 307 到 GitHub 资产 CDN,
# 即本次故障点,但它是权威来源,留作最后一道)。
CANDIDATES=()
if [[ -n "$MIRROR" ]]; then
  CANDIDATES+=("${MIRROR%/}/$ZIP_NAME")
fi
CANDIDATES+=("https://services.gradle.org/distributions/$ZIP_NAME")

downloaded=""
for url in "${CANDIDATES[@]}"; do
  echo "Fetching $ZIP_NAME from $url"
  # -sS: no progress meter (it floods CI logs), but keep real errors on stderr.
  if curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 \
      --connect-timeout 20 --max-time 900 \
      -o "$ZIP_PATH" "$url"; then
    downloaded="$url"
    break
  fi
  echo "::warning::download failed from $url; trying the next source if any"
  rm -f "$ZIP_PATH"
done

if [[ -z "$downloaded" ]]; then
  echo "::error::could not download $ZIP_NAME from any of: ${CANDIDATES[*]}" >&2
  exit 1
fi

echo "Verifying sha256 of $ZIP_PATH (from $downloaded)"
actual="$(sha256sum "$ZIP_PATH" | awk '{print $1}')"
if [[ "$actual" != "$SHA256" ]]; then
  echo "::error::$ZIP_NAME sha256 mismatch: expected $SHA256, got $actual (source $downloaded)" >&2
  rm -f "$ZIP_PATH"
  exit 1
fi
echo "sha256 OK: $actual"

# 归档里只有顶层 gradle-<version>/ 一个目录,直接解到 installs/ 即得到
# installs/gradle-<version>/,与 action 期望的布局一致。
unzip -q -o "$ZIP_PATH" -d "$PROVISION_ROOT/installs"
rm -f "$ZIP_PATH"

if [[ ! -f "$INSTALL_DIR/bin/gradle" ]]; then
  echo "::error::after extracting $ZIP_NAME, $INSTALL_DIR/bin/gradle is missing" >&2
  ls -la "$PROVISION_ROOT/installs" >&2 || true
  exit 1
fi
chmod 755 "$INSTALL_DIR/bin/"* 2>/dev/null || true

# 端到端确认这个包真的能跑(不只是解压成功)。JDK 由前置的 setup-java 提供。
reported="$("$INSTALL_DIR/bin/gradle" --version 2>/dev/null \
  | sed -n 's/^Gradle \([0-9][0-9.]*\).*/\1/p' | head -n1)"
if [[ "$reported" != "$VERSION" ]]; then
  echo "::error::$INSTALL_DIR/bin/gradle --version reported '${reported:-<nothing>}', expected $VERSION" >&2
  exit 1
fi
echo "Seeded Gradle ${VERSION} at $INSTALL_DIR (gradle --version reports $reported)"

if [[ -n "${GITHUB_PATH:-}" ]]; then
  echo "$INSTALL_DIR/bin" >> "$GITHUB_PATH"
fi
