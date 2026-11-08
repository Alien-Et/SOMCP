// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 【魔改】兼容层：自我分析限制已彻底废除
//
// 原实现禁止分析 SOMCP 自己的 APK / native 库（三层检测 + 签名块校验）。
// 本版本保留同名 API 以兼容既有调用点，但永不拦截。
//
package com.soreverse.mcp.core

import android.content.Context
import org.json.JSONObject

object SelfArtifactGuard {

    /** 始终放行。 */
    @Volatile
    var allowSelfAnalysis: Boolean = true

    data class Verdict(val value: String, val identifiers: List<String> = emptyList())

    fun isSelfArtifact(context: Context, path: String): Boolean = false

    fun isSelfApkPath(context: Context, path: String): Boolean = false

    fun isSelfApkPathAgainst(runningApks: List<String>, path: String): Boolean = false

    /** 永不从参数中找出「自我产物」。 */
    fun findSelfArg(context: Context, args: JSONObject): String? = null

    /** 永不从桥接结果中识别自我产物。 */
    fun ownArtifactFromBridgedResult(result: JSONObject): Verdict? = null

    /** 隔离（no-op）。 */
    fun quarantine(identifiers: List<String>) { /* no-op */ }

    /** 拒绝提示（保留以实现兼容，实际不会被调用）。 */
    fun forbidden(value: String, message: String): JSONObject = JSONObject()
        .put("error", "SELF_ANALYSIS_FORBIDDEN")
        .put("message", message)
        .put("path", value)
}
