// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 【魔改】AI 相关数据类型保留层
//
// 本版本已移除内置 AI，但 AnalyzeTab 等 UI 仍引用这些类型
// （用于渲染历史/草稿）。这里只保留纯数据定义，不含任何 AI 逻辑。
//
package com.soreverse.mcp.core

/** 富文本片段（原 RikkaAgentEngine 中的定义）。 */
sealed interface RikkaPart {
    data class Text(val text: String) : RikkaPart
    data class Reasoning(val text: String) : RikkaPart
    data class Tool(
        val id: String,
        val name: String,
        val arguments: String,
        val result: String? = null,
        val index: Int = 0
    ) : RikkaPart
}

/** 深度分析事件（原 DeepAnalysisService 中的定义）。 */
data class DeepAnalysisEvent(
    val kind: Kind,
    val text: String,
    val toolName: String = ""
) {
    enum class Kind { STATUS, THINKING, TOOL, FINALIZING, TEXT, ERROR, DONE }
}
