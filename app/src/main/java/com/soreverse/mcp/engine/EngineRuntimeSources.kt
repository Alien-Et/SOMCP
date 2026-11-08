// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Copyright (C) 2026 bilieebiliee1-design
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.
//
package com.soreverse.mcp.engine

import android.net.Uri
import com.soreverse.mcp.BuildConfig
import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.InsufficientMemoryException
import com.soreverse.mcp.core.MemoryGuard
import com.soreverse.mcp.core.RootShell
import com.soreverse.mcp.core.SelfArtifactGuard
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.nativecore.NativeEngine
import com.soreverse.mcp.nativecore.NativeProbe
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 【魔改】无工作目录时，root 扫描的候选根目录。
 * 顺序即优先级；扫描深度见 listAvailableSos。
 */
internal val ROOT_SCAN_ROOTS = listOf(
    "/data/local/tmp",
    "/data/app",
    "/storage/emulated/0",
    "/sdcard"
)

internal fun EngineRuntime.setWorkDirectory(uri: Uri) {
    if (workDirUri == uri && workDir != null) return
    workDirUri = uri
    workDir = WorkDirectory(context, uri)
    sources = emptyList()
    sourceFingerprint = emptyList()
    sourceSummaryCache.clear()
    workspaceBySourceKey.clear()
    pageStore.clear()
    searchCache.clear()
    AppLog.i("Work directory selected: ${WorkDirectory.displayPath(uri)}")
}

internal fun EngineRuntime.listAvailableSos(prefix: String = "", limit: Int = 50, cursor: String = ""): JSONObject = guarded {
    val boundedLimit = limit.coerceIn(1, 500)
    val start = cursor.removePrefix("source:").toIntOrNull()?.coerceAtLeast(0) ?: 0

    // 【魔改】没有工作目录时，用 root 扫描常见 SO 目录
    val dir = workDir
    if (dir == null) {
        // KernelSU/Magisk 按 App uid 授权；没在管理器里给本 App 开 root 时
        // isAvailable() 会是 false，walkFiles 直接返回空 —— 明确回报状态，
        // 免得 list 返回「rootMode:true 但 0 项」让人误以为扫描逻辑坏了。
        val rootAvailable = RootShell.isAvailable()
        val roots = ROOT_SCAN_ROOTS
        val all = mutableListOf<SoSource>()
        if (rootAvailable) {
            for (root in roots) {
                if (all.size >= 2000) break
                RootShell.walkFiles(root, listOf(".so"), maxDepth = 6) { e ->
                    if (all.size < 2000) {
                        all.add(SoSource(e.path, "root_fs", e.name, e.size, e.modified, null))
                    }
                }
            }
        }
        val filtered = all.filter {
            prefix.isBlank() || it.path.startsWith(prefix) || it.name.startsWith(prefix)
        }
        val items = JSONArray()
        filtered.asSequence().drop(start).take(boundedLimit).forEach { src ->
            items.put(
                JSONObject()
                    .put("path", src.path)
                    .put("filePath", src.path)
                    .put("name", src.name)
                    .put("size", src.size)
                    .put("source", src.source)
            )
        }
        val next = if (start + boundedLimit < filtered.size) "source:${start + boundedLimit}" else ""
        return@guarded JSONObject()
            .put("items", items)
            .put("returnedCount", items.length())
            .put("totalAvailableCount", filtered.size)
            .put("nextCursor", next)
            .put("rootMode", true)
            .put("rootAvailable", rootAvailable)
            .put(
                "usage",
                if (rootAvailable) {
                    "root scan (no work directory). Roots: ${ROOT_SCAN_ROOTS.joinToString()}."
                } else {
                    "ROOT NOT GRANTED: give com.soreverse.mcp superuser access in KernelSU/Magisk manager, then restart the app. Direct so_open(path=...) still works for readable paths."
                }
            )
    }

    val currentSources = ensureSources(dir)
    val filtered = currentSources.filter {
        prefix.isBlank() ||
            it.path.startsWith(prefix) ||
            it.name.startsWith(prefix)
    }
    val items = JSONArray()
    filtered.asSequence()
        .drop(start)
        .take(boundedLimit)
        .forEach { src ->
            val meta = sourceSummary(dir, src)
            items.put(
                JSONObject()
                    .put("path", src.path)
                    .put("filePath", src.path)
                    .put("openPath", src.path)
                    .put("source", src.source)
                    .put("apkPath", src.apkPath)
                    .put("apkEntry", src.apkEntry)
                    .put("abi", src.abi)
                    .put("size", src.size)
                    .put("modified", src.modified)
                    .put("architecture", meta.architecture)
                    .put("bits", meta.bits)
                    .put("endian", meta.endian)
                    .put("soname", JSONObject.NULL)
                    .put("hasDebugInfo", meta.hasDebugInfo)
                    .put("stripped", meta.stripped)
            )
        }
    val nextOffset = start + items.length()
    val nextCursor = if (nextOffset < filtered.size) "source:$nextOffset" else null
    ok(
        JSONObject()
            .put("items", items)
            .put(
                "usage",
                "Call so_open with path or filePath from any item. Use the returned workspaceId for the other tools."
            )
            .put(
                "pagination",
                pagination(
                    nextCursor != null,
                    nextCursor,
                    items.length(),
                    boundedLimit,
                    filtered.size
                )
            )
    )
}

internal fun EngineRuntime.open(path: String, temporary: Boolean): JSONObject = guarded {
    if (path.isBlank()) {
        return@guarded err(
            "INVALID_ARGUMENT",
            "Missing SO path. Pass path or filePath from so_open (action=list).",
            "path",
            path
        )
    }
    val ws = openWorkspace(path, temporary)
    val elf = ws.elf
    val src = ws.source
    val symbolFunctions = (elf.symbols + elf.dynSymbols).filter {
        it.type == "FUNC" && !it.imported
    }.distinctBy {
        it.name to
            it.value
    }
    val exportedFunctions = elf.dynSymbols.filter {
        it.type == "FUNC" &&
            !it.imported &&
            it.value > 0
    }.distinctBy { it.name to it.value }
    val analyzedFunctions = if (NativeEngine.active().available()) {
        runCatching {
            JSONArray(NativeEngine.active().functions(ws.data, elf.architecture)).length()
        }.getOrDefault(symbolFunctions.size)
    } else {
        symbolFunctions.size
    }
    val pltStubs = elf.relocations.count { it.section.contains("plt", true) }
    ok(
        JSONObject()
            .put("workspaceId", ws.id)
            .put("temporary", temporary)
            .put("soFileName", src.name)
            .put("source", src.source)
            .put("inputPath", src.path)
            .put("apkPath", src.apkPath)
            .put("apkEntry", src.apkEntry)
            .put("abi", src.abi)
            .put("architecture", elf.architecture)
            .put("bits", elf.bits)
            .put("endian", elf.endian)
            .put("elfType", "ET_${elf.type}")
            .put("machine", elf.machineName)
            .put("entryPoint", hex(elf.entry))
            .put(
                "analysisInput",
                JSONObject().put(
                    "source",
                    ws.analysisInputSource
                ).put(
                    "originalSha256",
                    ws.originalSha256
                ).put(
                    "analysisSha256",
                    sha256(ws.data)
                ).put("structureRecovery", ws.structureRecovery)
            )
            .put(
                "counts",
                JSONObject().put(
                    "sections",
                    elf.sections.size
                ).put(
                    "symbols",
                    elf.symbols.size
                ).put(
                    "dynsyms",
                    elf.dynSymbols.size
                ).put(
                    "relocations",
                    elf.relocations.size
                ).put(
                    "functions",
                    symbolFunctions.size
                ).put(
                    "functionsMeaning",
                    "symbolFunctions"
                ).put(
                    "symbolFunctions",
                    symbolFunctions.size
                ).put(
                    "exportedFunctions",
                    exportedFunctions.size
                ).put(
                    "analyzedFunctions",
                    analyzedFunctions
                ).put("pltStubs", pltStubs).put("strings", elf.strings.size)
            )
            .put(
                "capabilities",
                JSONObject().put(
                    "canDisassemble",
                    true
                ).put(
                    "canEditAsm",
                    true
                ).put("canEditHex", true).put("canResolveRelocs", elf.relocations.isNotEmpty()).put(
                    "hasPltGot",
                    elf.sections.any {
                        it.name in
                            setOf(".plt", ".got")
                    }
                ).put("canSearchStrings", elf.strings.isNotEmpty()).put(
                    "hasDebugInfo",
                    elf.sections.any {
                        it.name.startsWith(".debug")
                    }
                ).put(
                    "hasEhFrame",
                    elf.sections.any {
                        it.name in
                            setOf(".eh_frame", ".ARM.exidx")
                    }
                )
            )
            .put("checksums", checksums(ws.data))
    )
}

internal fun EngineRuntime.analyzeApk(path: String, entryLimit: Int = 500): JSONObject = guarded {
    if (path.isBlank()) return@guarded err("INVALID_ARGUMENT", "APK path is required", "path", path)
    val local = File(path)
    // 【修复】旧实现只要 root 可用就先 openStream 探一次、关掉、再让
    // analyzeRootStream 自己 openStream 第二次 —— 每个大 APK 要起两次
    // su + cat，且第一次读进 pipe 的数据被直接丢弃。现在改成：
    //   1) 能被 App 直接读取的普通文件走 analyzeFile（随机访问、解析完整）
    //   2) 否则才走 root 流（且只开一次）
    val directReadable = local.isFile && local.canRead()
    if (directReadable) {
        if (local.length() > ApkAnalyzer.MAX_INPUT_BYTES) {
            return@guarded err(
                "APK_LIMIT_EXCEEDED",
                "APK exceeds ${ApkAnalyzer.MAX_INPUT_BYTES / 1024 / 1024} MiB input limit",
                "path",
                path
            )
        }
        return@guarded try {
            if (!com.soreverse.mcp.core.SelfArtifactGuard.allowSelfAnalysis &&
                NativeProbe.isOwnApk(local.absolutePath)
            ) {
                selfForbidden("apk:$path")
            } else {
                ok(ApkAnalyzer.analyzeFile(local, path, entryLimit))
            }
        } catch (error: ApkNotZipException) {
            err("APK_INVALID", error.message ?: "Input is not a ZIP/APK file", "path", path)
        } catch (error: ApkAnalysisLimitException) {
            err("APK_LIMIT_EXCEEDED", error.message ?: "APK exceeds analysis limits", "path", path)
        }
    }
    // 到这里说明 App 读不到（分区存储、/data/data 私有、权限 600 等），
    // 有 root 就走流式，否则才落到工作目录兜底。
    if (RootShell.isAvailable()) {
        return@guarded try {
            ok(ApkAnalyzer.analyzeRootStream(path, entryLimit))
        } catch (error: ApkNotZipException) {
            err("APK_INVALID", error.message ?: "Input is not a ZIP/APK file", "path", path)
        } catch (error: ApkAnalysisLimitException) {
            err("APK_LIMIT_EXCEEDED", error.message ?: "APK exceeds analysis limits", "path", path)
        } catch (error: java.io.IOException) {
            err(
                "APK_READ_FAILED",
                error.message ?: "Unable to read APK through the root channel",
                "path",
                path
            )
        }
    }
    // 既不可直读、也没有 root：最后才用工作目录（SAF）。
    if (local.isFile && local.length() > ApkAnalyzer.MAX_INPUT_BYTES) {
        return@guarded err(
            "APK_LIMIT_EXCEEDED",
            "APK exceeds ${ApkAnalyzer.MAX_INPUT_BYTES / 1024 / 1024} MiB input limit",
            "path",
            path
        )
    }
    val bytes = try {
        if (local.isFile) {
            // 【魔改】路径白名单已废除：普通文件直读，异常/空都走 root 兜底
            val direct = runCatching { local.readBytes() }.getOrNull()
            val read = direct?.takeIf { it.isNotEmpty() }
                ?: RootShell.readBytes(path)
            if (read == null) throw java.io.IOException("Unable to read APK: $path (root unavailable or unreadable)")
            read
        } else {
            val workDirForApk =
                workDir
                    ?: return@guarded err(
                        "WORK_DIRECTORY_NOT_SELECTED",
                        "APK path is not readable and no work directory is selected",
                        "path",
                        path
                    )
            MemoryGuard.ensureAnalysisMemory(
                workDirForApk.fileSize(path) ?: ApkAnalyzer.MAX_INPUT_BYTES,
                "Analyzing APK $path"
            )
            workDirForApk.readFile(path, ApkAnalyzer.MAX_INPUT_BYTES)
        }
    } catch (error: ApkAnalysisLimitException) {
        return@guarded err(
            "APK_LIMIT_EXCEEDED",
            error.message ?: "APK exceeds analysis limits",
            "path",
            path
        )
    } catch (error: java.io.IOException) {
        return@guarded err(
            "APK_READ_FAILED",
            error.message ?: "Unable to read APK: $path",
            "path",
            path
        )
    }
    if (bytes.size < 4 ||
        bytes[0] != 0x50.toByte() ||
        bytes[1] != 0x4b.toByte()
    ) {
        return@guarded err("APK_INVALID", "Input is not a ZIP/APK file", "path", path)
    }
    // 【魔改】allowSelfAnalysis 放行时不拦
    if (!com.soreverse.mcp.core.SelfArtifactGuard.allowSelfAnalysis &&
        (isSelfApkBytes(bytes) || (local.isFile && NativeProbe.isOwnApk(local.absolutePath)))
    ) {
        return@guarded selfForbidden("apk:$path")
    }
    try {
        ok(ApkAnalyzer.analyze(bytes, path, entryLimit))
    } catch (error: ApkNotZipException) {
        err("APK_INVALID", error.message ?: "Input is not a ZIP/APK file", "path", path)
    } catch (error: ApkAnalysisLimitException) {
        err("APK_LIMIT_EXCEEDED", error.message ?: "APK exceeds analysis limits", "path", path)
    }
}

internal fun EngineRuntime.openUrl(url: String, outputName: String = "", temporary: Boolean = false): JSONObject = guarded {
    // 【修复】旧实现强制要求 SAF 工作目录，而魔改版已经把选目录的 UI 删掉了，
    // 于是 open_url 永久不可用。现在改为：有工作目录就写工作目录，
    // 没有就落到 App 自己的外部私有目录（不需要任何存储权限）。
    val dir = workDir
    val parsed =
        runCatching { URL(url.trim()) }.getOrNull()
            ?: return@guarded err("INVALID_ARGUMENT", "url must be a valid http(s) URL", "url", url)
    if (parsed.protocol !in
        setOf("http", "https")
    ) {
        return@guarded err(
            "UNSUPPORTED_URL_SCHEME",
            "Only http and https URLs are supported",
            "url",
            url
        )
    }
    runCatching { java.net.InetAddress.getAllByName(parsed.host) }.getOrNull()?.let { addresses ->
        if (addresses.any {
                it.isLoopbackAddress ||
                    it.isAnyLocalAddress ||
                    it.isLinkLocalAddress ||
                    it.isSiteLocalAddress ||
                    it.isMulticastAddress
            }
        ) {
            return@guarded err(
                "URL_HOST_NOT_ALLOWED",
                "Refusing to download from a private, loopback, or link-local address",
                "url",
                url
            )
        }
    }
    val timeout = SettingsStore(context).requestTimeoutMs
    val conn = (parsed.openConnection() as HttpURLConnection).apply {
        connectTimeout =
            timeout.coerceAtMost(30_000)
        readTimeout = timeout
        instanceFollowRedirects = true
        requestMethod =
            "GET"
    }
    val status = conn.responseCode
    if (status !in
        200..299
    ) {
        return@guarded err(
            "DOWNLOAD_FAILED",
            "HTTP download failed with status $status",
            "url",
            url
        )
    }
    val maxBytes = soDownloadMaxBytes()
    val maxMiB = maxBytes / (1024L * 1024L)
    if (conn.contentLengthLong >
        maxBytes
    ) {
        return@guarded err(
            "DOWNLOAD_TOO_LARGE",
            "SO download exceeds $maxMiB MiB limit",
            "contentLength",
            conn.contentLengthLong
        )
    }
    val bytes = conn.inputStream.use { input ->
        java.io.ByteArrayOutputStream().apply {
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n <
                    0
                ) {
                    break
                }
                total += n
                if (total >
                    maxBytes
                ) {
                    return@guarded err(
                        "DOWNLOAD_TOO_LARGE",
                        "SO download exceeds $maxMiB MiB limit",
                        "url",
                        url
                    )
                }
                write(buf, 0, n)
            }
        }.toByteArray()
    }
    // 【魔改】allowSelfAnalysis 放行时不拦
    if (!com.soreverse.mcp.core.SelfArtifactGuard.allowSelfAnalysis && isSelfApkBytes(bytes)) {
        return@guarded selfForbidden("url:$url")
    }
    if (bytes.size < 4 ||
        bytes[0] != 0x7f.toByte() ||
        bytes[1] != 'E'.code.toByte() ||
        bytes[2] != 'L'.code.toByte() ||
        bytes[3] != 'F'.code.toByte()
    ) {
        return@guarded err("NOT_ELF_SO", "Downloaded file is not an ELF/SO file", "url", url)
    }
    val rawName = outputName.ifBlank {
        parsed.path.substringAfterLast('/').substringBefore('?').ifBlank { "downloaded.so" }
    }
    val safeName = rawName.substringAfterLast('/').substringAfterLast('\\').let {
        if (it.endsWith(".so", ignoreCase = true)) it else "$it.so"
    }
    val source = if (dir != null) {
        dir.writeRootFile(safeName, bytes).also {
            sources = (sources.filterNot { s -> s.path == it.path } + it).sortedBy { s -> s.path }
            sourceFingerprint = emptyList()
            sourceSummaryCache.clear()
        }
    } else {
        // 【修复】没有 SAF 工作目录时的落点：App 外部私有目录
        // (/storage/emulated/0/Android/data/com.soreverse.mcp/files)。
        // 这个目录不需要任何存储权限，也不会污染用户的相册/文件管理器。
        val outDir = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
        outDir.mkdirs()
        val out = java.io.File(outDir, safeName)
        out.writeBytes(bytes)
        SoSource(
            path = out.absolutePath,
            source = "build_output",
            name = out.name,
            size = bytes.size.toLong(),
            modified = out.lastModified(),
            treeDocumentUri = null,
            apkPath = null,
            apkEntry = null,
            abi = null
        )
    }
    open(
        source.path,
        temporary
    ).put(
        "download",
        JSONObject().put(
            "url",
            url
        ).put(
            "savedAs",
            source.path
        ).put("size", bytes.size).put("sha256_16", sha256(bytes).take(16))
    )
}

/**
 * Dynamic limit for a single SO download, derived from the process max heap and
 * the work-directory free space. A downloaded ELF is later read fully into
 * memory (plus LIEF/xanso parsing copies), so on capable devices the cap scales
 * up with available heap, while low-memory devices degrade gracefully with a
 * smaller cap instead of crashing with an OutOfMemoryError.
 */
internal fun EngineRuntime.soDownloadMaxBytes(): Long {
    // 【魔改】下载上限放开：不再按堆大小/磁盘余量收紧，固定 8 GiB 天花板。
    // 真实的内存压力交给 JVM OOM + MemoryGuard(ENFORCE=false) 兜底。
    return 8L * 1024L * 1024L * 1024L
}

/** True when [bytes] carries SOMCP's own package identifier (`BuildConfig.APPLICATION_ID`). */
internal fun containsPackageIdentifier(bytes: ByteArray): Boolean {
    val marker = BuildConfig.APPLICATION_ID
    if (marker.isBlank()) return false
    val ascii = marker.toByteArray(Charsets.US_ASCII)
    val utf16le = marker.toByteArray(Charsets.UTF_16LE)
    return containsSubsequence(bytes, ascii) || containsSubsequence(bytes, utf16le)
}

private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
    if (needle.isEmpty() || haystack.size < needle.size) return false
    for (i in 0..(haystack.size - needle.size)) {
        var j = 0
        while (j < needle.size && haystack[i + j] == needle[j]) j++
        if (j == needle.size) return true
    }
    return false
}

/** Returns a forbidden error when the target is recognised as SOMCP's own artifact. */
internal fun EngineRuntime.selfForbidden(desc: String): JSONObject = err(
    "SELF_ANALYSIS_FORBIDDEN",
    "SOMCP cannot analyze its own artifact (official signature or package ${BuildConfig.APPLICATION_ID})",
    "reason",
    desc
)

/** Byte-level check: true when [bytes] is SOMCP's own artifact via the package marker. */
internal fun EngineRuntime.isSelfApkBytes(bytes: ByteArray): Boolean = containsPackageIdentifier(bytes)

internal fun EngineRuntime.listWorkspaces(): JSONObject = guarded {
    val items = JSONArray()
    workspaces.values.sortedBy {
        it.source.path
    }.forEach { ws ->
        items.put(
            JSONObject().put(
                "workspaceId",
                ws.id
            ).put(
                "path",
                ws.source.path
            ).put(
                "filePath",
                ws.source.path
            ).put(
                "soFileName",
                ws.source.name
            ).put(
                "source",
                ws.source.source
            ).put(
                "apkPath",
                ws.source.apkPath
            ).put(
                "apkEntry",
                ws.source.apkEntry
            ).put(
                "abi",
                ws.source.abi
            ).put(
                "architecture",
                ws.elf.architecture
            ).put("bits", ws.elf.bits).put("temporary", ws.temporary)
        )
    }
    ok(JSONObject().put("items", items).put("count", items.length()))
}

internal fun EngineRuntime.close(workspaceId: String): JSONObject = guarded {
    // Release emulator sessions bound to this workspace so their native
    // unidbg VMs (and the in-memory copy of the SO they hold) are freed
    // immediately instead of lingering after so_close.
    emulatorSessions.entries.removeAll { (_, session) ->
        if (session.workspaceId == workspaceId) {
            session.live?.let(unidbg::closeSession)
            true
        } else {
            false
        }
    }
    workspaces.remove(workspaceId)
    pageStore.clear()
    searchCache.clear()
    AppLog.i("Closed $workspaceId")
    ok(JSONObject().put("success", true))
}

internal fun EngineRuntime.clearCaches(dropWorkspaces: Boolean = false) {
    emulatorSessions.values.forEach { session -> session.live?.let(unidbg::closeSession) }
    emulatorSessions.clear()
    frida.closeAll()
    // 【修复】工作区不是缓存：它持有已解析的 ELF 与编辑会话。旧实现无条件
    // `workspaces.clear()`，于是 cache_clear（schema 只承诺清「扫描缓存/分页缓存」）
    // 会静默作废所有 workspaceId / editSessionId。实测复现：so_open → analyze_elf 正常
    // → cache_clear → analyze_elf 立刻 WORKSPACE_NOT_FOUND；调用方还会把它误当成
    // LRU 淘汰（docs 里就是这么写的）。默认只清派生缓存，丢工作区必须显式要求。
    if (dropWorkspaces) {
        workspaces.clear()
        workspaceBySourceKey.clear()
    }
    sources = emptyList()
    sourceFingerprint = emptyList()
    sourceSummaryCache.clear()
    pageStore.clear()
    searchCache.clear()
    workDir?.clearPersistentCache()
    AppLog.i("Index caches cleared (dropWorkspaces=$dropWorkspaces, workspaces=${workspaces.size})")
}

internal fun EngineRuntime.openWorkspace(path: String, temporary: Boolean): Workspace {
    val archiveEntry = path.substringAfterLast('!', "")
    if (archiveEntry.isNotBlank() &&
        !archiveEntry.endsWith(".so", ignoreCase = true)
    ) {
        error(
            "NOT_ELF_INPUT: $path is an APK/JAR entry, not an ELF SO file. Use apk_analyze or an APK MCP tool."
        )
    }
    val keyFallback = "local:$path"
    // An APK-embedded reference is never a plain filesystem file, so skip the
    // resolveLocalSoSource() File().exists() probe for it (avoids treating
    // "apk:...!..." / "content://apk/..." as a real path).
    val isApkEmbedded = path.trim().let {
        it.contains('!') ||
            it.startsWith("content://apk/") ||
            it.startsWith("apk:")
    }
    val src = findSource(path) ?: (if (isApkEmbedded) null else resolveLocalSoSource(path)) ?: run {
        // 【魔改】路径限制已废除：任意绝对路径 + root 通道都可读。
        // 只要 resolveLocalSoSource 判定为可读（含 root），就会在上面返回，
        // 走不到这里说明文件确实不存在或不可读。
        if (isApkEmbedded) {
            error(
                "SO path not found: $path. This looks like an APK-embedded SO; call so_open (action=list) first and open it with the returned path (apk:<relpath>!lib/<abi>/x.so)."
            )
        }
        // 【修复】旧实现把诊断信息拼进了异常消息，而 guarded 会把
        // `substringAfter(": ")` 当成 badValue 返回，导致客户端拿到
        // "/data/local/tmp (root available=true)" 这种非法路径。
        // 现在只报原始路径，root 状态放到单独的 diagnostics 里。
        error("SO path not found: $path")
    }
    // Own-artifact protection: never open/view/modify SOMCP's own APK or the
    // native libraries it bundles, whether reached directly (local_file /
    // build_output) or through an APK-embedded reference.
    //
    // Both forms a work-directory source carries are checked: the full
    // reference (`apk:<rel>!lib/<abi>/x.so`) — which names one of SOMCP's own
    // bundled libraries and is matched by entry name — and the APK path. The
    // paths are work-directory-relative, so the judges that need a readable file
    // cannot fire here; the bytes that are actually read are checked below.
    val selfHit = listOfNotNull(src.path, src.apkPath)
        .firstOrNull { it.isNotBlank() && SelfArtifactGuard.isSelfArtifact(context, it) }
    if (selfHit != null) {
        throw IllegalArgumentException(
            "SELF_ANALYSIS_FORBIDDEN: SOMCP cannot open, view, or modify its own artifact $selfHit"
        )
    }
    val key = sourceKey(src).ifBlank { keyFallback }
    workspaceBySourceKey[key]?.let { existingId -> touchWorkspace(existingId)?.let { return it } }
    // Stop before reading the whole SO into memory when the process heap cannot
    // safely hold it plus the parse copies — otherwise a large libapp.so on a
    // constrained device ends in an OutOfMemoryError instead of a clean error.
    MemoryGuard.ensureAnalysisMemory(
        src.size,
        "Opening ${src.name} for analysis"
    )
    val original = when (src.source) {
        // 【魔改】普通文件直读：不再需要工作目录
        "build_output", "local_file", "local_fs" -> runCatching {
            File(src.path).readBytes()
        }.getOrElse {
            // 【魔改】直读被拒（EACCES 等）→ root 通道兜底，任意路径都能读
            RootShell.readBytes(src.path)
                ?: error("SO path not found: $path (direct read failed: ${it.message})")
        }

        // 【魔改】root 通道直读任意路径（/data/data、/data/local/tmp 等）
        "root_fs" -> RootShell.readBytes(src.path)
            ?: error("SO path not found via root: $path")

        else -> (
            workDir
                ?: error("No work directory selected")
            ).readSource(src)
    }
    // Identity by content: a copy of one of SOMCP's own libraries that was
    // extracted from the APK and renamed carries neither a path nor an entry
    // name this process can classify, and the work directory is read through
    // SAF documents rather than the filesystem. The bytes that are about to be
    // analyzed are therefore checked as well.
    // 【魔改】allowSelfAnalysis 放行时不拦
    if (!com.soreverse.mcp.core.SelfArtifactGuard.allowSelfAnalysis &&
        containsPackageIdentifier(original)
    ) {
        throw IllegalArgumentException(
            "SELF_ANALYSIS_FORBIDDEN: SOMCP cannot open, view, or modify its own artifact ${src.path}"
        )
    }
    require(
        original.size >= 4 &&
            original[0] == 0x7f.toByte() &&
            original[1] == 'E'.code.toByte() &&
            original[2] == 'L'.code.toByte() &&
            original[3] == 'F'.code.toByte()
    ) {
        "NOT_ELF_INPUT: ${src.path} is not an ELF SO file. Use apk_analyze or an APK MCP tool."
    }
    val prepared = prepareAnalysisInput(original)
    val ws =
        Workspace(
            "so-ws-${UUID.randomUUID()}",
            src,
            prepared.data,
            prepared.elf,
            temporary,
            sha256(original),
            prepared.source,
            prepared.facts
        )
    workspaces[ws.id] = ws
    workspaceBySourceKey[key] = ws.id
    // 【修复 B2】打开成功后立刻收敛常驻数量，避免连续 so_open 把堆吃穿。
    evictWorkspacesIfNeeded()
    AppLog.i("Opened ${src.path} as ${ws.id}")
    return ws
}

internal data class AnalysisInput(val data: ByteArray, val source: String, val facts: JSONObject, val elf: ElfFile)

internal fun EngineRuntime.prepareAnalysisInput(original: ByteArray): AnalysisInput {
    val before = lief.parse(original)
    val facts = JSONObject().put(
        "attempted",
        false
    ).put(
        "changed",
        false
    ).put(
        "sectionsBefore",
        before.sections.size
    ).put(
        "programHeadersBefore",
        before.programHeaders.size
    ).put(
        "symbolsBefore",
        before.symbols.size
    ).put("dynSymbolsBefore", before.dynSymbols.size).put("functionSymbolsRecovered", false)
    if (before.sections.isNotEmpty()) {
        return AnalysisInput(
            original,
            "original",
            facts.put("reason", "section_table_present"),
            before
        )
    }
    if (original.size < 5 ||
        !xanso.available()
    ) {
        return AnalysisInput(
            original,
            "original",
            facts.put(
                "reason",
                if (original.size <
                    5
                ) {
                    "invalid_elf_ident"
                } else {
                    "xanso_unavailable"
                }
            ),
            before
        )
    }
    facts.put("attempted", true)
    val recovered = when (original[4].toInt() and 0xff) {
        1 -> xanso.buildSections(original)
        2 -> xanso.recoverElf64Sections(original)?.let { lief.fixSections(it) }
        else -> null
    }
    if (recovered == null ||
        recovered.isEmpty()
    ) {
        return AnalysisInput(
            original,
            "original",
            facts.put("reason", "xanso_recovery_failed"),
            before
        )
    }
    val after = lief.parse(recovered)
    if (after.sections.isEmpty()) {
        return AnalysisInput(
            original,
            "original",
            facts.put("reason", "recovered_section_table_not_parseable"),
            before
        )
    }
    facts.put(
        "changed",
        !recovered.contentEquals(original)
    ).put("reason", "missing_section_table").put(
        "recoveryMode",
        if ((
                original[4].toInt() and
                    0xff
                ) ==
            1
        ) {
            "xanso32_section_fix"
        } else {
            "xanso64_section_recovery_lief_finalize"
        }
    ).put(
        "sectionsAfter",
        after.sections.size
    ).put(
        "programHeadersAfter",
        after.programHeaders.size
    ).put("symbolsAfter", after.symbols.size).put("dynSymbolsAfter", after.dynSymbols.size).put(
        "functionSymbolsRecovered",
        after.symbols.count { it.type == "FUNC" } > before.symbols.count { it.type == "FUNC" }
    )
    return AnalysisInput(recovered, "xanso_recovered_sections", facts, after)
}

internal fun EngineRuntime.resolveLocalSoSource(rawPath: String): SoSource? {
    if (rawPath.isBlank()) return null
    val file = File(rawPath)

    // 【魔改】1) root 优先：有 root 就任意路径直读，不依赖 Java File 可见性
    // （分区存储下 Java File 对 /sdcard/自建目录连 exists() 都看不到）
    if (RootShell.isAvailable()) {
        val trimmed = rawPath.trim()
        if (trimmed.startsWith("/") || trimmed.startsWith("~")) {
            val abs = if (trimmed.startsWith("~")) trimmed.replaceFirst("~", "/data/local/tmp") else trimmed
            if (RootShell.exists(abs) && !RootShell.isDirectory(abs)) {
                val size = RootShell.fileSize(abs) ?: 0L
                if (abs.endsWith(".so", ignoreCase = true) || abs.contains("!") || size > 0) {
                    val name = abs.substringAfterLast('/')
                    return SoSource(abs, "root_fs", name, size, System.currentTimeMillis(), null)
                }
            }
        }
    }

    // 【魔改】2) 无 root（或 root 未命中）时：普通可直读文件仍可打开
    if (file.exists() && file.isFile) {
        val canonical = runCatching { file.canonicalPath }.getOrDefault(rawPath)
        val kind = if (file.canRead()) "local_fs" else "root_fs"
        return SoSource(canonical, kind, file.name, file.length(), file.lastModified(), null)
    }

    // 【魔改】3) 原逻辑保留作为兜底：app 私有目录下的 .so
    val extDir = context.getExternalFilesDir(null)?.canonicalPath
    val intDir = context.filesDir.canonicalPath
    val canonical = runCatching { file.canonicalPath }.getOrDefault(rawPath)
    if (listOfNotNull(extDir, intDir).any { canonical.startsWith(it) } &&
        file.name.endsWith(".so", ignoreCase = true)
    ) {
        return SoSource(canonical, "build_output", file.name, file.length(), file.lastModified(), null)
    }
    return null
}

internal fun EngineRuntime.findSource(rawPath: String): SoSource? {
    if (rawPath.isBlank()) return null
    val trimmed = rawPath.trim()
    val path = trimmed.removePrefix("/")
    workDir?.let { ensureSources(it) }
    // APK-embedded SO resolution. A single SO inside an APK can be referenced
    // in several equivalent forms and we must map every one of them back to the
    // scanned source whose canonical path is `apk:<relpath>!<entry>`:
    //   1. apk:<relpath>!lib/<abi>/x.so         (the form so_open list returns)
    //   2. /abs/path/App.apk!lib/<abi>/x.so     (absolute APK path + entry)
    //   3. content://apk/<apkName>/lib/<abi>/x.so
    //   4. a bare entry like lib/<abi>/x.so when only one APK provides it
    // Instead of matching the whole string we parse out (apkHint, entry) and
    // match against the source's real apkPath/apkEntry fields.
    resolveApkEmbeddedSource(trimmed)?.let { return it }
    val candidates = mutableListOf(trimmed, path)
    workDir?.rootAbsolutePath()?.let { root ->
        if (trimmed.isSameOrChildOf(root)) candidates += trimmed.drop(root.length).removePrefix("/")
    }
    candidates.distinct().forEach { candidate ->
        sources.firstOrNull {
            it.path == candidate ||
                it.name == candidate ||
                it.apkEntry == candidate
        }?.let { return it }
    }
    return sources.firstOrNull { it.path.endsWith("/$path") }
}

private fun String.isSameOrChildOf(root: String): Boolean = this == root || this.startsWith(root.trimEnd('/') + "/")

/**
 * Parse an APK-embedded SO reference into (apkHint, entry) and match it against
 * the scanned `source=="apk"` entries by their real apkPath/apkEntry, so any
 * equivalent spelling of the same embedded SO resolves to the same source.
 *
 * Returns null when [raw] is not an APK-embedded reference or nothing matches.
 */
internal fun EngineRuntime.resolveApkEmbeddedSource(raw: String): SoSource? {
    val apkSources = sources.filter { it.source == "apk" }
    if (apkSources.isEmpty()) return null

    val parsed = parseApkEmbeddedReference(raw) ?: return null
    val normalizedEntry = parsed.entry
    val apkBasename = parsed.apkBasename

    // Prefer an exact apkPath basename + entry match; fall back to entry-only
    // when the caller supplied no usable APK hint and the entry is unambiguous.
    apkSources.firstOrNull { src ->
        src.apkEntry == normalizedEntry &&
            (
                apkBasename == null ||
                    src.apkPath?.substringAfterLast('/')?.equals(apkBasename, ignoreCase = true) ==
                    true
                )
    }?.let { return it }

    if (apkBasename == null) {
        val entryMatches = apkSources.filter { it.apkEntry == normalizedEntry }
        if (entryMatches.size == 1) return entryMatches.first()
    }
    return null
}

/** Normalized (apkBasename, entry) parsed from an APK-embedded SO reference. */
internal data class ApkEmbeddedReference(val apkBasename: String?, val entry: String)

/**
 * Pure parser (no engine state) that turns the many equivalent spellings of an
 * APK-embedded SO reference into a normalized (apkBasename, entry) pair. Kept
 * separate from [resolveApkEmbeddedSource] so it is unit-testable on the JVM.
 * Returns null when [raw] is not an APK-embedded reference or has no entry.
 */
internal fun parseApkEmbeddedReference(raw: String): ApkEmbeddedReference? {
    val trimmed = raw.trim()
    val (apkHint, entry) = when {
        trimmed.startsWith("apk:") -> {
            val body = trimmed.removePrefix("apk:")
            val bang = body.indexOf('!')
            if (bang <= 0) "" to body else body.substring(0, bang) to body.substring(bang + 1)
        }

        trimmed.startsWith("content://apk/") -> {
            // The segment ending in .apk is the APK hint; everything after the
            // first ".apk/" is the entry. Tolerates APK paths with separators.
            val body = trimmed.removePrefix("content://apk/")
            val marker = body.indexOf(".apk/", ignoreCase = true)
            if (marker >=
                0
            ) {
                body.substring(0, marker + 4) to body.substring(marker + 5)
            } else {
                "" to body
            }
        }

        trimmed.contains('!') -> {
            val bang = trimmed.lastIndexOf('!')
            trimmed.substring(0, bang) to trimmed.substring(bang + 1)
        }

        trimmed.contains(".apk/", ignoreCase = true) -> {
            val marker = trimmed.lastIndexOf(".apk/", ignoreCase = true)
            trimmed.substring(0, marker + 4) to trimmed.substring(marker + 5)
        }

        // A bare entry only qualifies when it clearly looks like an in-APK SO.
        trimmed.trimStart('/').startsWith("lib/") && trimmed.endsWith(".so", ignoreCase = true) ->
            "" to
                trimmed.trimStart('/')

        else -> return null
    }

    val normalizedEntry = entry.trim().trimStart('/').removePrefix("./")
    if (normalizedEntry.isBlank()) return null
    val apkBasename = apkHint.trim().trimEnd('/').substringAfterLast('/').ifBlank { null }
    return ApkEmbeddedReference(apkBasename, normalizedEntry)
}

internal fun EngineRuntime.ensureSources(dir: WorkDirectory): List<SoSource> {
    val settings = SettingsStore(context)
    val options = scanOptions(settings)
    if (!settings.indexCacheEnabled) {
        sources = dir.listSos(options)
        sourceFingerprint = sources.map { FileFingerprint(it.path, it.size, it.modified) }
        return sources
    }
    // Single-pass scan: get sources AND fingerprint in one directory walk
    val (scanned, fingerprint) = dir.listSosWithFingerprint(options)
    if (sources.isNotEmpty() && fingerprint == sourceFingerprint) return sources
    sources = scanned
    sourceFingerprint = fingerprint
    pageStore.clear()
    AppLog.i("Scanned ${sources.size} SO entries")
    return sources
}

internal fun EngineRuntime.scanOptions(settings: SettingsStore): ScanOptions = ScanOptions(
    settings.scanApks,
    settings.scanSubdirectories,
    settings.maxScanDepth,
    settings.skipFilesLargerThanMb.toLong() * 1024L * 1024L
)

internal fun EngineRuntime.sourceSummary(dir: WorkDirectory, src: SoSource): SourceSummary {
    if (!SettingsStore(
            context
        ).parseMetadataInList
    ) {
        return SourceSummary("unknown", 0, "little", false, false)
    }
    return sourceSummaryCache.getOrPut(sourceKey(src)) {
        dir.cachedSummary(src)?.let {
            return@getOrPut SourceSummary(
                it.architecture,
                it.bits,
                it.endian,
                it.hasDebugInfo,
                it.stripped
            )
        }

        // Fast path: for filesystem SOs, parse ELF header via seeking (reads only ~few KB
        // instead of the full SO file, which can be tens of MB for libapp.so)
        if (src.source == "filesystem" && src.treeDocumentUri != null) {
            dir.readElfSummary(src.treeDocumentUri)?.let { summary ->
                if (summary.architecture != "unknown") {
                    dir.putCachedSummary(
                        src,
                        CachedSourceSummary(
                            summary.architecture,
                            summary.bits,
                            summary.endian,
                            summary.hasDebugInfo,
                            summary.stripped
                        )
                    )
                    return@getOrPut summary
                }
            }
        }

        // Fallback: read full file and parse with LIEF. When the heap cannot hold
        // the file, degrade to an "unknown" summary instead of crashing the scan
        // with an OutOfMemoryError.
        runCatching {
            MemoryGuard.ensureAnalysisMemory(
                src.size,
                "Reading ${src.name} metadata during scan"
            )
            lief.parse(dir.readSource(src)).let { elf ->
                SourceSummary(
                    elf.architecture,
                    elf.bits,
                    elf.endian,
                    elf.sections.any {
                        it.name.startsWith(".debug")
                    },
                    elf.symbols.isEmpty()
                ).also {
                    dir.putCachedSummary(
                        src,
                        CachedSourceSummary(
                            it.architecture,
                            it.bits,
                            it.endian,
                            it.hasDebugInfo,
                            it.stripped
                        )
                    )
                }
            }
        }.onFailure { failure ->
            if (failure is InsufficientMemoryException) {
                AppLog.w(failure.message ?: "Skipping metadata parse: insufficient memory")
            }
        }.getOrElse { SourceSummary("unknown", 0, "little", false, true) }
    }
}

internal fun EngineRuntime.sourceKey(src: SoSource): String = "${src.path}|${src.size}|${src.modified}"
