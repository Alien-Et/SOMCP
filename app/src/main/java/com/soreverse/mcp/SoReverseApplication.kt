package com.soreverse.mcp

import android.app.Application
import android.content.Context
import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.ToolStats
import com.soreverse.mcp.nativecore.RizinNativeEngine

/**
 * 【魔改】已移除：
 *  - IntegrityGuard（全部完整性/威胁检测）
 *  - CrashReporter（崩溃上报）
 *  - LogReporter（日志上报 log-report-platform）
 *
 * 本版本不做任何联网上报，也不做任何签名/环境校验。
 */
class SoReverseApplication : Application() {

    override fun attachBaseContext(base: Context) {
        // 【魔改】不再做早期完整性校验
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val settings = SettingsStore(this)
        ToolStats.setPersistEnabled(settings.toolStatsPersist)
        ToolStats.attachContext(this)
        RizinNativeEngine.configureGhidra(this)
        AppLog.i("SOMCP (modified, no-report) initialized")
    }
}
