// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 【魔改】兼容层：隧道功能已彻底移除
//
// 本版本只提供本地 + 局域网访问，不依赖任何外部隧道。
// 本类保留全部方法签名以兼容既有调用点，但一律为空操作。
//
package com.soreverse.mcp.core

import android.content.Context
import org.json.JSONObject

class CloudflareTunnelManager(
    @Suppress("unused") private val context: Context,
    @Suppress("unused") private val settings: SettingsStore
) {
    enum class Mode { OFF, QUICK, NAMED }

    enum class State { STOPPED, STARTING, RUNNING, ERROR }

    enum class BinaryState { MISSING, PRESENT, REMOVED }

    data class Status(
        val state: State = State.STOPPED,
        val publicUrl: String? = null,
        val message: String = "removed"
    )

    val state: State get() = State.STOPPED
    val publicUrl: String? get() = null
    val status: Status get() = Status()
    val tunnelStats: JSONObject get() = JSONObject().put("removed", true)

    fun start(mode: Mode, publicUrl: String? = null): Status = Status()

    fun start(targetPort: Int, mode: Mode, token: String?): Status = Status()

    fun stop() { /* no-op */ }

    fun snapshotJson(): JSONObject = JSONObject()
        .put("state", State.STOPPED.name)
        .put("removed", true)

    fun resetTunnelStats() { /* no-op */ }

    /** 【魔改】兼容：请求停止（no-op）。 */
    fun requestStop() { /* no-op */ }

    val binaryState: BinaryState get() = BinaryState.REMOVED

    fun binary(): java.io.File? = null
}
