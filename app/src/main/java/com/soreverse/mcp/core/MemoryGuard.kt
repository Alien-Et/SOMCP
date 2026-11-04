// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 【魔改】兼容层：内存守卫已废除
//
// 原实现会在「输入 × 3 + 48MB」超过堆余量时拒绝分析。
// 本版本保留同名 API 以兼容 11 个既有调用点，但永不拦截。
// 真正的内存压力由 largeHeap + JVM OutOfMemoryError 兜底。
//
package com.soreverse.mcp.core

/** 保留异常类型以兼容既有 catch 分支（永不抛出）。 */
class InsufficientMemoryException(message: String) : IllegalStateException(message)

object MemoryGuard {

    const val DEFAULT_MULTIPLICITY = 1
    const val DEFAULT_RESERVE_MIB = 0L
    const val MIN_HEADROOM_MIB = 0L

    private const val MIB = 1024L * 1024L

    fun heapHeadroomMiB(): Long {
        val runtime = Runtime.getRuntime()
        val headroom = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        return headroom.coerceAtLeast(0L) / MIB
    }

    /** 【魔改】永不拦截。 */
    fun ensureAnalysisMemory(requiredBytes: Long, what: String) {
        // no-op
    }
}
