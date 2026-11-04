// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Runtime adapters for the APK audit tools (apk_sig_verify, apk_cert_info,
// apk_zip_anomaly, apk_abi_coverage). The input gate mirrors analyzeApk:
// path allow-list, size limit, memory guard, self-artifact rejection and the
// ZIP magic check happen before any parsing.
package com.soreverse.mcp.engine

import com.soreverse.mcp.core.MemoryGuard
import com.soreverse.mcp.core.RootShell
import com.soreverse.mcp.core.SelfArtifactGuard
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.nativecore.NativeProbe
import java.io.File
import org.json.JSONObject

/** Loads APK bytes for the audit tools; returns the error envelope on any gate failure. */
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
    if (local.isFile && local.length() > ApkAnalyzer.MAX_INPUT_BYTES) {
        return ByteArray(0) to err(
            "APK_LIMIT_EXCEEDED",
            "APK exceeds ${ApkAnalyzer.MAX_INPUT_BYTES / 1024 / 1024} MiB input limit",
            "path",
            path
        )
    }
    val bytes = try {
        if (local.isFile) {
            MemoryGuard.ensureAnalysisMemory(local.length(), "Analyzing APK ${local.name}")
            local.readBytes()
        } else {
            // 【魔改】本地文件不可读时，先用 root 直读
            val rootBytes = RootShell.readBytes(path)
            if (rootBytes != null) {
                rootBytes
            } else {
                val dir = workDir
                    ?: return ByteArray(0) to err(
                        "WORK_DIRECTORY_NOT_SELECTED",
                        "APK path is not a local file, root is unavailable and no work directory is selected",
                        "path",
                        path
                    )
                MemoryGuard.ensureAnalysisMemory(
                    dir.fileSize(path) ?: ApkAnalyzer.MAX_INPUT_BYTES,
                    "Analyzing APK $path"
                )
                dir.readFile(path, ApkAnalyzer.MAX_INPUT_BYTES)
            }
        }
    } catch (error: ApkAnalysisLimitException) {
        return ByteArray(0) to err("APK_LIMIT_EXCEEDED", error.message ?: "APK exceeds analysis limits", "path", path)
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
