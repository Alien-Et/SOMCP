// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Native build profile logic behind android_ident_note, ndk_toolchain_fingerprint
// and libcxx_variant. Evidence comes from the note section, the string table and
// the dynamic symbol surface; every claim carries its evidence inline.
package com.soreverse.mcp.engine

import org.json.JSONArray
import org.json.JSONObject

internal object NativeProfile {
    private fun u32(data: ByteArray, off: Int, little: Boolean): Long {
        if (off < 0 || off + 4 > data.size) return 0L
        return java.nio.ByteBuffer.wrap(data, off, 4).order(
            if (little) java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN
        ).int.toLong() and 0xffffffffL
    }

    private fun sectionBytes(data: ByteArray, section: SectionInfo): ByteArray? {
        if (section.type == 8L || section.size <= 0) return null
        val start = section.offset.toInt().coerceIn(0, data.size)
        val end = minOf(data.size, start + section.size.toInt())
        if (start >= end) return null
        return data.copyOfRange(start, end)
    }

    private fun cstr(data: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= data.size) return ""
        var end = offset
        while (end < data.size && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.UTF_8)
    }

    // ── android_ident_note ──

    /** Parses ELF notes (PT_NOTE segments / SHT_NOTE sections) as (name, type, desc) triples. */
    fun notes(data: ByteArray, elf: ElfFile): List<Triple<String, Int, ByteArray>> {
        val out = mutableListOf<Triple<String, Int, ByteArray>>()
        val regions = mutableListOf<Pair<Long, Long>>()
        elf.programHeaders.filter { it.type == 4L }.forEach { regions += it.offset to it.filesz }
        elf.sections.filter { it.type == 7L && it.size > 0 }.forEach { regions += it.offset to it.size }
        regions.forEach { (off, size) ->
            var pos = off.toInt().coerceIn(0, data.size)
            val end = minOf(data.size, pos + size.toInt())
            while (pos + 12 <= end && out.size < 64) {
                val namesz = u32(data, pos, elf.littleEndian).toInt()
                val descsz = u32(data, pos + 4, elf.littleEndian).toInt()
                val type = u32(data, pos + 8, elf.littleEndian).toInt()
                if (namesz < 0 || descsz < 0 || namesz > 256 || descsz > 65536) break
                val nameStart = pos + 12
                if (nameStart + namesz > end) break
                val name = String(data, nameStart, namesz, Charsets.UTF_8).trimEnd('\u0000')
                val descStart = nameStart + ((namesz + 3) / 4) * 4
                if (descStart + descsz > end) break
                out += Triple(name, type, data.copyOfRange(descStart, descStart + descsz))
                pos = descStart + ((descsz + 3) / 4) * 4
            }
        }
        return out
    }

    fun androidIdent(data: ByteArray, elf: ElfFile): JSONObject {
        val result = JSONObject().put("found", false)
        val notes = notes(data, elf)
        val android = notes.firstOrNull { it.first == "Android" }
        if (android == null) {
            return result
                .put("noteCount", notes.size)
                .put("method", "Scans PT_NOTE segments and SHT_NOTE sections for name=Android.")
        }
        val desc = android.third
        result.put("found", true).put("descSize", desc.size)
        // 【修复】AOSP 的 .note.android.ident desc 布局是
        //   { uint32 android_api_level; char ndk_version[64]; char ndk_build_number[64]; }
        // 共 132 字节（实测 descsz = 0x84，正好 4 + 64 + 64）。旧实现把 offset 4 当
        // 「打包整数」、把 offset 8 当 build number 字符串，于是：
        //   - NDK 版本 "r21b" 的 ASCII（72 32 31 62）被读成 1647391346，再按位切片
        //     "格式化"成现实中不存在的 49.196.12914；
        //   - build number 从 offset 8 起取到 60 个 NUL，再接上真值 6352462。
        if (desc.size >= 4) {
            result.put("sdkVersion", u32(desc, 0, elf.littleEndian))
        }
        readFixedString(desc, 4, 64)?.let { result.put("ndkVersion", it) }
        readFixedString(desc, 68, 64)?.let { result.put("buildNumber", it) }
        return result
    }

    /** 读 desc 中 [offset] 起的定长 NUL 结尾 ASCII 字段；为空则返回 null（不下发该键）。 */
    private fun readFixedString(desc: ByteArray, offset: Int, maxLen: Int): String? {
        if (offset >= desc.size) return null
        val end = minOf(desc.size, offset + maxLen)
        val text = String(desc, offset, end - offset, Charsets.US_ASCII)
            .substringBefore('\u0000')
            .trim()
        return text.ifEmpty { null }
    }

    // ── ndk_toolchain_fingerprint ──

    private val CLANG_RE = Regex("Android \\(\\d+\\) clang version (\\d+\\.\\d+\\.\\d+)")
    private val CLANG_PLAIN_RE = Regex("clang version (\\d+\\.\\d+\\.\\d+)")
    private val GCC_RE = Regex("(?:Android/)?[\\w-]+-gcc .*?(\\d+\\.\\d+\\.\\d+)")

    fun toolchainFingerprint(data: ByteArray, elf: ElfFile): JSONObject {
        val evidence = JSONArray()
        var compiler = "unknown"
        var version: String? = null
        for (s in elf.strings) {
            val v = s.value
            val clang = CLANG_RE.find(v) ?: CLANG_PLAIN_RE.find(v)
            if (clang != null) {
                compiler = "clang"
                if (version == null) version = clang.groupValues[1]
                if (evidence.length() < 20) {
                    evidence.put(
                        JSONObject().put("kind", "clang_version").put("value", v.take(120))
                            .put("section", s.section).put("offset", "0x${s.offset.toString(16)}")
                    )
                }
                continue
            }
            if (compiler == "unknown" && v.contains("GCC: (")) {
                compiler = "gcc"
                val g = GCC_RE.find(v)
                if (g != null) version = g.groupValues[1]
                if (evidence.length() < 20) {
                    evidence.put(
                        JSONObject().put("kind", "gcc_version").put("value", v.take(120))
                            .put("section", s.section).put("offset", "0x${s.offset.toString(16)}")
                    )
                }
            }
        }
        val symbols = elf.dynSymbols.map { it.name }
        val sanitizers = listOf("__asan_", "__msan_", "__tsan_", "__ubsan_").filter { prefix -> symbols.any { it.startsWith(prefix) } }
        if (sanitizers.isNotEmpty()) {
            evidence.put(JSONObject().put("kind", "sanitizer_symbols").put("value", sanitizers.joinToString(",")))
        }
        val ltoSections = elf.sections.filter { it.name in setOf(".llvmbc", ".llvm_addrsig") }.map { it.name }
        if (ltoSections.isNotEmpty()) evidence.put(JSONObject().put("kind", "lto_sections").put("value", ltoSections.joinToString(",")))
        val rustMarker = symbols.any { it.startsWith("_ZN") && it.contains("17rust") } ||
            elf.strings.any { it.value.contains("rustc") }
        if (rustMarker) evidence.put(JSONObject().put("kind", "rust_runtime").put("value", "rustc strings/symbols"))
        return JSONObject()
            .put("compiler", compiler)
            .put("version", version)
            .put("sanitizers", JSONArray(sanitizers))
            .put("lto", ltoSections.isNotEmpty())
            .put("rust", rustMarker)
            .put("evidence", evidence)
            .put(
                "method",
                "Version strings from the string table plus sanitizer/LTO/rust markers from symbols and sections. " +
                    "A stripped, string-less binary may legitimately report unknown."
            )
    }

    // ── libcxx_variant ──

    fun libcxxVariant(data: ByteArray, elf: ElfFile): JSONObject {
        val needed = ElfAudit.neededList(data, elf)
        val evidence = JSONArray()
        var variant = "none"
        when {
            needed.any { it.startsWith("libc++_shared") } -> {
                variant = "libc++_dynamic"
                evidence.put(JSONObject().put("kind", "dt_needed").put("value", needed.first { it.startsWith("libc++_shared") }))
            }
            needed.any { it.startsWith("libstdc++") } -> {
                variant = "libstdc++_dynamic"
                evidence.put(JSONObject().put("kind", "dt_needed").put("value", needed.first { it.startsWith("libstdc++") }))
            }
            needed.any { it.startsWith("libgnustl") || it.startsWith("libstlport") } -> {
                variant = "legacy_stl_dynamic"
                evidence.put(JSONObject().put("kind", "dt_needed").put("value", "legacy STL library in DT_NEEDED"))
            }
            elf.dynSymbols.any { it.name.startsWith("_ZNSt") || it.name.startsWith("_ZSt") } -> {
                variant = "stl_static"
                evidence.put(JSONObject().put("kind", "dynsym").put("value", "C++ standard library mangled symbols present without STL DT_NEEDED"))
            }
        }
        val cxaThrow = elf.dynSymbols.any { it.name == "__cxa_throw" }
        val rtti = elf.dynSymbols.any { it.name.startsWith("_ZTI") }
        val vtable = elf.dynSymbols.any { it.name.startsWith("_ZTV") }
        if (cxaThrow) evidence.put(JSONObject().put("kind", "exception_runtime").put("value", "__cxa_throw in dynsym"))
        return JSONObject()
            .put("variant", variant)
            .put("neededLibraries", JSONArray(needed))
            .put("exceptions", cxaThrow)
            .put("rttiHints", rtti)
            .put("vtablesInDynsym", vtable)
            .put("evidence", evidence)
    }
}
