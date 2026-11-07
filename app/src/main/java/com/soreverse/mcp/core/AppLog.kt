package com.soreverse.mcp.core

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object AppLog {
    const val ACTION_LOG = "com.soreverse.mcp.LOG"
    const val EXTRA_LINE = "line"
    private const val MAX_LINES = 500
    private const val MAX_LINE_LEN = 4096
    private val lock = Any()
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var app: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(String) -> Unit>()

    @Volatile private var lastDelivered: String? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun addListener(l: (String) -> Unit) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun removeListener(l: (String) -> Unit) {
        synchronized(listeners) { listeners.remove(l) }
    }

    @Deprecated("Use addListener/removeListener", ReplaceWith("addListener(f)"))
    fun setInprocListener(f: ((String) -> Unit?)?) {
        synchronized(listeners) {
            listeners.clear()
            if (f != null) listeners.add { s -> f(s) }
        }
    }

    fun i(message: String) = add("I", message)
    fun w(message: String) = add("W", message)
    fun e(message: String, throwable: Throwable? = null) {
        add("E", if (throwable == null) message else "$message: ${throwable.message}")
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() {
        synchronized(lock) { lines.clear() }
        deliver("__CLEAR__")
    }

    private fun add(level: String, message: String) {
        val minLevel = app?.let { runCatching { SettingsStore(it).logLevel }.getOrNull() } ?: "I"
        if (levelRank(level) < levelRank(minLevel)) return
        val raw = message
        val clipped = if (raw.length >
            MAX_LINE_LEN
        ) {
            raw.substring(0, MAX_LINE_LEN) + "…(${raw.length})"
        } else {
            raw
        }
        // 【修复 B3】SimpleDateFormat 不是线程安全的（内部持有可变 Calendar）。
        // MCP 服务会并发处理请求，以前 formatter.format() 在 synchronized(lock)
        // 之外执行，多线程同时写日志会出现时间戳串味，甚至抛
        // ArrayIndexOutOfBoundsException —— 而那个异常恰好发生在写日志的路径上，
        // 会把真正要记录的错误盖掉。现在格式化与入队合并进同一把锁（开销可忽略）。
        val nextLine = synchronized(lock) {
            if (lines.size == MAX_LINES) lines.removeFirst()
            val line = "${formatter.format(Date())} $level $clipped"
            lines.addLast(line)
            line
        }
        deliver(nextLine)
    }

    private fun deliver(line: String) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        if (snapshot.isEmpty()) {
            lastDelivered = null
            runCatching { app?.sendBroadcast(Intent(ACTION_LOG).putExtra(EXTRA_LINE, line)) }
            return
        }
        if (line != "__CLEAR__" && line == lastDelivered) return
        lastDelivered = line
        if (Looper.myLooper() == Looper.getMainLooper()) {
            snapshot.forEach { runCatching { it(line) } }
        } else {
            mainHandler.post {
                snapshot.forEach { runCatching { it(line) } }
            }
        }
    }

    private fun levelRank(level: String): Int = when (level) {
        "E" -> 3
        "W" -> 2
        else -> 1
    }
}
