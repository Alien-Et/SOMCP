// SPDX-License-Identifier: AGPL-3.0-or-later
//
// SOMCP 魔改：Root 支持
// 提供 su 执行通道，使 SOMCP 可以读取 /data/data、/data/local/tmp 等受限目录。
//
package com.soreverse.mcp.core

import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader

/**
 * Root 执行通道。
 *
 * 三条能力：
 *  1. [isAvailable]  —— 探测 su 是否可用（带缓存）
 *  2. [exec]         —— 执行 shell 命令，返回 stdout/stderr/exitCode
 *  3. [readBytes]    —— 以 root 身份读取任意文件到 ByteArray
 *  4. [listFiles]    —— 以 root 身份列目录
 *
 * 全部带超时保护，避免 su 卡死（之前 frida-server 卡死过设备）。
 */
object RootShell {

    private const val TAG = "SOMCP-Root"

    /** su 可执行文件候选。按顺序探测。 */
    private val SU_CANDIDATES = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/adb/magisk/su"
    )

    @Volatile
    private var cachedAvailable: Boolean? = null

    @Volatile
    private var cachedSuPath: String? = null

    /** 默认命令超时（毫秒）。*/
    var defaultTimeoutMs: Long = 30_000L

    /** 读取文件时的最大字节数，防止误读超大文件导致 OOM。0 = 不限制。*/
    @Volatile
    var maxReadBytes: Long = 0L

    /** 探测 root 是否可用。结果缓存，可通过 [invalidate] 强制重测。*/
    fun isAvailable(): Boolean {
        cachedAvailable?.let { return it }
        synchronized(this) {
            cachedAvailable?.let { return it }
            for (candidate in SU_CANDIDATES) {
                try {
                    val p = ProcessBuilder(candidate, "-c", "id").redirectErrorStream(true).start()
                    val out = p.inputStream.bufferedReader().use { it.readText() }
                    val finished = p.waitFor()
                    if (finished == 0 && out.contains("uid=0")) {
                        cachedSuPath = candidate
                        cachedAvailable = true
                        Log.i(TAG, "root available via $candidate")
                        return true
                    }
                } catch (_: Throwable) {
                    // 继续下一个候选
                }
            }
            cachedAvailable = false
            Log.w(TAG, "root NOT available")
            return false
        }
    }

    /** 清除探测缓存（例如用户刚 root 完）。*/
    fun invalidate() {
        synchronized(this) {
            cachedAvailable = null
            cachedSuPath = null
        }
    }

    /** 实际使用的 su 路径，不可用时为 null。*/
    fun suPath(): String? {
        if (!isAvailable()) return null
        return cachedSuPath
    }

    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val ok: Boolean
    )

    /**
     * 以 root 执行命令。
     *
     * @param command 完整 shell 命令（会交给 su -c 执行）
     * @param timeoutMs 超时；<=0 用 [defaultTimeoutMs]
     */
    fun exec(command: String, timeoutMs: Long = 0L): ShellResult {
        val su = suPath()
            ?: return ShellResult(-1, "", "root not available", false, false)
        val timeout = if (timeoutMs > 0) timeoutMs else defaultTimeoutMs
        var process: Process? = null
        return try {
            process = ProcessBuilder(su, "-c", command).start()
            val stdoutBuf = ByteArrayOutputStream()
            val stderrBuf = ByteArrayOutputStream()

            val outThread = Thread {
                try {
                    process.inputStream.use { input ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) stdoutBuf.write(buf, 0, n)
                    }
                } catch (_: Throwable) {
                }
            }
            val errThread = Thread {
                try {
                    process.errorStream.use { input ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) stderrBuf.write(buf, 0, n)
                    }
                } catch (_: Throwable) {
                }
            }
            outThread.start()
            errThread.start()

            val finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                outThread.join(1000)
                errThread.join(1000)
                return ShellResult(-1, stdoutBuf.toString("UTF-8"), stderrBuf.toString("UTF-8"), true, false)
            }
            outThread.join(2000)
            errThread.join(2000)
            val code = process.exitValue()
            ShellResult(code, stdoutBuf.toString("UTF-8"), stderrBuf.toString("UTF-8"), false, code == 0)
        } catch (t: Throwable) {
            try {
                process?.destroyForcibly()
            } catch (_: Throwable) {
            }
            ShellResult(-1, "", t.message ?: t.toString(), false, false)
        }
    }

    /**
     * 以 root 身份把任意路径读成 ByteArray。
     *
     * 走 `su -c "cat <path>"`，用二进制管道直读，避免 base64 膨胀。
     * 大文件建议同时设置 [maxReadBytes]。
     */
    fun readBytes(path: String, maxBytes: Long = 0L): ByteArray? {
        val su = suPath() ?: return null
        val limit = when {
            maxBytes > 0 -> maxBytes
            maxReadBytes > 0 -> maxReadBytes
            else -> 0L
        }
        // 先用 stat 拿大小，超过限制直接放弃（避免读到一半才发现）
        if (limit > 0) {
            val size = fileSize(path) ?: return null
            if (size > limit) {
                Log.w(TAG, "refuse to read $path: size=$size > limit=$limit")
                return null
            }
        }
        var process: Process? = null
        return try {
            val quoted = shellQuote(path)
            // head -c 在部分 busybox 上不兼容，改用 cat + 本地截断
            process = ProcessBuilder(su, "-c", "cat $quoted").start()
            val out = ByteArrayOutputStream()
            process.inputStream.use { input ->
                val buf = ByteArray(65536)
                var total = 0L
                var n: Int
                while (input.read(buf).also { n = it } > 0) {
                    if (limit > 0 && total + n > limit) {
                        out.write(buf, 0, (limit - total).toInt())
                        break
                    }
                    out.write(buf, 0, n)
                    total += n
                }
            }
            val finished = process.waitFor(defaultTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) {
                val err = process.errorStream.bufferedReader().use { it.readText() }
                Log.w(TAG, "readBytes($path) exit=${process.exitValue()} err=$err")
                return null
            }
            out.toByteArray()
        } catch (t: Throwable) {
            try {
                process?.destroyForcibly()
            } catch (_: Throwable) {
            }
            Log.w(TAG, "readBytes($path) failed: ${t.message}")
            null
        }
    }

    /** 以 root 身份拿文件大小，失败返回 null。*/
    fun fileSize(path: String): Long? {
        if (!isAvailable()) return null
        val r = exec("stat -c %s ${shellQuote(path)} 2>/dev/null")
        if (!r.ok) return null
        return r.stdout.trim().toLongOrNull()
    }

    /** 以 root 身份判断路径存在且是文件。*/
    fun exists(path: String): Boolean {
        if (!isAvailable()) return File(path).exists()
        val r = exec("test -e ${shellQuote(path)} && echo yes || echo no")
        return r.stdout.trim() == "yes"
    }

    /** 以 root 身份判断路径是目录。*/
    fun isDirectory(path: String): Boolean {
        if (!isAvailable()) return File(path).isDirectory
        val r = exec("test -d ${shellQuote(path)} && echo yes || echo no")
        return r.stdout.trim() == "yes"
    }

    data class DirEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val modified: Long
    )

    /**
     * 以 root 身份列目录（非递归）。
     *
     * 用 `ls -la --time-style=+%s` 解析；对含空格的文件名做了兜底。
     */
    fun listFiles(dirPath: String): List<DirEntry> {
        if (!isAvailable()) return emptyList()
        val r = exec("ls -la ${shellQuote(dirPath)} 2>/dev/null")
        if (!r.ok) return emptyList()
        val entries = mutableListOf<DirEntry>()
        for (raw in r.stdout.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            if (line.startsWith("total ")) continue
            // 典型行：-rw-r--r-- 1 u0_a123 u0_a123 12345 1793750336 name
            val parts = line.split(Regex("\\s+"), limit = 9)
            if (parts.size < 9) continue
            val perms = parts[0]
            if (perms.isEmpty()) continue
            val isDir = perms.startsWith("d")
            val isLink = perms.startsWith("l")
            val size = parts[4].toLongOrNull() ?: 0L
            val mtime = parts[5].toLongOrNull() ?: 0L
            var name = parts[8]
            if (isLink) {
                name = name.substringBefore(" -> ")
            }
            if (name == "." || name == "..") continue
            val full = if (dirPath.endsWith("/")) "$dirPath$name" else "$dirPath/$name"
            entries.add(DirEntry(name, full, isDir, size, mtime * 1000L))
        }
        return entries
    }

    /** 递归扫描目录，收集匹配后缀的文件。失败时静默跳过。*/
    fun walkFiles(
        dirPath: String,
        suffixes: List<String>,
        maxDepth: Int = 8,
        onFile: (DirEntry) -> Unit
    ) {
        if (maxDepth <= 0) return
        for (e in listFiles(dirPath)) {
            if (e.isDirectory) {
                walkFiles(e.path, suffixes, maxDepth - 1, onFile)
            } else {
                if (suffixes.isEmpty() || suffixes.any { e.name.endsWith(it, ignoreCase = true) }) {
                    onFile(e)
                }
            }
        }
    }

    /** POSIX 单引号转义，防注入。*/
    fun shellQuote(s: String): String {
        if (s.isEmpty()) return "''"
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
