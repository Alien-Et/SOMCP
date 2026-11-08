#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# cmake-compat.sh — 把 pin 住的老 CMake 工程规范化到现代 CMake 能配置的形态。
# source 进来用，不单独执行。
#
# 背景：CMake 4.x 把两件旧事从「兼容」改成「硬错误」：
#
#   1. `cmake_minimum_required(VERSION <3.5)` 直接失败：
#        Compatibility with CMake < 3.5 has been removed from CMake.
#   2. `cmake_policy(SET CMPxxxx OLD)` 也直接失败（不是警告）：
#        Policy CMP0051 may not be set to OLD behavior because this version of
#        CMake no longer supports it.
#
# 本项目 pin 的三棵老树全都踩：
#
#   capstone 4.0.2        根 CMakeLists：`VERSION 2.6` + `CMP0048 OLD`
#   keystone (内置 LLVM)  根 / llvm/ / kstool/ / samples/ 共 6 处：
#                         `VERSION 2.8.7`、`VERSION 2.8`、`CMP0051 OLD`
#   Dart VM 2.19.6        根 CMakeLists 的 min 版本低于 3.5
#
# GitHub 的 ubuntu runner 自带 CMake 4.x，所以只要没装 SDK 的 3.22.1 包，
# 这些工程就会在 configure 阶段挂掉（实测 test-v11 的 Unidbg 步骤就这样红过）。
#
# 这里同时做两件事：就地规范化这些 CMakeLists，并让调用方传
# `-DCMAKE_POLICY_VERSION_MINIMUM=3.5` 作为第二道保险（前者改声明，后者改
# CMake 自己的下限判定）。规范化是幂等的：第二次运行 patched 计数为 0。
#
# 只动 submodule 工作副本 / 下载下来的源码树，主仓库无改动。

# somcp_cmake_compat_policy_flag — 打印该传给 cmake 的下限逃生参数。
somcp_cmake_compat_policy_flag() {
  printf '%s\n' '-DCMAKE_POLICY_VERSION_MINIMUM=3.5'
}

# somcp_cmake_normalise <src-root> [<label>]
#   递归规范化 <src-root> 下所有 CMakeLists.txt（跳过 .git/）。
#   打印一行摘要到 stdout；没有改动时不打印。
#   返回 0 表示成功（无论是否改动），不因单个文件不可写而中止。
somcp_cmake_normalise() {
  local src="$1" label="${2:-$(basename "$1")}"
  if [[ ! -d "$src" ]]; then
    echo "error: somcp_cmake_normalise: not a directory: $src" >&2
    return 1
  fi

  # 两个模式必须一起判：只判 min 版本会漏掉「min 已经是 3.5 但还写着
  # CMP0051 OLD」的树（keystone 的 llvm/CMakeLists.txt 就是这种），
  # 反过来只判 policy 会漏掉纯 min 版本过低的树（capstone 根文件）。
  local needs='cmake_policy *\( *SET +CMP[0-9]+ +OLD *\)|cmake_minimum_required *\( *VERSION +([0-2](\.[0-9]+)*|3\.[0-4](\.[0-9]+)*) *\)'

  local patched=0 f
  while IFS= read -r f; do
    [[ -f "$f" ]] || continue
    grep -qE "$needs" "$f" 2>/dev/null || continue
    # `cmake_policy (SET ...)` 这种 policy 名和左括号之间带空格的写法在
    # capstone 4.0.2 里真实存在，所以 `cmake_policy` 后面必须是 ` *`。
    sed -i -E \
      -e 's/cmake_policy *\( *SET +(CMP[0-9]+) +OLD *\)/cmake_policy(SET \1 NEW)/g' \
      -e 's/cmake_minimum_required *\( *VERSION +[0-2](\.[0-9]+)* *\)/cmake_minimum_required(VERSION 3.5)/g' \
      -e 's/cmake_minimum_required *\( *VERSION +3\.[0-4](\.[0-9]+)* *\)/cmake_minimum_required(VERSION 3.5)/g' \
      "$f" || continue
    patched=$((patched + 1))
  done < <(find "$src" -name CMakeLists.txt -not -path '*/.git/*' -print 2>/dev/null)

  if [[ $patched -gt 0 ]]; then
    printf '[cmake-compat] normalised %d CMakeLists.txt under %s for modern CMake\n' \
      "$patched" "$label"
  fi
  return 0
}
