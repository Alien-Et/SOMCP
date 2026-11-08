// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Pure JVM-testable string census logic behind str_encoding_census,
// str_literal_classes, net_endpoint_extract, file_path_roots and
// env_property_keys. Input is the parsed string table (StringInfo list) plus
// the raw bytes for the UTF-16LE sweep the parser does not perform.
package com.soreverse.mcp.engine

import org.json.JSONArray
import org.json.JSONObject

internal object StringCensus {
    private val URL_RE = Regex("^(https?|wss?|ftp|rtmp|rtsp)://\\S+$")
    private val IP_PORT_RE = Regex("^(\\d{1,3}(\\.\\d{1,3}){3}):(\\d{1,5})$")
    private val HOST_PORT_RE = Regex("^[a-zA-Z][\\w.-]{2,}:\\d{2,5}$")
    private val ABS_PATH_RE = Regex("^/[\\w@%+=:,.~^\\-/ ]+$")
    private val BASE64_RE = Regex("^[A-Za-z0-9+/]{16,}={0,2}$")
    private val BASE64_URL_RE = Regex("^[A-Za-z0-9_-]{16,}={0,2}$")
    private val HEXISH_RE = Regex("^[0-9a-fA-F]{16,}$")

    /** 驼峰标识符：`nativeInitCipher` / `nativeConstructor` 这类 Java 方法名。 */
    private val CAMEL_RE = Regex("^[a-z]+([A-Z][a-z]+)+$|^[A-Z][a-z]+([A-Z][a-z]+)+$")

    /** Java 包/类路径：`com/example/tool/manager/adb/PairingContext`。 */
    private val JAVA_PATH_SEG_RE = Regex("^[A-Za-z][A-Za-z0-9_]*$")

    /**
     * 一个路径段是否像 Java 标识符。
     *
     * 真 base64 的分段是随机字符，`PairingContext` 这种「PascalCase 单词拼接」
     * 在 base64 里出现的概率可以忽略。允许三种形态：全小写（含数字）、
     * 全大写常量、驼峰/PascalCase。
     */
    private fun isIdentifierSegment(seg: String): Boolean {
        if (!JAVA_PATH_SEG_RE.matches(seg)) return false
        if (seg.none { it.isUpperCase() }) return true   // 全小写
        if (seg.none { it.isLowerCase() }) return true   // 全大写
        return CAMEL_RE.matches(seg)                     // 驼峰 / PascalCase
    }

    /**
     * 【修复】`base64_like` / `hex_like` 之前只用「字符集 + 长度」判定，于是
     * `__register_atfork`（18 字符）、`__system_property_get`（21 字符）、
     * `__android_log_print`（19 字符）、`__stack_chk_fail`、`android_set_abort_message`
     * 这些**普通 C 标识符**全部被标成 base64 候选 —— 样本A.so 的 7 个
     * base64_like 里 5 个是这种误报。
     *
     * base64 / hex 的真实特征不只是字符集，还有：
     *  - 大小写与数字**混合**出现（纯小写 + 下划线的标识符几乎不可能是 base64）；
     *  - base64 的 `+` / `/` / `=` 或大小写交替；
     *  - hex 串通常全小写或全大写且无下划线。
     */
    private fun looksBase64(value: String): Boolean {
        if (!(BASE64_RE.matches(value) || BASE64_URL_RE.matches(value))) return false
        // 含 C 标识符特征（下划线）的一律排除
        if ('_' in value) return false
        // 【修复】绝对路径一律不是 base64。`/proc/self/attr/current` 同时含 `/`（base64
        // 字符集内）与大小写混合，之前被误判成 base64_like。
        if (value.startsWith("/")) return false
        // 含 `.` / `:` / `-`（URL、主机名、版本号）也不是 base64 的常见形态
        if ('.' in value || ':' in value) return false
        // 【修复】驼峰 Java 标识符不是 base64：`nativeInitCipher`(16)、
        // `nativeConstructor`(17) 字符数刚好越过 BASE64_RE 的 16 字符门槛，
        // 且「小写+大写」被算成「大小写混合」，于是 样本C.so 上两者都被判成
        // base64_like。真 base64 是随机字符，不会呈现 小写串+(大写+小写串)+ 的节奏，
        // 也几乎不会完全没有数字。
        if (value.length < 32 && value.none { it.isDigit() } && CAMEL_RE.matches(value)) return false
        // 【修复】Java 包/类路径不是 base64：`com/example/tool/manager/adb/PairingContext`
        // 38 字符、含 `/`、大小写混合，旧判据三条全中。判据是「每段都以小写开头，
        // 或首字母大写且其余全小写」—— 真 base64 的分段不会这么整齐。
        if ('/' in value) {
            val segs = value.split('/')
            if (segs.size >= 2 && segs.all { isIdentifierSegment(it) }) return false
        }
        val hasLower = value.any { it.isLowerCase() }
        val hasUpper = value.any { it.isUpperCase() }
        val hasDigit = value.any { it.isDigit() }
        // 至少两类字符混合，或含 base64 专有符号
        val classes = listOf(hasLower, hasUpper, hasDigit).count { it }
        return classes >= 2 || '+' in value || '/' in value || '=' in value
    }

    private fun looksHex(value: String): Boolean {
        if (!HEXISH_RE.matches(value)) return false
        if ('_' in value) return false
        // 纯十进制长数字不是 hex 串（如时间戳、随机 id 的十进制形态）
        return value.any { it in 'a'..'f' || it in 'A'..'F' }
    }
    private val FORMAT_RE = Regex("%[-#0+ .\\d]*(s|d|u|x|X|o|f|e|g|c|p|n|\\d+\\$)")
    private val JAVA_DESC_RE = Regex("^L[a-zA-Z_$/][\\w/$]*;$")
    private val PACKAGE_RE = Regex("^[a-z][a-z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*){2,}$")

    /**
     * 【修复】BoringSSL 的源码文件名 `hkdf.cc.inc` / `digest.cc.inc` / `e_aes.cc.inc`
     * 形如 `a.b.c` 的三段小写点分串，旧 [PACKAGE_RE] 直接判成 package_name。
     * 包名的段是「标识符」；源码文件名最后一段是已知扩展名（inc/cc/h/c/cpp/...）。
     */
    private val SOURCE_FILE_EXT = setOf(
        "inc", "cc", "cpp", "cxx", "c", "h", "hpp", "hh", "s", "asm",
        "txt", "json", "xml", "yml", "yaml", "md", "cfg", "conf", "ini",
        "bin", "dat", "db", "log", "tmp", "so", "a", "o", "map", "pem", "crt", "key"
    )

    private fun looksPackageName(value: String): Boolean {
        if (!PACKAGE_RE.matches(value)) return false
        val last = value.substringAfterLast('.').lowercase()
        return last !in SOURCE_FILE_EXT
    }
    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val SQL_RE = Regex("(?i)^(select|insert|update|delete|create table)\\b.{4,}$")
    private val VERSION_RE = Regex("^\\d+(\\.\\d+){1,3}([_.-][\\w.]+)?$")

    private fun classify(value: String): List<String> {
        val out = mutableListOf<String>()
        if (URL_RE.matches(value)) out += "url"
        if (IP_PORT_RE.matches(value)) {
            out += "ip_port"
        } else if (HOST_PORT_RE.matches(value) && !PACKAGE_RE.matches(value)) {
            out += "host_port"
        }
        if (JAVA_DESC_RE.matches(value)) out += "java_descriptor"
        if (ABS_PATH_RE.matches(value) && !URL_RE.matches(value)) out += "abs_path"
        if (FORMAT_RE.containsMatchIn(value)) out += "format_string"
        if (looksBase64(value)) out += "base64_like"
        if (looksHex(value)) out += "hex_like"
        if (looksPackageName(value)) out += "package_name"
        if (UUID_RE.matches(value)) out += "uuid"
        if (SQL_RE.matches(value)) out += "sql"
        if (VERSION_RE.matches(value)) out += "version_like"
        if (value.length >= 4 && value.endsWith(".so")) out += "library_name"
        return out
    }

    // ── str_encoding_census ──

    /** UTF-16LE runs inside alloc non-exec sections; the parser only decodes UTF-8. */
    fun utf16LeStrings(data: ByteArray, elf: ElfFile, limit: Int): List<Triple<Long, String, String>> {
        val out = mutableListOf<Triple<Long, String, String>>()
        for (section in elf.sections) {
            if (section.type == 8L || section.size <= 0 || section.flags and 0x4L != 0L || section.flags and 0x2L == 0L) continue
            val start = section.offset.toInt().coerceIn(0, data.size)
            val end = minOf(data.size, start + section.size.toInt())
            var i = start
            var runStart = -1
            while (i + 1 < end) {
                val c = data[i].toInt() and 0xff
                val hi = data[i + 1].toInt() and 0xff
                val printable = c in 0x20..0x7e && hi == 0
                if (printable) {
                    if (runStart < 0) runStart = i
                } else {
                    if (runStart >= 0 && i - runStart >= 8) {
                        val text = String(data, runStart, i - runStart, Charsets.UTF_16LE)
                        out += Triple(section.offset + (runStart - start), text, section.name)
                    }
                    runStart = -1
                }
                i += if (printable) 2 else 1
            }
            if (runStart >= 0 && end - runStart >= 8) {
                val text = String(data, runStart, end - runStart, Charsets.UTF_16LE)
                out += Triple(section.offset + (runStart - start), text, section.name)
            }
            if (out.size >= limit) break
        }
        return out
    }

    fun encodingCensus(data: ByteArray, elf: ElfFile, limit: Int): JSONObject {
        val strings = elf.strings
        var ascii = 0
        var utf8Multi = 0
        var cjk = 0
        val duplicates = HashMap<String, Int>()
        strings.forEach { s ->
            if (s.value.all { it.code < 0x80 }) ascii++ else utf8Multi++
            if (s.value.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) cjk++
            duplicates.merge(s.value, 1, Int::plus)
        }
        val utf16 = utf16LeStrings(data, elf, limit)
        val topDup = JSONArray()
        duplicates.entries.filter { it.value > 1 }.sortedByDescending { it.value }.take(20).forEach { (v, n) ->
            topDup.put(JSONObject().put("value", v.take(120)).put("count", n))
        }
        return JSONObject()
            .put("scannedStrings", strings.size)
            .put("ascii", ascii)
            .put("utf8Multibyte", utf8Multi)
            .put("containsCjk", cjk)
            .put("utf16LeRuns", utf16.size)
            .put(
                "utf16LeSamples",
                JSONArray().apply {
                    utf16.take(30).forEach { (off, text, sec) ->
                        put(JSONObject().put("offset", "0x${off.toString(16)}").put("value", text.take(120)).put("section", sec))
                    }
                }
            )
            .put("duplicateValues", duplicates.count { it.value > 1 })
            .put("topDuplicates", topDup)
            .put(
                "scope",
                JSONObject()
                    .put("utf8Source", "parser string table (.rodata/.strtab/.dynstr)")
                    .put("utf16Source", "raw scan of alloc non-exec sections")
            )
    }

    // ── str_literal_classes ──

    fun literalClasses(elf: ElfFile, limit: Int): JSONObject {
        val counts = LinkedHashMap<String, Int>()
        val items = JSONArray()
        elf.strings.forEach { s ->
            val classes = classify(s.value)
            if (classes.isEmpty()) return@forEach
            classes.forEach { counts.merge(it, 1, Int::plus) }
            if (items.length() < limit) {
                items.put(
                    JSONObject()
                        .put("offset", "0x${s.offset.toString(16)}")
                        .put("section", s.section)
                        .put("value", s.value.take(160))
                        .put("classes", JSONArray(classes))
                )
            }
        }
        val byClass = JSONArray()
        counts.entries.sortedByDescending { it.value }.forEach { (k, v) -> byClass.put(JSONObject().put("class", k).put("count", v)) }
        return JSONObject()
            .put("scannedStrings", elf.strings.size)
            .put("classified", counts.values.sum())
            .put("byClass", byClass)
            .put("items", items)
    }

    // ── net_endpoint_extract ──

    fun netEndpoints(elf: ElfFile, limit: Int): JSONObject {
        val urls = LinkedHashMap<String, JSONObject>()
        val hosts = LinkedHashMap<String, JSONObject>()
        elf.strings.forEach { s ->
            val v = s.value.trim()
            if (URL_RE.matches(v) && urls.size < limit) {
                val host = runCatching { java.net.URI(v).host }.getOrNull()
                urls[v] = JSONObject()
                    .put("url", v.take(200))
                    .put("scheme", v.substringBefore("://"))
                    .put("host", host)
                    .put("offset", "0x${s.offset.toString(16)}")
                    .put("section", s.section)
            } else {
                val m = IP_PORT_RE.matchEntire(v)
                if (m != null && hosts.size < limit) {
                    hosts[v] = JSONObject()
                        .put("host", m.groupValues[1])
                        .put("port", m.groupValues[3].toInt())
                        .put("raw", v)
                        .put("offset", "0x${s.offset.toString(16)}")
                        .put("section", s.section)
                } else if (HOST_PORT_RE.matches(v) && !PACKAGE_RE.matches(v) && hosts.size < limit) {
                    hosts[v] = JSONObject()
                        .put("host", v.substringBeforeLast(':'))
                        .put("port", v.substringAfterLast(':').toIntOrNull() ?: -1)
                        .put("raw", v.take(120))
                        .put("offset", "0x${s.offset.toString(16)}")
                        .put("section", s.section)
                }
            }
        }
        return JSONObject()
            .put("scannedStrings", elf.strings.size)
            .put("urlCount", urls.size)
            .put("hostCount", hosts.size)
            .put("urls", JSONArray(urls.values.take(limit)))
            .put("hosts", JSONArray(hosts.values.take(limit)))
    }

    // ── file_path_roots ──

    private val ROOTS = listOf(
        "/proc/self", "/proc/", "/sys/", "/dev/", "/system/bin/", "/system/xbin/", "/system/lib", "/system/app",
        "/vendor/", "/data/data/", "/data/app/", "/data/local/tmp/", "/data/local/", "/data/misc/", "/data/",
        "/sdcard/", "/storage/emulated/", "/storage/", "/etc/", "/cache/", "/sbin/", "/su", "/magisk"
    )

    private val SENSITIVE = listOf(
        "/proc/self/maps", "/proc/self/status", "/proc/self/cmdline", "/proc/version", "/proc/cpuinfo",
        "su", "magisk", "frida", "xposed", "/system/bin/su", "busybox"
    )

    fun filePathRoots(elf: ElfFile, limit: Int): JSONObject {
        val counts = LinkedHashMap<String, Int>()
        val items = JSONArray()
        val notable = JSONArray()
        elf.strings.forEach { s ->
            val v = s.value.trim()
            if (!ABS_PATH_RE.matches(v)) return@forEach
            val root = ROOTS.firstOrNull { v.startsWith(it) } ?: "/"
            counts.merge(root, 1, Int::plus)
            if (items.length() < limit) {
                items.put(JSONObject().put("path", v.take(160)).put("root", root).put("section", s.section).put("offset", "0x${s.offset.toString(16)}"))
            }
            if (notable.length() < 40 && SENSITIVE.any { v.contains(it, ignoreCase = true) }) {
                notable.put(JSONObject().put("path", v.take(160)).put("reason", "anti-analysis or privilege surface"))
            }
        }
        val byRoot = JSONArray()
        counts.entries.sortedByDescending { it.value }.forEach { (k, v) -> byRoot.put(JSONObject().put("root", k).put("count", v)) }
        return JSONObject()
            .put("scannedStrings", elf.strings.size)
            .put("absPathCount", counts.values.sum())
            .put("byRoot", byRoot)
            .put("items", items)
            .put("notable", notable)
    }

    // ── env_property_keys ──

    private val PROPERTY_RE = Regex("^(ro\\.|persist\\.|sys\\.|debug\\.|service\\.|ctl\\.|vendor\\.|hw\\.|ro\\.build\\.)[\\w.]+")
    private val ENV_KEYS = setOf(
        "PATH", "CLASSPATH", "LD_LIBRARY_PATH", "LD_PRELOAD", "TMPDIR", "HOME", "ANDROID_DATA",
        "ANDROID_ROOT", "BOOTCLASSPATH", "ANDROID_ART_ROOT", "DEX2OATBOOTCLASSPATH", "I18N_CLASSPATH"
    )

    fun propertyKeys(elf: ElfFile, limit: Int): JSONObject {
        val properties = JSONArray()
        val env = JSONArray()
        elf.strings.forEach { s ->
            val v = s.value.trim()
            if (properties.length() < limit && PROPERTY_RE.matches(v)) {
                properties.put(JSONObject().put("key", v.take(120)).put("section", s.section).put("offset", "0x${s.offset.toString(16)}"))
            } else if (env.length() < 40 && v in ENV_KEYS) {
                env.put(JSONObject().put("key", v).put("section", s.section))
            }
        }
        return JSONObject()
            .put("scannedStrings", elf.strings.size)
            .put("propertyLikeKeys", properties.length())
            .put("properties", properties)
            .put("envKeys", env)
            .put(
                "method",
                "String-shape only: keys that look like Android system properties or known env names. " +
                    "Call-site binding (getenv/__system_property_get) is not proven without disassembly."
            )
    }
}
