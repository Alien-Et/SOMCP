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
        // 【修复 A】BlutterRunnerService 在 AndroidManifest 里声明为
        // `android:isolatedProcess="true"`（跑在 `:blutter` 隔离进程）。
        // 隔离进程里 UserManager 不可用，Android 禁止访问 SharedPreferences ——
        // 而下面这些 App 级初始化（AppLog.init / SettingsStore / ToolStats /
        // RizinNativeEngine.configureGhidra）全都要碰它。
        // 结果：runner 进程启动约 0.85 秒即抛
        //   IllegalStateException: SharedPreferences cannot be accessed if
        //   UserManager is not available. (e.g. from inside an isolated process)
        // → 进程死亡 → 上层只看到 RUNNER_CRASHED，且与 APK/版本/设备无关，
        //   flutter_blutter(action=analyze) 在任何输入上都跑不起来。
        // 隔离进程只需要 Binder 服务本身，跳过全部 App 级初始化即可。
        if (android.os.Process.isIsolated()) return
        AppLog.init(this)
        val settings = SettingsStore(this)
        ToolStats.setPersistEnabled(settings.toolStatsPersist)
        ToolStats.attachContext(this)
        RizinNativeEngine.configureGhidra(this)
        AppLog.i("SOMCP (modified, no-report) initialized")
    }
}
