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

import android.content.Context
import com.soreverse.mcp.core.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 【新增】按需下载的 Blutter runner 的本地存储与发现。
 *
 * ## 为什么需要它
 *
 * 内置 runner 必须打进 APK 的 jniLibs；而 runner 是按 Dart 版本拆分的，
 * 每个约 12 MB（7z 压缩后约 1.7 MB），想覆盖多版本会把 APK 撑爆 ——
 * 33 个版本就是 390 MB 级别。
 *
 * 但 runner 完全可以**运行时下载到应用私有目录再 dlopen 绝对路径**：
 * Android 10+ 限制的是外部存储那类"其他方可能篡改"的位置，应用自己的
 * `files/` 目录是可以加载的（`myfler/fler-dart` 就是这么做的，其
 * `files/engines/` 下放了 32 个 `dartvm_<ver>.so` 且 targetSdk=36）。
 *
 * ## 目录布局
 *
 * ```text
 * files/blutter-engines/
 *   ├── dartvm_3.11.5.so        # 一个 Dart 版本一个 runner
 *   ├── dartvm_3.12.2.so
 *   └── runners.local.json      # 可选：显式描述（字段同内置 runners.json 的 runners[]）
 * ```
 *
 * 有 `runners.local.json` 时以它为准；否则按文件名 `dartvm_<dartVersion>.so`
 * 推断。文件名不符合约定且没有描述条目时**忽略**（不猜版本，避免错配）。
 *
 * ## 与 native 的约定
 *
 * 本地 runner 的 `libraryName` 填**绝对路径**：`blutter_bridge.cpp` 见到 `/`
 * 开头就走绝对路径 dlopen，否则按库名在 linker namespace 里搜索。
 */
internal class BlutterEngineStore(private val context: Context) {

    val root: File get() = File(context.filesDir, DIR_NAME)

    /** sha256 缓存：path -> (size, sha256)。runner 是十几 MB 的文件，避免每次列表都重算。 */
    private val shaCache = HashMap<String, Pair<Long, String>>()

    /**
     * 本地 runner 列表。返回的描述符 `libraryName` 是**绝对路径**，
     * `source` 为 [SOURCE_LOCAL]。
     */
    fun localRunners(): List<BlutterRunnerDescriptor> = runCatching {
        val dir = root
        if (!dir.isDirectory) return emptyList()
        val explicit = readExplicitManifest()
        if (explicit != null) {
            return BlutterRunnerManifest.runners(explicit, SOURCE_LOCAL)
        }
        scanByFileName(dir)
    }.getOrElse {
        AppLog.w("BlutterEngineStore: local runner scan failed: ${it.message}")
        emptyList()
    }

    /** 目录里是否已经有该 Dart 版本的 runner。 */
    fun hasDartVersion(dartVersion: String): Boolean =
        localRunners().any { it.dartVersion == dartVersion }

    /** 删除某个 Dart 版本的本地 runner，返回是否真的删掉了。 */
    fun deleteDartVersion(dartVersion: String): Boolean {
        val target = root.listFiles().orEmpty().firstOrNull { file ->
            file.isFile && file.name == fileNameFor(dartVersion)
        } ?: return false
        shaCache.remove(target.absolutePath)
        return runCatching { target.delete() }.getOrDefault(false)
    }

    /** 本地已占用的字节数，用于清理提示。 */
    fun usedBytes(): Long = root.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }

    /** 供 UI / MCP 展示的概览。 */
    fun overview(): JSONObject {
        val runners = localRunners()
        return JSONObject()
            .put("directory", root.absolutePath)
            .put("exists", root.isDirectory)
            .put("count", runners.size)
            .put("usedBytes", usedBytes())
            .put("expectedFileName", fileNameFor("<dartVersion>"))
            .put(
                "runners",
                JSONArray(
                    runners.map { runner ->
                        JSONObject()
                            .put("runnerId", runner.runnerId)
                            .put("dartVersion", runner.dartVersion ?: JSONObject.NULL)
                            .put("abi", runner.abi)
                            .put("sha256", runner.sha256)
                            .put("path", runner.libraryName)
                    }
                )
            )
            .put(
                "howToInstall",
                "Drop dartvm_<dartVersion>.so into the directory above (adb push as root, " +
                    "or a release asset download). The native bridge loads it by absolute path."
            )
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    /**
     * 读取显式描述（字段同内置 `runners.json` 的 `runners[]`）。
     *
     * 两点与"发布包"对接的处理：
     *
     * 1. `libraryName` 允许只写**文件名** —— 打包时还不知道用户会把它装到哪，
     *    这里统一解析成 `root/<name>` 的绝对路径再交给解析器。
     * 2. 用**实际文件**的 sha256 覆盖描述里的值，不一致就**跳过该 runner**。
     *    这样"下载到一半 / 被替换"的 runner 不会被静默加载 —— Blutter 是要
     *    把第三方快照喂给 native 解析的，加载来源必须可信。
     */
    private fun readExplicitManifest(): JSONObject? {
        val file = File(root, EXPLICIT_MANIFEST)
        if (!file.isFile) return null
        val parsed = runCatching { JSONObject(file.readText()) }.getOrElse {
            AppLog.w("BlutterEngineStore: bad $EXPLICIT_MANIFEST (${it.message})")
            return null
        }
        val array = parsed.optJSONArray("runners") ?: return parsed
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val raw = item.optString("libraryName")
            if (raw.isBlank() || raw.startsWith("/")) continue
            val candidate = File(root, raw)
            if (!candidate.isFile) {
                AppLog.w("BlutterEngineStore: declared runner file missing: $raw")
                item.put("libraryName", "")
                continue
            }
            val actual = sha256Of(candidate)
            val declared = item.optString("sha256")
            if (declared.isNotBlank() && !declared.equals(actual, ignoreCase = true)) {
                AppLog.w("BlutterEngineStore: sha256 mismatch for $raw — refusing to load it")
                item.put("libraryName", "")
                continue
            }
            item.put("libraryName", candidate.absolutePath)
            item.put("sha256", actual)
        }
        return parsed
    }

    /**
     * 扫描 `dartvm_<dartVersion>.so`。
     *
     * 不按约定命名的文件会被跳过 —— 版本猜错比"用不上"更糟：Blutter 对快照
     * 格式是硬绑定的，错配只会在解析阶段 abort。
     */
    private fun scanByFileName(dir: File): List<BlutterRunnerDescriptor> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("dartvm_") && it.name.endsWith(".so") }
            .mapNotNull { file ->
                val version = file.name.removePrefix("dartvm_").removeSuffix(".so")
                if (version.isBlank() || !version.first().isDigit()) return@mapNotNull null
                BlutterRunnerDescriptor(
                    runnerId = "local-dart-${version.replace('.', '_')}-${abiOf(context)}",
                    dartVersion = version,
                    engineRevision = null,
                    abi = abiOf(context),
                    // 现代 Dart AOT（3.x 起）默认 compressed pointers；如与实际不符，
                    // 请在 runners.local.json 里显式声明覆盖。
                    compressedPointers = true,
                    analysis = true,
                    sha256 = sha256Of(file),
                    source = SOURCE_LOCAL,
                    libraryName = file.absolutePath,
                    upstreamCommit = "",
                    snapshotAliases = emptyList(),
                    engineRevisions = emptyList(),
                    flutterVersions = emptyList(),
                    snapshotHashes = emptySet()
                )
            }

    private fun sha256Of(file: File): String {
        val cached = shaCache[file.absolutePath]
        if (cached != null && cached.first == file.length()) return cached.second
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        // runCatching().getOrElse { } 的返回值类型是 non-null，这里写入是安全的
        shaCache[file.absolutePath] = file.length() to hex
        return hex
    }

    companion object {
        const val DIR_NAME = "blutter-engines"
        const val EXPLICIT_MANIFEST = "runners.local.json"
        /** 与内置 runner 的 `source` 字段区分。 */
        const val SOURCE_LOCAL = "local"

        /** 约定文件名：`dartvm_<dartVersion>.so`。 */
        fun fileNameFor(dartVersion: String): String = "dartvm_$dartVersion.so"
    }
}

/** 当前设备的主 ABI，仅用于本地 runner 的描述。 */
private fun abiOf(context: Context): String = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
