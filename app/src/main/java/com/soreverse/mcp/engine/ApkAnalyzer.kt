package com.soreverse.mcp.engine

import com.soreverse.mcp.core.RootShell
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

internal class ApkAnalysisLimitException(message: String) : IllegalArgumentException(message)

/** 输入不是 ZIP/APK（magic 不匹配）。调用方应映射为 APK_INVALID。 */
internal class ApkNotZipException(message: String) : IllegalArgumentException(message)

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
        // 【修复】旧实现没有 magic 校验：把 .so / 文本 / 空文件丢进来
        // 会得到一份「0 个条目但 ok:true」的空结果。现在先读 4 字节判定。
        requireZipMagic(file, path)
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
                if (!entry.isDirectory && name == "resources.arsc") resourcesArsc = true
                // 【修复】旧实现只收集条目名，manifest / dex / parsedBytes
                // 声明后从未赋值，永远是 present:false / 0 —— 等于什么都没解析。
                // 现在对 AndroidManifest.xml 和 classes*.dex 读内容做真实解析。
                val shouldParse = !entry.isDirectory &&
                    (name == "AndroidManifest.xml" ||
                        name.matches(Regex("^classes(?:[2-9][0-9]*)?\\.dex$")))
                if (shouldParse) {
                    val data = readEntry(zip, name, limits, parsedBytes)
                    parsedBytes += data.size
                    if (name == "AndroidManifest.xml") {
                        manifest = parseManifestSummary(data)
                    } else {
                        dexFiles.put(parseDexHeader(name, data))
                    }
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

    /**
     * 【魔改】任意受保护路径的 APK 分析：root 的 `cat` 管道 → ZipInputStream
     * 流式解析，不进堆、不落盘副本。用于 App 直读 EACCES 时的兜底。
     *
     * 【修复 1】旧实现用 DigestInputStream 包住 ZipInputStream 的输入，
     * 而 ZipInputStream 读到 central directory 就停了，不会把文件读完 ——
     * 于是 sha256 只覆盖了「前 N 字节」，且 N 随内部缓冲预读量漂移，
     * 同一个文件两次调用会得到两个不同的 hash（实测 386MB 包在
     * fd53cae8… 与 2bd19f75… 之间摆动）。现在改成：ZIP 遍历结束后
     * 继续把流里剩余字节全部读完再收摘要，得到的是真正的全文件 sha256。
     *
     * 【修复 2】旧实现没有 ZIP magic 校验，空流/ELF/文本都会返回
     * 「entryCount=0, ok=true」。现在先读 4 字节做 magic 判断，
     * 不是 ZIP 直接抛 [ApkNotZipException]。
     *
     * 【修复 3】旧实现的 manifest / resourcesArsc / parsedBytes / flutter
     * 全是声明后从未赋值的常量，等于什么都没解析。现在对
     * AndroidManifest.xml / classes*.dex 做真实解析（流内单条目读取），
     * Flutter 检测复用已经收集到的条目清单。
     */
    fun analyzeRootStream(path: String, entryLimit: Int, limits: Limits = Limits()): JSONObject {
        val declaredSize = RootShell.fileSize(path) ?: -1L
        if (declaredSize > MAX_INPUT_BYTES) {
            throw ApkAnalysisLimitException(
                "APK exceeds ${MAX_INPUT_BYTES / 1024 / 1024} MiB input limit"
            )
        }
        // 目录不是 ZIP：openStream 的 exit-code 检查会抛 IOException，
        // 这里提前用 isDirectory 给一个更明确的错误。
        if (RootShell.isDirectory(path)) {
            throw ApkNotZipException("$path is a directory, not a ZIP/APK file")
        }
        val stream = RootShell.openStream(path, declaredSize)
            ?: throw java.io.IOException("root stream unavailable for $path")

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
        var totalRead = 0L
        val boundedEntryLimit = entryLimit.coerceIn(1, 5000)

        stream.use { input ->
            val counting = CountingInputStream(input) { n, buf, off ->
                digest.update(buf, off, n)
            }
            val buffered = java.io.BufferedInputStream(counting, 64 * 1024)
            // magic 校验：ZIP 本地文件头固定是 50 4B 03/05/07
            val magic = ByteArray(4)
            var magicLen = 0
            while (magicLen < 4) {
                val n = buffered.read(magic, magicLen, 4 - magicLen)
                if (n < 0) break
                magicLen += n
            }
            if (magicLen < 4 || magic[0] != 0x50.toByte() || magic[1] != 0x4b.toByte()) {
                throw ApkNotZipException(
                    "$path is not a ZIP/APK file (magic=${magic.take(magicLen).joinToString("") { "%02x".format(it) }})"
                )
            }
            // 【关键】ZipInputStream.close() 会关掉它包装的流。用一层
            // NonClosing 包装，让它在 use 块结束时不去关 buffered ——
            // 否则下面读 ZIP 尾部时立刻抛 "Stream closed"。
            val nonClosing = NonClosingInputStream(buffered)
            val replay = java.io.SequenceInputStream(java.io.ByteArrayInputStream(magic, 0, magicLen), nonClosing)
            ZipInputStream(replay).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    totalEntries++
                    if (totalEntries > limits.maxEntries) {
                        throw ApkAnalysisLimitException("APK contains more than ${limits.maxEntries} entries")
                    }
                    val name = entry.name
                    val declaredEntrySize = entry.size
                    if (entries.length() < boundedEntryLimit) {
                        entries.put(
                            JSONObject()
                                .put("name", name)
                                .put("size", declaredEntrySize.takeIf { it >= 0 } ?: -1L)
                                .put("compressedSize", entry.compressedSize)
                                .put("directory", entry.isDirectory)
                        )
                    }
                    if (!entry.isDirectory && name.matches(Regex("^lib/[^/]+/[^/]+\\.so$"))) {
                        val abi = name.split('/')[1]
                        abis.add(abi)
                        nativeLibs.put(
                            JSONObject().put("name", name).put("abi", abi)
                                .put("size", declaredEntrySize.takeIf { it >= 0 } ?: -1L)
                        )
                    }
                    if (!entry.isDirectory && name.endsWith(".dex", true)) dexFiles.put(name)
                    if (!entry.isDirectory && name.startsWith("META-INF/") &&
                        (name.endsWith(".RSA", true) || name.endsWith(".DSA", true) ||
                            name.endsWith(".EC", true) || name.endsWith(".SF", true) ||
                            name.endsWith("MANIFEST.MF", true))
                    ) signatures.put(name)
                    if (!entry.isDirectory && name == "resources.arsc") resourcesArsc = true
                    // 真正解析：只对这两个目标读内容，其余条目只走条目头
                    val shouldParse = !entry.isDirectory &&
                        (name == "AndroidManifest.xml" ||
                            name.matches(Regex("^classes(?:[2-9][0-9]*)?\\.dex$")))
                    if (shouldParse) {
                        val data = readEntry(zip, name, limits, parsedBytes)
                        parsedBytes += data.size
                        if (name == "AndroidManifest.xml") {
                            manifest = parseManifestSummary(data)
                        } else {
                            dexFiles.put(parseDexHeader(name, data))
                        }
                    }
                    zip.closeEntry()
                }
            }
            // 【修复 1】ZipInputStream 在 central directory 就停了，剩余字节
            // 必须继续读空，sha256 才是整个文件的。ZipInputStream 内部缓冲里
            // 多读的字节已经进过 digest，这里从 buffered 继续读不会重复计数。
            val tail = ByteArray(64 * 1024)
            while (true) {
                val n = buffered.read(tail)
                if (n < 0) break
                totalRead += n
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val size = if (declaredSize >= 0) declaredSize else totalRead
        val flutter = runCatching {
            FlutterArtifactInspector.inspectFlutterFromEntries(entries, path)
        }.getOrDefault(JSONObject())
        return JSONObject()
            .put("path", path)
            .put("size", size)
            .put("sha256", sha)
            .put("parser", "builtin_apk_zip_root_stream")
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
            .put(
                "limitations",
                JSONArray(
                    listOf(
                        "Streaming root path: only AndroidManifest.xml and classes*.dex payloads are parsed",
                        "APK Signature Scheme v2/v3/v4 block verification is not included in this parser (use apk_sig_verify)"
                    )
                )
            )
    }

    /** 读文件头 4 字节确认是 ZIP；不是就抛 [ApkNotZipException]。 */
    private fun requireZipMagic(file: File, path: String) {
        val magic = ByteArray(4)
        val n = try {
            FileInputStream(file).use { it.read(magic) }
        } catch (t: Throwable) {
            throw java.io.IOException("Unable to read $path: ${t.message}")
        }
        if (n < 4 || magic[0] != 0x50.toByte() || magic[1] != 0x4b.toByte()) {
            throw ApkNotZipException(
                "$path is not a ZIP/APK file (magic=${magic.take(maxOf(n, 0)).joinToString("") { "%02x".format(it) }})"
            )
        }
    }

    /** 统计流经的字节数，同时把每一段喂给摘要。 */
    private class CountingInputStream(
        private val delegate: java.io.InputStream,
        private val onBytes: (Int, ByteArray, Int) -> Unit
    ) : java.io.InputStream() {
        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) {
                val one = byteArrayOf(b.toByte())
                onBytes(1, one, 0)
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) onBytes(n, b, off)
            return n
        }

        override fun available(): Int = delegate.available()
        override fun close() = delegate.close()
    }

    /** 让 ZipInputStream.close() 不要连带关掉底层流（尾部还要继续读）。 */
    private class NonClosingInputStream(delegate: java.io.InputStream) : java.io.FilterInputStream(delegate) {
        override fun close() {
            // 故意不关：调用方在读完整包之后自己 close 底层
        }
    }

    fun analyze(bytes: ByteArray, path: String, entryLimit: Int, limits: Limits = Limits()): JSONObject {
        if (bytes.size.toLong() >
            MAX_INPUT_BYTES
        ) {
            throw ApkAnalysisLimitException(
                "APK exceeds ${MAX_INPUT_BYTES / 1024 / 1024} MiB input limit"
            )
        }
        // 【修复】同上：内存路径也补 magic 校验。
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) {
            throw ApkNotZipException(
                "$path is not a ZIP/APK file (magic=${
                    bytes.take(4).joinToString("") { "%02x".format(it) }
                })"
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
