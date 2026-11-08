package com.soreverse.mcp.engine

import org.json.JSONArray
import org.json.JSONObject

internal object PatchByteUtils {
    fun patchJson(patch: PatchRecord): JSONObject = JSONObject()
        .put("timeMillis", patch.timeMillis)
        .put("kind", patch.kind)
        .put("locator", patch.locator)
        .put("fileOffset", hex(patch.fileOffset.toLong()))
        .put("oldHex", patch.oldHex)
        .put("newHex", patch.newHex)
        .put("asm", patch.asm.ifBlank { JSONObject.NULL })

    fun hexBytes(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

    fun architectureNop(architecture: String, thumb: Boolean): ByteArray = when (architecture) {
        "arm64" -> byteArrayOf(0x1f, 0x20, 0x03, 0xd5.toByte())

        "arm32" -> if (thumb) {
            byteArrayOf(
                0x00,
                0xbf.toByte()
            )
        } else {
            byteArrayOf(0x00, 0xf0.toByte(), 0x20, 0xe3.toByte())
        }

        "mips" -> byteArrayOf(0x00, 0x00, 0x00, 0x00)

        else -> byteArrayOf(0x90.toByte())
    }

    /**
     * Instruction-size alignment of one opcode pattern.
     *
     * [repeatBytes] must never emit a partial pattern: a 6-byte arm64 range
     * filled with a 4-byte NOP used to end in `1F 20 03 D5 1F 20`, which is a
     * half instruction that crashes the process the moment it is executed.
     * Callers validate their range against this first and report a structured
     * error instead of writing an illegal encoding.
     */
    fun instructionAlignment(pattern: ByteArray): Int = pattern.size.coerceAtLeast(1)

    fun repeatBytes(pattern: ByteArray, length: Int): ByteArray {
        if (length <= 0 || pattern.isEmpty()) return ByteArray(0)
        val step = pattern.size
        require(length % step == 0) {
            "Refusing to emit a partial $step-byte pattern: length $length is not a multiple of $step"
        }
        val out = ByteArray(length)
        var pos = 0
        while (pos < length) {
            System.arraycopy(pattern, 0, out, pos, step)
            pos += step
        }
        return out
    }

    /**
     * Bounded byte-difference scan.
     *
     * [maxRanges] caps how many ranges are materialised as JSON objects, and the
     * scan stops early once that many are collected. Without the cap a LIEF
     * rebuild that shifts every offset turns a 300 MiB pair into millions of
     * JSONObject allocations plus a gigantic response body. [truncated] and
     * [diffBytes] let callers disclose that the range list is partial.
     */
    class ByteDiffResult(val ranges: JSONArray, val truncated: Boolean, val diffBytes: Long)

    fun diffRanges(a: ByteArray, b: ByteArray, maxRanges: Int = DEFAULT_MAX_DIFF_RANGES): ByteDiffResult {
        val ranges = JSONArray()
        val maxLen = maxOf(a.size, b.size)
        var diffBytes = 0L
        var i = 0
        while (i < maxLen) {
            val same = i < a.size && i < b.size && a[i] == b[i]
            if (same) {
                i++
                continue
            }
            val start = i
            while (i < maxLen && !(i < a.size && i < b.size && a[i] == b[i])) i++
            diffBytes += (i - start).toLong()
            if (ranges.length() >= maxRanges) continue
            val oldBytes = if (start <
                a.size
            ) {
                a.copyOfRange(start, minOf(i, a.size))
            } else {
                ByteArray(0)
            }
            val newBytes = if (start <
                b.size
            ) {
                b.copyOfRange(start, minOf(i, b.size))
            } else {
                ByteArray(0)
            }
            ranges.put(
                JSONObject().put(
                    "fileOffset",
                    hex(start.toLong())
                ).put(
                    "length",
                    i - start
                ).put("oldHex", hexBytes(oldBytes)).put("newHex", hexBytes(newBytes))
            )
        }
        return ByteDiffResult(ranges, diffBytes > 0L && ranges.length() >= maxRanges, diffBytes)
    }

    /** Range-only convenience wrapper; bounded by the same default cap. */
    fun byteDiffRanges(a: ByteArray, b: ByteArray, maxRanges: Int = DEFAULT_MAX_DIFF_RANGES): JSONArray = diffRanges(a, b, maxRanges).ranges

    const val DEFAULT_MAX_DIFF_RANGES = 256

    private fun hex(value: Long) = "0x${value.toString(16)}"
}
