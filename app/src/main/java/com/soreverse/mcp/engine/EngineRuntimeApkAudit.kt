// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Runtime adapters for the APK audit tools (apk_sig_verify, apk_cert_info,
// apk_zip_anomaly, apk_abi_coverage). The input gate mirrors analyzeApk:
// path allow-list, size limit, memory guard, self-artifact rejection and the
// ZIP magic check happen before any parsing.
package com.soreverse.mcp.engine

import com.soreverse.mcp.core.RootShell
import com.soreverse.mcp.core.SelfArtifactGuard
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.nativecore.NativeProbe
import java.io.File
import org.json.JSONObject

/**
 * Loads APK bytes for the audit tools; returns the error envelope on any gate failure.
 *
 * 【修复】旧实现有两个问题：
 *  1. root-only 路径（/data/data 私有、权限 600）走 `RootShell.readBytes`
 *     一次性把整包读进堆，和 `local.readBytes()` 一样会吃掉整个包大小的堆。
 *  2. `MemoryGuard.ensureAnalysisMemory` 已被改成 no-op，所以没有任何
 *     前置保护，超过堆余量时直接 OutOfMemoryError，而不是一个可读的错误。
 *
 * 现在：
 *  - root-only 路径先流式落到 App 私有缓存，再由本地随机访问读入（等价于
 *    旧行为但至少能读到），临时文件用完整生命周期管理，读完即删。
 *  - 读入前用真实堆余量做一次检查，放不下就返回 `APK_TOO_LARGE_FOR_AUDIT`
 *    并提示改用流式的 `apk_analyze`，而不是让进程被 OOM 打死。
 */
internal const val AUDIT_MAX_HEAP_FRACTION = 0.45

/** 进程可用堆余量（字节）。 */
private fun heapHeadroomBytes(): Long {
    val runtime = Runtime.getRuntime()
    val free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
    return free.coerceAtLeast(0L)
}

/** 把 root-only 的 APK 流式落到 App 私有缓存，返回临时文件（调用方负责删除）。 */
private fun stageApkToTempFile(
    context: android.content.Context,
    path: String,
    declaredSize: Long
): File? {
    val dir = File(context.cacheDir, "apk-audit").apply { mkdirs() }
    val safeName = path.substringAfterLast('/').take(64).ifBlank { "apk" }
    val out = File(dir, "audit-${System.currentTimeMillis()}-$safeName")
    val stream = RootShell.openStream(path, declaredSize) ?: return null
    return try {
        stream.use { input ->
            out.outputStream().buffered().use { sink -> input.copyTo(sink, 64 * 1024) }
        }
        out
    } catch (t: Throwable) {
        runCatching { out.delete() }
        null
    }
}

internal fun EngineRuntime.loadApkAuditInput(path: String): Pair<ByteArray, JSONObject?> {
    if (path.isBlank()) return ByteArray(0) to err("INVALID_ARGUMENT", "APK path is required", "path", path)
    // 【魔改】路径白名单已废除：任意绝对路径都允许（root 可读 /data/data 等）。
    // 仅保留 ".." 穿越检测（防止相对路径越界导致的意外行为）。
    val normalized = path.replace('\\', '/')
    val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) {
        return ByteArray(0) to err("PATH_NOT_ALLOWED", "APK path must not contain '..'", "path", path)
    }
    val local = File(path)
    val localReadable = local.isFile && local.canRead()
    val declaredSize = if (localReadable) local.length() else (RootShell.fileSize(path) ?: -1L)
    if (declaredSize > ApkAnalyzer.MAX_INPUT_BYTES) {
        return ByteArray(0) to err(
            "APK_LIMIT_EXCEEDED",
            "APK exceeds ${ApkAnalyzer.MAX_INPUT_BYTES / 1024 / 1024} MiB input limit",
            "path",
            path
        )
    }
    // 【修复】读入前先确认堆放得下。审计工具需要整包随机访问，
    // 放不下时给明确错误而不是 OutOfMemoryError。
    if (declaredSize > 0) {
        val budget = (heapHeadroomBytes() * AUDIT_MAX_HEAP_FRACTION).toLong()
        if (declaredSize > budget) {
            return ByteArray(0) to err(
                "APK_TOO_LARGE_FOR_AUDIT",
                "APK is ${declaredSize / 1024 / 1024} MiB but only ${budget / 1024 / 1024} MiB of heap " +
                    "headroom is available for a whole-file audit. Use apk_analyze (streaming) instead, " +
                    "or free memory and retry.",
                "path",
                path
            )
        }
    }
    var staged: File? = null
    val bytes = try {
        if (localReadable) {
            local.readBytes()
        } else {
            // 【修复】root-only 路径：流式落临时文件再读，避免 readBytes 无上限
            val tmp = stageApkToTempFile(context, path, declaredSize)
                ?: return ByteArray(0) to err(
                    "APK_READ_FAILED",
                    if (RootShell.isAvailable()) {
                        "Unable to read APK through the root channel: $path"
                    } else {
                        "APK path is not readable and root is unavailable: $path"
                    },
                    "path",
                    path
                )
            staged = tmp
            tmp.readBytes()
        }
    } catch (error: ApkAnalysisLimitException) {
        staged?.let { runCatching { it.delete() } }
        return ByteArray(0) to err("APK_LIMIT_EXCEEDED", error.message ?: "APK exceeds analysis limits", "path", path)
    } catch (error: OutOfMemoryError) {
        staged?.let { runCatching { it.delete() } }
        return ByteArray(0) to err(
            "APK_TOO_LARGE_FOR_AUDIT",
            "Out of memory while loading the APK for a whole-file audit. Use apk_analyze (streaming) instead.",
            "path",
            path
        )
    } catch (error: Throwable) {
        staged?.let { runCatching { it.delete() } }
        return ByteArray(0) to err("APK_READ_FAILED", error.message ?: "Unable to read APK", "path", path)
    } finally {
        staged?.let { runCatching { it.delete() } }
    }
    if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) {
        return ByteArray(0) to err("APK_INVALID", "Input is not a ZIP/APK file", "path", path)
    }
    // 【魔改】ALLOW_SELF_ANALYSIS 放行时不拦
    if (!SelfArtifactGuard.ALLOW_SELF_ANALYSIS &&
        (isSelfApkBytes(bytes) || (local.isFile && NativeProbe.isOwnApk(local.absolutePath)))
    ) {
        return ByteArray(0) to selfForbidden("apk:$path")
    }
    return bytes to null
}

private fun EngineRuntime.apkAuditOk(path: String, bytes: ByteArray, payload: JSONObject): JSONObject = ok(
    JSONObject()
        .put("path", path)
        .put("size", bytes.size)
        .put("sha256", sha256(bytes))
        .put("audit", payload)
)

internal fun EngineRuntime.apkSigVerify(path: String): JSONObject = guarded {
    val (bytes, error) = loadApkAuditInput(path)
    if (error != null) return@guarded error
    apkAuditOk(path, bytes, ApkSigTools.verify(bytes))
}

internal fun EngineRuntime.apkCertInfo(path: String): JSONObject = guarded {
    val (bytes, error) = loadApkAuditInput(path)
    if (error != null) return@guarded error
    apkAuditOk(path, bytes, ApkSigTools.certInfo(bytes))
}

internal fun EngineRuntime.apkZipAnomaly(path: String): JSONObject = guarded {
    val (bytes, error) = loadApkAuditInput(path)
    if (error != null) return@guarded error
    apkAuditOk(path, bytes, ApkSigTools.zipAnomalies(bytes))
}

internal fun EngineRuntime.apkAbiCoverage(path: String): JSONObject = guarded {
    val (bytes, error) = loadApkAuditInput(path)
    if (error != null) return@guarded error
    apkAuditOk(path, bytes, ApkSigTools.abiCoverage(bytes))
}
