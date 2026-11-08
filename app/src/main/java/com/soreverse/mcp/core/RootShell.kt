// SPDX-License-Identifier: AGPL-3.0-or-later
//
// SOMCP 魔改：Root 支持
// 提供 su 执行通道，使 SOMCP 可以读取 /data/data、/data/local/tmp 等受限目录。
//
package com.soreverse.mcp.core

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

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

    data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean, val ok: Boolean)

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
    fun readBytes(path: String, maxBytes: Long = 0L, timeoutMs: Long = 0L): ByteArray? {
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
        var watchdog: java.util.Timer? = null
        var timedOut = false
        return try {
            val quoted = shellQuote(path)
            // head -c 在部分 busybox 上不兼容，改用 cat + 本地截断
            process = ProcessBuilder(su, "-c", "cat $quoted").start()
            val proc = process
            // 【修复】旧实现把超时变量算出来后就没用过（waitFor 用的是
            // defaultTimeoutMs），而且 waitFor 排在「读完 stdout」之后 ——
            // cat 卡在 FUSE/NFS 上不产出时 input.read() 会永久阻塞，
            // 永远走不到 waitFor。现在用看门狗到点直接强杀进程，让阻塞的
            // read 抛 IOException 返回，超时真正生效。
            val timeout = if (timeoutMs > 0) timeoutMs else defaultTimeoutMs
            val timer = java.util.Timer("rootshell-read-watchdog", true)
            watchdog = timer
            timer.schedule(
                object : java.util.TimerTask() {
                    override fun run() {
                        timedOut = true
                        runCatching { proc.destroyForcibly() }
                    }
                },
                timeout
            )
            val out = ByteArrayOutputStream()
            // stderr 必须抽干，否则 cat 写满 64KB pipe 缓冲后会阻塞
            val errBuf = StringBuilder()
            val errThread = Thread({
                runCatching {
                    proc.errorStream.bufferedReader().use { reader ->
                        val chunk = CharArray(4096)
                        while (true) {
                            val n = reader.read(chunk)
                            if (n < 0) break
                            synchronized(errBuf) {
                                if (errBuf.length < 16 * 1024) errBuf.append(chunk, 0, n)
                            }
                        }
                    }
                }
            }, "rootshell-read-stderr").apply {
                isDaemon = true
                start()
            }
            process.inputStream.use { input ->
                val buf = ByteArray(65536)
                var total = 0L
                while (true) {
                    val n = try {
                        input.read(buf)
                    } catch (t: java.io.IOException) {
                        timedOut = true
                        break
                    }
                    if (n < 0) break
                    if (limit > 0 && total + n > limit) {
                        out.write(buf, 0, (limit - total).toInt())
                        break
                    }
                    out.write(buf, 0, n)
                    total += n
                }
            }
            timer.cancel()
            errThread.join(1000)
            if (timedOut) {
                Log.w(TAG, "readBytes($path) timed out after ${timeout}ms")
                return null
            }
            val finished = proc.waitFor(2_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return null
            }
            if (proc.exitValue() != 0) {
                Log.w(TAG, "readBytes($path) exit=${proc.exitValue()} err=${errBuf.toString().take(400)}")
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
        } finally {
            runCatching { watchdog?.cancel() }
        }
    }

    /**
     * 【魔改】以 root 身份打开任意路径的输入流（`su -c cat`），调用方需
     * 负责 close（会顺带回收进程）。用于大文件流式解析，不把整包读进堆。
     *
     * 【修复】旧实现不消费 stderr、不检查子进程退出码，导致 `cat <目录>`
     * 或 `cat <不存在文件>` 也返回一个「非 null 但立即 EOF」的流，上层
     * 无法区分「空文件」和「读取失败」，apk_analyze 因此对任意文件都
     * 返回 ok:true。现在：
     *  - stderr 由守护线程抽干（避免 64KB pipe 写满导致双向死锁）
     *  - 流读到 EOF 时检查退出码，非 0 则抛 IOException，让上层得到明确失败
     *  - 支持 [expectedSize]，用于提前发现截断（cat 中断时字节数会偏小）
     */
    fun openStream(path: String, expectedSize: Long = -1L): java.io.InputStream? {
        val su = suPath() ?: return null
        return try {
            val process = ProcessBuilder(su, "-c", "cat ${shellQuote(path)}").start()
            val errBuf = StringBuilder()
            Thread({
                runCatching {
                    process.errorStream.bufferedReader().use { reader ->
                        val chunk = CharArray(4096)
                        while (true) {
                            val n = reader.read(chunk)
                            if (n < 0) break
                            synchronized(errBuf) {
                                if (errBuf.length < 8 * 1024) errBuf.append(chunk, 0, n)
                            }
                        }
                    }
                }
            }, "rootshell-stream-stderr").apply {
                isDaemon = true
                start()
            }

            val raw = process.inputStream
            object : java.io.FilterInputStream(raw) {
                private var consumed = 0L
                private var eofVerified = false

                override fun read(): Int {
                    val b = super.read()
                    if (b >= 0) consumed++ else verifyEof()
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        consumed += n
                    } else if (n < 0) {
                        verifyEof()
                    }
                    return n
                }

                /** 读到 EOF 时确认 cat 是正常退出，否则把失败暴露给调用方。 */
                private fun verifyEof() {
                    if (eofVerified) return
                    eofVerified = true
                    val finished = runCatching {
                        process.waitFor(2_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }.getOrDefault(false)
                    if (!finished) return
                    val code = runCatching { process.exitValue() }.getOrDefault(-1)
                    if (code != 0) {
                        val msg = synchronized(errBuf) { errBuf.toString().trim() }
                        throw java.io.IOException(
                            "root cat failed for $path (exit=$code)${if (msg.isBlank()) "" else ": ${msg.take(400)}"}"
                        )
                    }
                    if (expectedSize >= 0 && consumed != expectedSize) {
                        throw java.io.IOException(
                            "root cat truncated for $path: read $consumed of $expectedSize bytes"
                        )
                    }
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        runCatching { process.destroyForcibly() }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "openStream($path) failed: ${t.message}")
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

    data class DirEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long, val modified: Long)

    /**
     * 以 root 身份列目录（非递归）。
     *
     * 用 `ls -la --time-style=+%s` 解析；对含空格的文件名做了兜底。
     */
    fun listFiles(dirPath: String): List<DirEntry> {
        if (!isAvailable()) return emptyList()
        // 【魔改】不再解析 `ls -la`：Android toybox 的 `ls -la` 把日期拆成
        // 「2026-11-07 13:56」两列，且部分 ROM 还会带额外列，旧代码按
        // 固定的 9 列切分（parts[4]=size / parts[5]=mtime / parts[8]=name）
        // 会全部落空，导致 root 扫描 list 永远返回空。
        // 改用 `find <dir> -maxdepth 1` 拿直接子项路径，再用 `stat -c`
        // 一次取回类型/大小/mtime，格式由我们自己控制，跨 ROM 稳定。
        val q = shellQuote(dirPath)
        // find 只列直接子项；紧接着用 stat 一次性把类型/大小/mtime 打出来
        // （stat 支持多路径，避免每个文件起一次 su 进程）。
        val r = exec(
            "find $q -maxdepth 1 -mindepth 1 -print0 2>/dev/null | " +
                "xargs -0 -r stat -c '%F|%s|%Y|%n' 2>/dev/null"
        )
        if (!r.ok) return emptyList()
        val entries = mutableListOf<DirEntry>()
        for (raw in r.stdout.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            // 格式：regular file|22016|1794030963|/abs/path/name
            val fields = line.split('|', limit = 4)
            if (fields.size < 4) continue
            val path = fields[3]
            val name = path.substringAfterLast('/')
            if (name == "." || name == "..") continue
            val isDir = fields[0].equals("directory", ignoreCase = true)
            val size = fields[1].toLongOrNull() ?: 0L
            val mtimeSec = fields[2].toLongOrNull() ?: 0L
            entries.add(DirEntry(name, path, isDir, size, mtimeSec * 1000L))
        }
        return entries
    }

    /** 递归扫描目录，收集匹配后缀的文件。失败时静默跳过。*/
    fun walkFiles(dirPath: String, suffixes: List<String>, maxDepth: Int = 8, onFile: (DirEntry) -> Unit) {
        if (maxDepth <= 0 || !isAvailable()) return
        // 【魔改】旧实现递归逐层 listFiles（每层一次 `su -c find|stat`），
        // 扫 /data/app、/storage/emulated/0 这类大目录会起几百个 su 进程，
        // 慢到 MCP 请求直接超时。改成一条 `find -maxdepth N` 拿全部路径，
        // 再一次性 stat 补元信息（最多 2 次 su）。
        val nameFilter = suffixes.joinToString(" -o ") { "-iname '*$it'" }
            .let { if (suffixes.isEmpty()) "" else " \\( $it \\)" }
        val r = exec(
            "find ${shellQuote(dirPath)} -maxdepth $maxDepth -type f $nameFilter -print0 2>/dev/null | " +
                "xargs -0 -r stat -c '%F|%s|%Y|%n' 2>/dev/null"
        )
        if (!r.ok) return
        for (raw in r.stdout.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val fields = line.split('|', limit = 4)
            if (fields.size < 4) continue
            val path = fields[3]
            val name = path.substringAfterLast('/')
            if (name.isBlank()) continue
            val isDir = fields[0].equals("directory", ignoreCase = true)
            val size = fields[1].toLongOrNull() ?: 0L
            val mtimeSec = fields[2].toLongOrNull() ?: 0L
            onFile(DirEntry(name, path, isDir, size, mtimeSec * 1000L))
        }
    }

    /** POSIX 单引号转义，防注入。*/
    fun shellQuote(s: String): String {
        if (s.isEmpty()) return "''"
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
