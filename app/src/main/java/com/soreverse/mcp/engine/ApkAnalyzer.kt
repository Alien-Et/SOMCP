package com.soreverse.mcp.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

internal class ApkAnalysisLimitException(message: String) : IllegalArgumentException(message)

internal object ApkAnalyzer {
    /**
     * 【魔改】APK 输入上限。
     *
     * 原值 512 MiB 改为 4 GiB（实质上等于不限制），配合 largeHeap 与 root 直读，
     * 可以分析任意大包。
     */
    const val MAX_INPUT_BYTES = 4L * 1024L * 1024L * 1024L

    /**
     * 【魔改】条目/解析上限同步放开。
     */
    data class Limits(
        val maxEntries: Int = 1_000_000,
        val maxParsedEntryBytes: Int = 1024 * 1024 * 1024,
        val maxParsedTotalBytes: Long = 8L * 1024L * 1024L * 1024L
    )

    /**
     * 【魔改】大 APK 流式解析入口：不把整个文件读进 ByteArray（386MB 包
     * 一次性 readBytes() 会 OOM/IO_ERROR）。ZIP 条目用 FileInputStream 逐个读，
     * sha256 流式计算，Flutter 检测走 inspectApk(File) 重载。
     */
    fun analyzeFile(file: File, path: String, entryLimit: Int, limits: Limits = Limits()): JSONObject {
        val size = file.length()
        if (size > MAX_INPUT_BYTES) {
            throw ApkAnalysisLimitException(
                "APK exceeds ${MAX_INPUT_BYTES / 1024 / 1024} MiB input limit"
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val entries = JSONArray()
        val nativeLibs = JSONArray()
        val dexFiles = JSONArray()
        val signatures = JSONArray()
        val abis = linkedSetOf<String>()
        var manifest = JSONObject().put("present", false)
        var resourcesArsc = false
        var totalEntries = 0
        var parsedBytes = 0L
        ZipInputStream(FileInputStream(file).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                totalEntries++
                if (totalEntries > limits.maxEntries) {
                    throw ApkAnalysisLimitException(
                        "APK contains more than ${limits.maxEntries} entries"
                    )
                }
                val name = entry.name
                if (entries.length() < entryLimit.coerceIn(1, 5000)) {
                    entries.put(
                        JSONObject()
                            .put("name", name)
                            .put("size", entry.size.takeIf { it >= 0 } ?: -1L)
                            .put("compressedSize", entry.compressedSize)
                            .put("directory", entry.isDirectory)
                    )
                }
                if (!entry.isDirectory && name.matches(Regex("^lib/[^/]+/[^/]+\\.so$"))) {
                    val abi = name.split('/')[1]
                    abis.add(abi)
                    nativeLibs.put(JSONObject().put("name", name).put("abi", abi))
                }
                if (!entry.isDirectory && name.endsWith(".dex", true)) {
                    dexFiles.put(name)
                }
                if (!entry.isDirectory &&
                    (name.startsWith("META-INF/") &&
                        (name.endsWith(".RSA", true) ||
                            name.endsWith(".DSA", true) ||
                            name.endsWith(".EC", true) ||
                            name.endsWith(".SF", true) ||
                            name.endsWith("MANIFEST.MF", true))
                        )
                ) {
                    signatures.put(name)
                }
                zip.closeEntry()
            }
        }
        // 流式 sha256（DigestInputStream 风格，手动读块）
        FileInputStream(file).buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val flutter = runCatching {
            FlutterArtifactInspector.inspectApk(file).getJSONObject("flutter")
        }.getOrDefault(JSONObject())
        return JSONObject()
            .put("path", path)
            .put("size", size)
            .put("sha256", sha)
            .put("parser", "builtin_apk_zip_streaming")
            .put("externalApkMcpRequired", false)
            .put("entryCount", totalEntries)
            .put("entriesTruncated", totalEntries > entries.length())
            .put("entries", entries)
            .put("nativeLibs", nativeLibs)
            .put("dexFiles", dexFiles)
            .put("signatures", signatures)
            .put("abis", JSONArray(abis.toList()))
            .put("manifest", manifest)
            .put("resourcesArsc", resourcesArsc)
            .put("flutter", flutter)
            .put("parsedBytes", parsedBytes)
    }

    fun analyze(bytes: ByteArray, path: String, entryLimit: Int, limits: Limits = Limits()): JSONObject {
        if (bytes.size.toLong() >
            MAX_INPUT_BYTES
        ) {
            throw ApkAnalysisLimitException(
                "APK exceeds ${MAX_INPUT_BYTES / 1024 / 1024} MiB input limit"
            )
        }
        val entries = JSONArray()
        val nativeLibs = JSONArray()
        val dexFiles = JSONArray()
        val signatures = JSONArray()
        val abis = linkedSetOf<String>()
        var manifest = JSONObject().put("present", false)
        var resourcesArsc = false
        var totalEntries = 0
        var parsedBytes = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                totalEntries++
                if (totalEntries >
                    limits.maxEntries
                ) {
                    throw ApkAnalysisLimitException(
                        "APK contains more than ${limits.maxEntries} entries"
                    )
                }
                val name = entry.name
                val size = entry.size.takeIf { it >= 0 } ?: -1L
                if (entries.length() <
                    entryLimit.coerceIn(1, 5000)
                ) {
                    entries.put(
                        JSONObject().put(
                            "name",
                            name
                        ).put(
                            "size",
                            size
                        ).put(
                            "compressedSize",
                            entry.compressedSize
                        ).put("directory", entry.isDirectory)
                    )
                }
                if (!entry.isDirectory && name.matches(Regex("^lib/[^/]+/[^/]+\\.so$"))) {
                    val abi = name.split('/')[1]
                    abis += abi
                    nativeLibs.put(
                        JSONObject().put(
                            "entry",
                            name
                        ).put(
                            "abi",
                            abi
                        ).put("name", name.substringAfterLast('/')).put("size", size)
                    )
                }
                if (!entry.isDirectory && name.matches(Regex("^classes(?:[2-9][0-9]*)?\\.dex$"))) {
                    val data = readEntry(zip, name, limits, parsedBytes)
                    parsedBytes += data.size
                    dexFiles.put(parseDexHeader(name, data))
                } else if (!entry.isDirectory && name == "AndroidManifest.xml") {
                    val data = readEntry(zip, name, limits, parsedBytes)
                    parsedBytes += data.size
                    manifest = parseManifestSummary(data)
                } else if (!entry.isDirectory && name == "resources.arsc") {
                    resourcesArsc = true
                } else if (!entry.isDirectory &&
                    name.startsWith("META-INF/", true) &&
                    (
                        name.endsWith(".RSA", true) ||
                            name.endsWith(".DSA", true) ||
                            name.endsWith(".EC", true) ||
                            name.endsWith(".SF", true) ||
                            name.endsWith("MANIFEST.MF", true)
                        )
                ) {
                    signatures.put(name)
                }
                zip.closeEntry()
            }
        }
        val flutter = FlutterArtifactInspector.inspectApk(bytes, path).getJSONObject("flutter")
        return JSONObject().put(
            "path",
            path
        ).put(
            "size",
            bytes.size
        ).put(
            "sha256",
            sha256(bytes)
        ).put(
            "parser",
            "builtin_apk_zip_dex_axml"
        ).put("externalApkMcpRequired", false).put("entryCount", totalEntries).put(
            "entriesTruncated",
            totalEntries > entries.length()
        ).put(
            "entries",
            entries
        ).put(
            "abis",
            JSONArray(abis.toList())
        ).put(
            "nativeLibraries",
            nativeLibs
        ).put(
            "dexFiles",
            dexFiles
        ).put(
            "manifest",
            manifest
        ).put("resourcesArsc", resourcesArsc).put("v1SignatureFiles", signatures).put(
            "hasV1Signature",
            signatures.length() > 0
        ).put(
            "flutter",
            flutter
        ).put(
            "limitations",
            JSONArray(
                listOf(
                    "APK Signature Scheme v2/v3/v4 block verification is not included in this basic parser",
                    "Binary AndroidManifest attributes require the full resource table resolver"
                )
            )
        )
    }

    private fun readEntry(zip: ZipInputStream, name: String, limits: Limits, parsedBefore: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var entryBytes = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            entryBytes += count
            if (entryBytes >
                limits.maxParsedEntryBytes
            ) {
                throw ApkAnalysisLimitException(
                    "APK entry $name exceeds ${limits.maxParsedEntryBytes} byte parse limit"
                )
            }
            if (parsedBefore + entryBytes >
                limits.maxParsedTotalBytes
            ) {
                throw ApkAnalysisLimitException(
                    "APK parsed entries exceed ${limits.maxParsedTotalBytes} byte total limit"
                )
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun parseDexHeader(name: String, dex: ByteArray): JSONObject {
        fun u32(offset: Int): Long {
            if (offset + 4 > dex.size) return 0
            return (dex[offset].toLong() and 0xff) or ((dex[offset + 1].toLong() and 0xff) shl 8) or
                ((dex[offset + 2].toLong() and 0xff) shl 16) or
                ((dex[offset + 3].toLong() and 0xff) shl 24)
        }
        val magic = if (dex.size >=
            8
        ) {
            String(dex.copyOfRange(0, 8), Charsets.ISO_8859_1).replace("\u0000", "\\0")
        } else {
            ""
        }
        return JSONObject().put("entry", name).put("size", dex.size).put("magic", magic).put(
            "valid",
            dex.size >= 0x70 &&
                dex[0] == 'd'.code.toByte() &&
                dex[1] == 'e'.code.toByte() &&
                dex[2] == 'x'.code.toByte() &&
                dex[3] == '\n'.code.toByte()
        ).put(
            "fileSize",
            u32(0x20)
        ).put(
            "headerSize",
            u32(0x24)
        ).put(
            "endianTag",
            "0x${u32(0x28).toString(16)}"
        ).put(
            "stringIds",
            u32(0x38)
        ).put(
            "typeIds",
            u32(0x40)
        ).put(
            "protoIds",
            u32(0x48)
        ).put("fieldIds", u32(0x50)).put("methodIds", u32(0x58)).put("classDefs", u32(0x60))
    }

    private fun parseManifestSummary(data: ByteArray): JSONObject {
        val binary = data.size >= 8 && data[0] == 0x03.toByte() && data[1] == 0x00.toByte()
        val printable = if (!binary) String(data, Charsets.UTF_8).take(4096) else ""
        return JSONObject().put(
            "present",
            true
        ).put(
            "size",
            data.size
        ).put(
            "format",
            if (binary) "android_binary_xml" else "text_xml"
        ).put("textPreview", if (printable.isBlank()) JSONObject.NULL else printable)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }
}
