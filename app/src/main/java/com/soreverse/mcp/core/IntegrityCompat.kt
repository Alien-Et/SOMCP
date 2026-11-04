// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 【魔改】完整性相关辅助函数保留层
//
// IntegrityGuard 已删除，但 NativeProbe 等处仍引用指纹归一化函数。
// 这里保留原实现，保证编译通过。
//
package com.soreverse.mcp.core

/** 归一化指纹为小写十六进制（与原实现一致）。 */
internal fun normalizeFingerprint(value: String): String = value.filter {
    it.isLetterOrDigit()
}.lowercase()
