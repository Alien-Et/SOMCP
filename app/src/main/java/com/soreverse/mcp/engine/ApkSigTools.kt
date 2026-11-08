// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Pure JVM-testable APK analysis behind apk_sig_verify, apk_cert_info,
// apk_zip_anomaly and apk_abi_coverage. The signing-block digest recomputation
// follows the APK Signature Scheme v2 spec: chunk digest = hash(0xa5 ||
// uint32le(chunkLen) || chunk) over 1MB chunks of sections 1/3/4, top digest =
// hash(0x5a || uint32le(chunkCount) || concat(chunk digests)), and the EOCD
// central-directory offset field treated as the signing-block offset.
package com.soreverse.mcp.engine

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.Inflater
import org.json.JSONArray
import org.json.JSONObject

internal object ApkSigTools {
    private const val CHUNK_SIZE = 1024 * 1024
    private const val CD_SIG = 0x02014b50L
    private const val LFH_SIG = 0x04034b50L
    private const val EOCD_SIG = 0x06054b50L
    private const val V2_BLOCK_ID = 0x7109871aL
    private const val V3_BLOCK_ID = 0xf05368c0L

    // 【修复】v2/v3 的「内容摘要算法 ID」真实取值是 0x0103 / 0x0104
    // （APK Signature Scheme v2 规范：SIG_ALG 与 DIGEST 共用同一套 algorithm id，
    //  CHUNKED_SHA256 = 0x0103，CHUNKED_SHA512 = 0x0104）。
    // 旧值 0x0301 / 0x0302 是 DSA-with-SHA256 / 一个不存在的编号，导致
    //  - digest 记录被标成 "not_recomputed_0x103"，内容摘要从不参与比对；
    //  - 验签时把 0x0103 当成 DSA 算法 → "invalid"（实际 apksigner 判定 Verifies）。
    private const val DIGEST_CHUNKED_SHA256 = 0x0103L
    private const val DIGEST_CHUNKED_SHA512 = 0x0104L

    private fun u16(b: ByteArray, o: Int): Int {
        if (o < 0 || o + 2 > b.size) return 0
        return (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(b: ByteArray, o: Int): Long {
        if (o < 0 || o + 4 > b.size) return 0L
        return (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or
            ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)
    }

    private fun put32le(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xff)
        out.write((v shr 8) and 0xff)
        out.write((v shr 16) and 0xff)
        out.write((v shr 24) and 0xff)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hexStr(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** ELF magic：7F 45 4C 46（"\x7fELF"）。 */
    private fun isElfMagic(b: ByteArray): Boolean = b.size >= 4 &&
        b[0] == 0x7F.toByte() &&
        b[1] == 0x45.toByte() &&
        b[2] == 0x4C.toByte() &&
        b[3] == 0x46.toByte()

    // ── ZIP layout ──

    data class CdEntry(
        val name: String,
        val method: Int,
        val flags: Int,
        val crc: Long,
        val csize: Long,
        val usize: Long,
        val localOffset: Long,
        val extraLen: Int,
        val commentLen: Int
    )

    data class ZipLayout(val eocdOffset: Int, val cdOffset: Int, val cdSize: Int, val entries: List<CdEntry>)

    fun parseZipLayout(bytes: ByteArray): ZipLayout? {
        if (bytes.size < 22) return null
        var eocd = -1
        val scanFrom = maxOf(0, bytes.size - 22 - 65535)
        var i = bytes.size - 22
        while (i >= scanFrom) {
            if (u32(bytes, i) == EOCD_SIG && u16(bytes, i + 20) == bytes.size - i - 22) {
                eocd = i
                break
            }
            i--
        }
        if (eocd < 0) return null
        val cdOffset = u32(bytes, eocd + 16).toInt()
        val cdSize = u32(bytes, eocd + 12).toInt()
        if (cdOffset < 0 || cdOffset >= bytes.size) return null
        val entries = mutableListOf<CdEntry>()
        var p = cdOffset
        while (p + 46 <= bytes.size && u32(bytes, p) == CD_SIG && entries.size <= 65536) {
            val nameLen = u16(bytes, p + 28)
            val extraLen = u16(bytes, p + 30)
            val commentLen = u16(bytes, p + 32)
            val nameEnd = p + 46 + nameLen
            if (nameEnd > bytes.size) break
            entries += CdEntry(
                name = String(bytes, p + 46, nameLen, Charsets.UTF_8),
                method = u16(bytes, p + 10),
                flags = u16(bytes, p + 8),
                crc = u32(bytes, p + 16),
                csize = u32(bytes, p + 20),
                usize = u32(bytes, p + 24),
                localOffset = u32(bytes, p + 42),
                extraLen = extraLen,
                commentLen = commentLen
            )
            p = nameEnd + extraLen + commentLen
        }
        return ZipLayout(eocd, cdOffset, cdSize, entries)
    }

    /** Reads one entry's decompressed bytes via its local file header. */
    fun readEntry(bytes: ByteArray, entry: CdEntry, maxBytes: Int): ByteArray? {
        val off = entry.localOffset.toInt()
        if (off < 0 || off + 30 > bytes.size || u32(bytes, off) != LFH_SIG) return null
        val nameLen = u16(bytes, off + 26)
        val extraLen = u16(bytes, off + 28)
        val dataStart = off + 30 + nameLen + extraLen
        if (dataStart < 0 || dataStart + entry.csize > bytes.size) return null
        if (entry.csize > maxBytes) return null
        val raw = bytes.copyOfRange(dataStart, dataStart + entry.csize.toInt())
        return when (entry.method) {
            0 -> raw
            8 -> try {
                // 【修复】ZIP 条目的 deflate 是 **raw deflate**（无 zlib 头 / 无 adler32），
                // `Inflater()` 默认 nowrap=false 会去找 2 字节 zlib 头，报
                // "incorrect header check" 后被 catch 成 null —— 于是所有压缩条目都读不出来。
                // apk_abi_coverage 的 parsedBytes 恒 0、apk_cert_info 读不到 META-INF
                // 证书、zipAnomalies 的 .so 检查全部失效，根因都在这里。
                val inflater = Inflater(true)
                inflater.setInput(raw)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0 && inflater.needsInput()) break
                    if (out.size() + n > maxBytes) {
                        inflater.end()
                        return null
                    }
                    out.write(buf, 0, n)
                }
                inflater.end()
                out.toByteArray()
            } catch (error: java.util.zip.DataFormatException) {
                null
            }
            else -> null
        }
    }

    // ── APK Signing Block ──

    data class SigningBlock(val start: Int, val end: Int, val pairs: List<Pair<Long, ByteArray>>)

    fun parseSigningBlock(bytes: ByteArray, layout: ZipLayout): SigningBlock? {
        val cdOffset = layout.cdOffset
        if (cdOffset < 32) return null
        val magic = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
        val magicStart = cdOffset - 24
        if (magicStart < 0) return null
        for (i in magic.indices) {
            if (bytes[magicStart + 8 + i] != magic[i]) return null
        }
        val sizeFooter = u64(bytes, magicStart)
        // sizeFooter counts everything after the leading size field: blockStart = cdOffset - 8 - sizeFooter.
        val blockStart = cdOffset - 8 - sizeFooter.toInt()
        if (blockStart < 0 || u64(bytes, blockStart) != sizeFooter) return null
        val pairs = mutableListOf<Pair<Long, ByteArray>>()
        var p = blockStart + 8
        val pairsEnd = magicStart
        while (p + 12 <= pairsEnd) {
            val pairLen = u64(bytes, p).toInt()
            if (pairLen <= 4 || p + 8 + pairLen > pairsEnd) break
            val id = u32(bytes, p + 8)
            pairs += id to bytes.copyOfRange(p + 12, p + 8 + pairLen)
            p += 8 + pairLen
        }
        return SigningBlock(blockStart, cdOffset, pairs)
    }

    private fun u64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xff)
        return v
    }

    /**
     * Split a v2/v3 block value into its individual signer blocks.
     *
     * 【修复】旧实现直接从 value 的偏移 0 开始读长度、把整段当成一个 signer，漏掉了
     * APK Signature Scheme v2/v3 的**外层 sequence 长度前缀**。真实布局是：
     *
     *     value = uint32 signersSequenceLen
     *             ( uint32 signerLen + signerBytes ) *
     *
     * 于是旧实现把 `signersSequenceLen` 当成第一个 signer 的长度，切出来的「signer」
     * 起点正好错 4 字节 —— 它的第一个 u32 其实是 signedData 的长度，`parseSigner`
     * 按这个长度切完 signedData 就正好用尽整个缓冲区，随后 `q + 4 > signer.size`
     * 成立直接返回 null。这就是 `apk_cert_info` 报 `certCount:0`、
     * `apk_sig_verify` 报 `signers:[]` + `verdict:parsed_unverified` 的原因。
     */
    private fun signerBlocks(value: ByteArray): List<ByteArray> {
        val signers = mutableListOf<ByteArray>()
        if (value.size < 4) return signers
        val seqLen = u32(value, 0).toInt()
        // 只信任声明长度内的字节；声明长度异常时退化为「用满整个 value」。
        val limit = if (seqLen in 0..(value.size - 4)) 4 + seqLen else value.size
        var p = 4
        while (p + 4 <= limit && signers.size <= 64) {
            val len = u32(value, p).toInt()
            if (len <= 0 || p + 4 + len > limit) break
            signers += value.copyOfRange(p + 4, p + 4 + len)
            p += 4 + len
        }
        return signers
    }

    private data class SignerData(
        val minSdk: Int?,
        val maxSdk: Int?,
        val digests: List<Pair<Long, ByteArray>>,
        val certs: List<ByteArray>,
        val signatures: List<Pair<Long, ByteArray>>,
        val signedData: ByteArray
    )

    private fun parseSigner(signer: ByteArray, v3: Boolean): SignerData? {
        var p = 0
        var minSdk: Int? = null
        var maxSdk: Int? = null
        if (v3) {
            if (signer.size < 8) return null
            minSdk = u32(signer, 0).toInt()
            maxSdk = u32(signer, 4).toInt()
            p = 8
        }
        if (p + 4 > signer.size) return null
        val sdLen = u32(signer, p).toInt()
        if (sdLen <= 0 || p + 4 + sdLen > signer.size) return null
        val signedData = signer.copyOfRange(p + 4, p + 4 + sdLen)
        var q = p + 4 + sdLen
        if (q + 4 > signer.size) return null
        val sigsLen = u32(signer, q).toInt()
        val sigsRaw = if (q + 4 + sigsLen <= signer.size) signer.copyOfRange(q + 4, q + 4 + sigsLen) else ByteArray(0)
        q += 4 + sigsLen
        val signatures = mutableListOf<Pair<Long, ByteArray>>()
        var r = 0
        while (r + 4 <= sigsRaw.size) {
            val sigLen = u32(sigsRaw, r).toInt()
            if (sigLen <= 8 || r + 4 + sigLen > sigsRaw.size) break
            // 【修复】v2/v3 的 signature 记录是 `len | algId | innerLen | sig` 四段式
            // （apksig 的 V2SchemeSigner 依次 putInt(algId) / putInt(sig.length) / put(sig)）。
            // 旧实现从 +8 切到 +4+sigLen，把 innerLen 也当成了签名数据的前 4 字节，
            // 于是 RSA 验签必然失败。
            val innerLen = u32(sigsRaw, r + 8).toInt()
            if (innerLen <= 0 || r + 12 + innerLen > sigsRaw.size) break
            signatures += u32(sigsRaw, r + 4) to sigsRaw.copyOfRange(r + 12, r + 12 + innerLen)
            r += 4 + sigLen
        }
        if (q + 4 > signer.size) return null
        val pkLen = u32(signer, q).toInt()
        q += 4 + pkLen
        // signed data: digests, certs, additional attributes
        var s = 0
        val digests = mutableListOf<Pair<Long, ByteArray>>()
        val certs = mutableListOf<ByteArray>()
        if (s + 4 <= signedData.size) {
            val dLen = u32(signedData, s).toInt()
            val dRaw = signedData.copyOfRange(s + 4, minOf(signedData.size, s + 4 + dLen))
            var t = 0
            while (t + 4 <= dRaw.size) {
                val recLen = u32(dRaw, t).toInt()
                if (recLen <= 8 || t + 4 + recLen > dRaw.size) break
                // 【修复】digest 记录同样是 `len | algId | innerLen | digest` 四段式。
                // 旧实现从 +8 切到 +4+recLen，把 innerLen（4 字节）拼进了摘要前面，
                // 所以 expected 变成 "20000000c3e6fd76…"，与 computed 永不相等，
                // 每个签名包都被判 digest_mismatch。
                val innerLen = u32(dRaw, t + 8).toInt()
                if (innerLen <= 0 || t + 12 + innerLen > dRaw.size) break
                digests += u32(dRaw, t + 4) to dRaw.copyOfRange(t + 12, t + 12 + innerLen)
                t += 4 + recLen
            }
            s += 4 + dLen
        }
        if (s + 4 <= signedData.size) {
            val cLen = u32(signedData, s).toInt()
            val cRaw = signedData.copyOfRange(s + 4, minOf(signedData.size, s + 4 + cLen))
            var t = 0
            while (t + 4 <= cRaw.size) {
                val certLen = u32(cRaw, t).toInt()
                if (certLen <= 0 || t + 4 + certLen > cRaw.size) break
                certs += cRaw.copyOfRange(t + 4, t + 4 + certLen)
                t += 4 + certLen
            }
        }
        // 【验证】v2/v3 的签名覆盖范围是 **裸 signed data**（不含外层 uint32 长度前缀）。
        // 已用 openssl 对真实 APK 双向验证：bare → "Verified OK"，
        // prefixed → "bad signature"。apksig 的 V2SchemeVerifier 同样直接喂裸 signedData。
        // （真正的 invalid 根因是签名记录的 innerLen 被拼进了签名字节，见上面的修复。）
        return SignerData(minSdk, maxSdk, digests, certs, signatures, signedData)
    }

    private fun signatureAlgorithmName(id: Long): String = when (id) {
        0x0101L -> "RSASSA-PSS with SHA2-256"
        0x0102L -> "RSASSA-PSS with SHA2-512"
        0x0103L -> "RSASSA-PKCS1-v1_5 with SHA2-256"
        0x0104L -> "RSASSA-PKCS1-v1_5 with SHA2-512"
        0x0201L -> "ECDSA with SHA2-256"
        0x0202L -> "ECDSA with SHA2-512"
        0x0301L -> "DSA with SHA2-256"
        0x0421L -> "Verity RSA PKCS1 SHA2-256"
        0x0423L -> "Verity ECDSA SHA2-256"
        0x0425L -> "Verity DSA SHA2-256"
        else -> "UNKNOWN_0x${id.toString(16)}"
    }

    private fun verifySignature(signedData: ByteArray, signature: ByteArray, algorithmId: Long, cert: X509Certificate): String {
        val (algorithm, pss) = when (algorithmId) {
            0x0101L -> "SHA256withRSA/PSS" to 32
            0x0102L -> "SHA512withRSA/PSS" to 64
            0x0103L -> "SHA256withRSA" to 0
            0x0104L -> "SHA512withRSA" to 0
            0x0201L -> "SHA256withECDSA" to 0
            0x0202L -> "SHA512withECDSA" to 0
            0x0301L -> "SHA256withDSA" to 0
            else -> return "unsupported_algorithm"
        }
        return try {
            val verifier = Signature.getInstance(algorithm)
            if (pss > 0) {
                val digestLen = pss
                verifier.setParameter(
                    java.security.spec.PSSParameterSpec(
                        "SHA-${
                            if (digestLen == 32) "256" else "512"
                        }",
                        "MGF1",
                        java.security.spec.MGF1ParameterSpec.SHA256,
                        digestLen,
                        1
                    )
                )
            }
            verifier.initVerify(cert.publicKey)
            verifier.update(signedData)
            if (verifier.verify(signature)) "verified" else "invalid"
        } catch (error: Exception) {
            "unverified:${error.javaClass.simpleName}"
        }
    }

    /**
     * Spec content digest. Sections: ZIP entries [0, blockStart), central
     * directory [cdOffset, eocdOffset), EOCD digested from a patched copy whose
     * CD-offset field contains the signing-block offset instead.
     */
    internal fun computeContentDigest(apk: ByteArray, blockStart: Int, cdOffset: Int, eocdOffset: Int, shaBits: Int): ByteArray {
        val eocdCopy = apk.copyOfRange(eocdOffset, apk.size)
        if (eocdCopy.size >= 20) put32leAt(eocdCopy, 16, blockStart)
        data class Section(val start: Int, val end: Int, val source: ByteArray)
        val sections = listOf(
            Section(0, blockStart, apk),
            Section(cdOffset, eocdOffset, apk),
            Section(0, eocdCopy.size, eocdCopy)
        )
        val md = MessageDigest.getInstance("SHA-$shaBits")
        val chunkDigests = mutableListOf<ByteArray>()
        var chunkCount = 0
        sections.forEach { sec ->
            var pos = sec.start
            while (pos < sec.end) {
                val chunkLen = minOf(CHUNK_SIZE, sec.end - pos)
                md.update(0xa5.toByte())
                put32le(md, chunkLen)
                md.update(sec.source, pos, chunkLen)
                chunkDigests += md.digest()
                chunkCount++
                pos += chunkLen
            }
        }
        md.update(0x5a.toByte())
        put32le(md, chunkCount)
        chunkDigests.forEach { md.update(it) }
        return md.digest()
    }

    private fun put32leAt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xff).toByte()
        b[o + 1] = ((v shr 8) and 0xff).toByte()
        b[o + 2] = ((v shr 16) and 0xff).toByte()
        b[o + 3] = ((v shr 24) and 0xff).toByte()
    }

    private fun put32le(md: MessageDigest, v: Int) {
        md.update((v and 0xff).toByte())
        md.update(((v shr 8) and 0xff).toByte())
        md.update(((v shr 16) and 0xff).toByte())
        md.update(((v shr 24) and 0xff).toByte())
    }

    private fun digestJson(id: Long, expected: ByteArray, computed: ByteArray?): JSONObject {
        val item = JSONObject()
            .put("digestAlgorithm", "0x${id.toString(16)}")
            .put(
                "algorithmName",
                when (id) {
                    DIGEST_CHUNKED_SHA256 -> "CHUNKED_SHA256"
                    DIGEST_CHUNKED_SHA512 -> "CHUNKED_SHA512"
                    else -> "not_recomputed_0x${id.toString(16)}"
                }
            )
            .put("expected", hexStr(expected))
        if (computed != null) {
            item.put("computed", hexStr(computed)).put("match", computed.contentEquals(expected))
        }
        return item
    }

    /** Full v2/v3 parse + digest recompute + signature verification. */
    fun verify(apk: ByteArray): JSONObject {
        val layout = parseZipLayout(apk)
            ?: return err("APK_INVALID", "not a ZIP: EOCD not found")
        val block = parseSigningBlock(apk, layout)
        val result = JSONObject()
            .put("eocdOffset", layout.eocdOffset)
            .put("centralDirectoryOffset", layout.cdOffset)
        val v1 = layout.entries.any {
            it.name.startsWith("META-INF/", true) &&
                (it.name.endsWith(".RSA", true) || it.name.endsWith(".DSA", true) || it.name.endsWith(".EC", true))
        }
        result.put("v1", JSONObject().put("present", v1).put("scope", "META-INF signature file presence only"))
        if (block == null) {
            result.put("v2", JSONObject().put("present", false)).put("v3", JSONObject().put("present", false))
            result.put("verdict", if (v1) "v1_only" else "unsigned")
            return result
        }
        result.put("signingBlock", JSONObject().put("start", block.start).put("end", block.end).put("pairCount", block.pairs.size))
        val v2Value = block.pairs.firstOrNull { it.first == V2_BLOCK_ID }?.second
        val v3Value = block.pairs.firstOrNull { it.first == V3_BLOCK_ID }?.second
        var anyVerified = false
        var anyInvalid = false
        val signerReports = JSONArray()
        fun handleSigners(value: ByteArray?, v3: Boolean, scheme: String) {
            result.put(if (v3) "v3" else "v2", JSONObject().put("present", value != null))
            val signerList = value?.let { signerBlocks(it) } ?: return
            signerList.forEach { signer ->
                val parsed = parseSigner(signer, v3) ?: return@forEach
                val signerReport = JSONObject()
                    .put("scheme", scheme)
                    .put("certCount", parsed.certs.size)
                parsed.certs.firstOrNull()?.let { der ->
                    runCatching {
                        val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
                        signerReport.put("certSha256", sha256(der))
                        signerReport.put("subject", cert.subjectDN.name.take(160))
                        if (parsed.minSdk != null) signerReport.put("minSdk", parsed.minSdk).put("maxSdk", parsed.maxSdk)
                        // digest recompute per signer's digest records
                        val digestReports = JSONArray()
                        parsed.digests.forEach { (id, expected) ->
                            val computed = when (id) {
                                DIGEST_CHUNKED_SHA256 ->
                                    computeContentDigest(apk, block.start, layout.cdOffset, layout.eocdOffset, 256)
                                DIGEST_CHUNKED_SHA512 ->
                                    computeContentDigest(apk, block.start, layout.cdOffset, layout.eocdOffset, 512)
                                else -> null
                            }
                            digestReports.put(digestJson(id, expected, computed))
                        }
                        signerReport.put("digests", digestReports)
                        // signature verification over signed data
                        val sigReports = JSONArray()
                        parsed.signatures.forEach { (sigId, sig) ->
                            val status = verifySignature(parsed.signedData, sig, sigId, cert)
                            if (status == "verified") anyVerified = true
                            if (status == "invalid") anyInvalid = true
                            sigReports.put(
                                JSONObject()
                                    .put("signatureAlgorithm", "0x${sigId.toString(16)}")
                                    .put("algorithmName", signatureAlgorithmName(sigId))
                                    .put("status", status)
                            )
                        }
                        signerReport.put("signatures", sigReports)
                    }.onFailure { error ->
                        signerReport.put("certParseError", error.message ?: error.javaClass.simpleName)
                    }
                }
                signerReports.put(signerReport)
            }
        }
        handleSigners(v2Value, v3 = false, scheme = "v2")
        handleSigners(v3Value, v3 = true, scheme = "v3")
        result.put("signers", signerReports)
        val digestMismatch = signerReports.hasDigestMismatch()
        result.put(
            "verdict",
            when {
                anyInvalid -> "signature_invalid"
                digestMismatch -> "digest_mismatch"
                anyVerified -> "verified"
                else -> "parsed_unverified"
            }
        )
        return result
    }

    private fun JSONArray.hasDigestMismatch(): Boolean {
        for (i in 0 until length()) {
            val signer = getJSONObject(i)
            val digests = signer.optJSONArray("digests") ?: continue
            for (j in 0 until digests.length()) {
                if (digests.getJSONObject(j).optBoolean("match", true) == false) return true
            }
        }
        return false
    }

    private fun err(code: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", message))

    // ── apk_cert_info ──

    fun certJson(cert: X509Certificate): JSONObject {
        val md = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val md1 = MessageDigest.getInstance("SHA-1").digest(cert.encoded)
        return JSONObject()
            .put("subject", cert.subjectDN.name)
            .put("issuer", cert.issuerDN.name)
            .put("serial", cert.serialNumber.toString(16))
            .put("notBefore", cert.notBefore.time)
            .put("notAfter", cert.notAfter.time)
            .put("sigAlgName", cert.sigAlgName)
            .put("publicKeyAlgorithm", cert.publicKey.algorithm)
            .put("selfSigned", cert.subjectDN == cert.issuerDN)
            .put("sha256", hexStr(md).uppercase())
            .put("sha1", hexStr(md1).uppercase())
    }

    fun certInfo(apk: ByteArray): JSONObject {
        val certs = mutableListOf<Pair<String, X509Certificate>>()
        val factory = CertificateFactory.getInstance("X.509")
        val layout = parseZipLayout(apk)
        if (layout != null) {
            val block = parseSigningBlock(apk, layout)
            if (block != null) {
                listOf(V2_BLOCK_ID to false, V3_BLOCK_ID to true).forEach { (id, v3) ->
                    val value = block.pairs.firstOrNull { it.first == id }?.second ?: return@forEach
                    signerBlocks(value).forEach { signer ->
                        val parsed = parseSigner(signer, v3) ?: return@forEach
                        parsed.certs.forEach { der ->
                            runCatching {
                                val cert = factory.generateCertificate(der.inputStream()) as X509Certificate
                                certs += "v${if (v3) 3 else 2} signing block" to cert
                            }
                        }
                    }
                }
            }
            layout.entries.filter {
                it.name.startsWith("META-INF/", true) &&
                    (it.name.endsWith(".RSA", true) || it.name.endsWith(".DSA", true) || it.name.endsWith(".EC", true))
            }.take(4).forEach { entry ->
                val data = readEntry(apk, entry, 4 * 1024 * 1024) ?: return@forEach
                runCatching {
                    factory.generateCertificates(data.inputStream()).forEach { cert ->
                        certs += entry.name to (cert as X509Certificate)
                    }
                }
            }
        }
        val items = JSONArray()
        certs.forEach { (source, cert) -> items.put(certJson(cert).put("source", source)) }
        return JSONObject()
            .put("certCount", certs.size)
            .put("certificates", items)
            .put("expired", certs.any { it.second.notAfter.time < System.currentTimeMillis() })
    }

    // ── apk_zip_anomaly ──

    fun zipAnomalies(apk: ByteArray): JSONObject {
        val layout = parseZipLayout(apk)
            ?: return err("APK_INVALID", "not a ZIP: EOCD not found")
        val anomalies = JSONArray()
        fun anomaly(kind: String, detail: String, entry: String? = null) {
            val item = JSONObject().put("kind", kind).put("detail", detail)
            if (entry != null) item.put("entry", entry)
            anomalies.put(item)
        }
        val seen = HashMap<String, Int>()
        layout.entries.forEach { e ->
            seen.merge(e.name, 1, Int::plus)
            if (e.name.startsWith("/") || e.name.contains("../") || e.name.contains("\\")) {
                anomaly("path_traversal", "entry name escapes the archive root", e.name)
            }
            if (e.name.length > 1 && e.name[1] == ':' && e.name[0].isLetter()) {
                anomaly("windows_drive", "drive-absolute entry name", e.name)
            }
            if (e.localOffset >= layout.cdOffset) anomaly("bad_local_offset", "local header points into central directory", e.name)
            if (e.flags and 0x1 != 0) anomaly("encrypted_entry", "entry is flagged encrypted", e.name)
            if (e.method != 0 && e.method != 8) anomaly("unknown_method", "compression method ${e.method}", e.name)
            if (e.name.endsWith(".so", true) && e.method == 0 && e.localOffset % 4096L != 0L) {
                anomaly("unaligned_so", "stored .so is not 4096-aligned (zipalign)", e.name)
            }
            if (e.method == 0 && e.csize != e.usize) anomaly("stored_size_mismatch", "stored entry csize != usize", e.name)
            if (e.extraLen > 1024) anomaly("large_extra", "extra field ${e.extraLen} bytes", e.name)
        }
        seen.filterValues { it > 1 }.forEach { (name, count) ->
            anomaly("duplicate_entry", "name appears $count times", name)
        }
        val cdActualEnd = layout.eocdOffset
        if (layout.cdOffset + layout.cdSize != cdActualEnd) {
            anomaly("cd_size_mismatch", "EOCD cdSize+cdOffset != EOCD offset (${layout.cdOffset}+${layout.cdSize} != $cdActualEnd)")
        }
        return JSONObject()
            .put("entryCount", layout.entries.size)
            .put("anomalyCount", anomalies.length())
            .put("anomalies", anomalies)
            .put("verdict", if (anomalies.length() == 0) "clean" else "findings")
    }

    // ── apk_abi_coverage ──

    private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    // 【魔改】128 MiB → 2 GiB
    // 【修复】旧签名 `maxParsedBytes: Int = 2 * 1024 * 1024 * 1024` 在 Int 下溢出成
    // -2147483648，导致 `parsedTotal + e.usize <= maxParsedBytes` 恒为 false，
    // 一个 SO 都不会被 ELF 解析 —— `parsedBytes` 恒 0、`files[]` 里没有 bits /
    // architecture / soname。改成 Long 后预算才是真的 2 GiB。
    fun abiCoverage(apk: ByteArray, maxParsedBytes: Long = 2L * 1024 * 1024 * 1024): JSONObject {
        val layout = parseZipLayout(apk)
            ?: return err("APK_INVALID", "not a ZIP: EOCD not found")
        val libs = layout.entries.filter { Regex("^lib/[^/]+/[^/]+\\.so$").matches(it.name) && !it.name.endsWith("/") }
        val byAbi = LinkedHashMap<String, MutableList<CdEntry>>()
        libs.forEach { e ->
            val abi = e.name.split('/')[1]
            byAbi.getOrPut(abi) { mutableListOf() } += e
        }
        var parsedTotal = 0L
        val items = JSONArray()
        byAbi.forEach { (abi, entries) ->
            val files = JSONArray()
            entries.forEach { e ->
                val file = JSONObject().put("name", e.name.substringAfterLast('/')).put("size", e.usize)
                if (parsedTotal + e.usize <= maxParsedBytes) {
                    val remaining = (maxParsedBytes - parsedTotal).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val data = readEntry(apk, e, remaining)
                    // 【修复】旧判断只看首字节 0x7F，会把任意以 0x7F 开头的压缩流当 ELF；
                    // 现在核对完整 ELF magic。
                    if (data != null && data.size >= 16 && isElfMagic(data)) {
                        parsedTotal += data.size
                        val elf = runCatching { ElfParser(data).parse() }.getOrNull()
                        if (elf != null) {
                            file.put("bits", elf.bits).put("architecture", elf.architecture)
                            val soname = runCatching { ElfAudit.soname(data, elf) }.getOrNull()
                            if (soname != null) file.put("soname", soname)
                        } else {
                            file.put("elfParse", "failed")
                        }
                    } else if (data != null) {
                        file.put("elfParse", "not_elf")
                    }
                }
                files.put(file)
            }
            items.put(JSONObject().put("abi", abi).put("libCount", entries.size).put("files", files))
        }
        val abis = byAbi.keys.toList()
        val findings = JSONArray()
        val has32 = abis.any { it == "armeabi-v7a" || it == "x86" }
        val has64 = abis.any { it == "arm64-v8a" || it == "x86_64" }
        if (has32 && !has64) findings.put("32-bit only: no arm64-v8a/x86_64 libraries")
        if (has64 && !has32) findings.put("64-bit only: legacy 32-bit devices unsupported")
        KNOWN_ABIS.filter { it !in abis }.forEach { findings.put("missing ABI: $it") }
        // 【修复】SONAME 冲突检测必须按 ABI 分组：同一个库在 arm64-v8a / armeabi-v7a /
        // x86 / x86_64 下大小天然不同，旧实现把所有 ABI 的 size 混进一个集合，
        // 于是任何一个多 ABI 的库都会报「soname X maps to multiple sizes」。
        // 真正的冲突是「同一 ABI 内同名 SONAME 却有不同大小」。
        val sonames = HashMap<String, MutableSet<Long>>()
        for (i in 0 until items.length()) {
            val abi = items.getJSONObject(i).optString("abi", "")
            val files = items.getJSONObject(i).getJSONArray("files")
            for (j in 0 until files.length()) {
                val f = files.getJSONObject(j)
                val soname = f.optString("soname", "")
                if (soname.isNotBlank()) {
                    sonames.getOrPut("$abi/$soname") { mutableSetOf() } += f.getLong("size")
                }
            }
        }
        sonames.filterValues { it.size > 1 }.forEach { (key, sizes) ->
            findings.put("soname $key maps to multiple sizes: $sizes")
        }
        return JSONObject()
            .put("abis", JSONArray(abis))
            .put("abiCount", abis.size)
            .put("libraries", items)
            .put("parsedBytes", parsedTotal)
            .put("findings", findings)
            .put(
                "method",
                "Groups lib/<abi>/*.so from the central directory; each SO is ELF-parsed for bits/architecture/SONAME within a byte budget."
            )
    }
}
