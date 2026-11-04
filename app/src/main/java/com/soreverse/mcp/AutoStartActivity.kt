package com.soreverse.mcp

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.service.McpForegroundService

/**
 * 【魔改】外部 Activity 后台启动入口：
 * 接收 com.soreverse.mcp.AUTO_START intent，无需用户点击图标即可后台拉起 MCP 服务。
 * 用 NoDisplay 主题，启动后立即 finish，不显示界面。
 *
 * 用法：am start -n com.soreverse.mcp/.AutoStartActivity
 */
class AutoStartActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!McpForegroundService.isRunning()) {
            runCatching {
                McpForegroundService.start(this)
                AppLog.i("MCP service auto-started via AutoStartActivity")
            }.onFailure {
                AppLog.w("AutoStartActivity failed to start MCP service: ${it.message}")
            }
        } else {
            AppLog.i("MCP service already running, AutoStartActivity no-op")
        }
        finish()
    }
}
