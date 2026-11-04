// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 【魔改】兼容层：AI 深度报告存储已移除
//
// 本版本没有内置 AI，也就没有 AI 深度报告。
// 保留同名 API 以兼容既有 UI 调用点，但全部返回空。
//
package com.soreverse.mcp.core

import android.content.Context

object DeepReportStore {

    fun ids(@Suppress("unused") context: Context): Set<String> = emptySet()

    fun save(
        @Suppress("unused") context: Context,
        @Suppress("unused") workspaceId: String,
        @Suppress("unused") report: String
    ) { /* no-op */ }

    fun load(@Suppress("unused") context: Context, @Suppress("unused") workspaceId: String): String? = null

    fun remove(@Suppress("unused") context: Context, @Suppress("unused") workspaceId: String) { /* no-op */ }
}
