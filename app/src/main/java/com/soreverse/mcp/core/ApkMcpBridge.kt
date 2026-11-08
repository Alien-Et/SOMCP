// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 【魔改】兼容层：外部 APK MCP 桥接已彻底移除
//
// SOMCP 本身就是 MCP 服务器，不需要再去连接别的 APK MCP 工具。
// 本类保留全部方法签名以兼容既有调用点，但一律返回空/离线。
//
package com.soreverse.mcp.core

import org.json.JSONObject

class ApkMcpBridge(@Suppress("unused") private val settings: SettingsStore) {

    companion object {
        const val DEFAULT_PORT = 8787
        const val MT_PREFIX = "mt"
        const val NP_PREFIX = "np"

        fun prefixLabel(prefix: String): String = when (prefix) {
            MT_PREFIX -> "MT"
            NP_PREFIX -> "NP"
            else -> prefix
        }
    }

    data class ProbeResult(val online: Boolean, val message: String = "removed")
    data class BridgeState(val online: Boolean = false, val message: String = "removed")

    val enabled: Boolean get() = false
    val online: Boolean get() = false

    fun autoDiscover(port: Int): ProbeResult = ProbeResult(false)
    fun probe(): ProbeResult = ProbeResult(false)
    fun ping(): Boolean = false

    fun state(): BridgeState = BridgeState()
    fun snapshotJson(): JSONObject = JSONObject()
        .put("enabled", false)
        .put("online", false)
        .put("removed", true)

    fun mergedTools(): List<JSONObject> = emptyList()
    fun bridgedPrefix(): String = ""
    fun allPrefixes(): List<String> = emptyList()
    fun isBridgedTool(name: String): Boolean = false
    fun callTool(name: String, args: JSONObject): JSONObject = JSONObject().put("error", "APK MCP bridge removed")

    fun startHealthMonitor() { /* no-op */ }
    fun stopHealthMonitor() { /* no-op */ }
}
