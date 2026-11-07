package com.soreverse.mcp.engine

import com.soreverse.mcp.core.HexCodec

internal object LocatorParser {
    fun target(locator: String, prefix: String = ""): String {
        val trimmed = locator.trim()
        val withoutPrefix = if (prefix.isNotBlank() &&
            trimmed.startsWith("$prefix:")
        ) {
            trimmed.substringAfter(':')
        } else {
            trimmed
        }
        // 【修复 B9】显式表达契约：locator 不含 '@' 时旧写法依赖
        // `substringBeforeLast('@')` 返回原串这一 Kotlin 隐式语义；将来若出现
        // 新的含 '@' 的 locator 形态，会在无人察觉的情况下被截断。这里把两种
        // 情形都写死，行为与旧版完全一致，但不再依赖隐式规则。
        val withoutEntry = withoutPrefix.substringAfterLast('!')
        val at = withoutEntry.lastIndexOf('@')
        val withoutAddress = if (at >= 0) withoutEntry.substring(0, at) else withoutEntry
        return withoutAddress.trim()
    }

    fun address(locator: String): Long? {
        val trimmed = locator.trim()
        val at = trimmed.substringAfterLast('@', "")
        return at.takeIf { it.isNotBlank() && it != trimmed }?.let(::hex)
    }

    fun hex(value: String): Long? = HexCodec.long(value)

    fun normalizeSymbol(raw: String): String {
        val base = raw.substringBefore('@').trim()
        return base
            .removePrefix("so_function:")
            .removePrefix("so_symbol:")
            .substringAfterLast('!')
            .removePrefix("sym.imp.")
            .removePrefix("sym.")
            .removePrefix("fcn.")
            .ifBlank { raw.substringBefore('@').trim() }
    }
}
