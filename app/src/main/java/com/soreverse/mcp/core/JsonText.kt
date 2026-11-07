// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Copyright (C) 2026 bilieebiliee1-design
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.
//
package com.soreverse.mcp.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 【修复 B4】读取"可能不存在的文本字段"。
 *
 * Android 的 `org.json` 里 `optString(key)` 对 `JSONObject.NULL` 返回的是**字符串
 * `"null"`** 而不是 `null`，所以常见写法
 *
 * ```kotlin
 * obj.optString("reason").ifBlank { null } ?: JSONObject.NULL
 * ```
 *
 * 的兜底分支永远不会触发 —— `"null"` 不是空白串，会被当成真实文本继续传给下游，
 * 最终在报告里出现字面量 "null"。本函数把三种情况统一收敛：
 *
 * - 字段缺失        → null
 * - 值为 JSON null  → null
 * - 值为空字符串    → null
 * - 其余            → 原文本
 */
internal fun JSONObject.optText(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/** 同 [optText]，但把空白串一并视为"没有值"。 */
internal fun JSONObject.optNonBlankText(key: String): String? =
    optText(key)?.takeIf { it.isNotBlank() }

/**
 * 【修复 B4】数组版本。`JSONArray.optString(i)` 对 `JSONObject.NULL` 元素同样返回
 * 字符串 `"null"`，因此 `optString(i).takeIf { it.isNotBlank() }` 会把 `"null"` 当成
 * 有效版本号/标识继续传给下游（例如 Blutter 的 engineRevision / dartVersion）。
 */
internal fun JSONArray.optText(index: Int): String? =
    if (isNull(index)) null else optString(index).takeIf { it.isNotEmpty() }

/** 同 [optText]，但把空白串一并视为"没有值"。 */
internal fun JSONArray.optNonBlankText(index: Int): String? =
    optText(index)?.takeIf { it.isNotBlank() }
