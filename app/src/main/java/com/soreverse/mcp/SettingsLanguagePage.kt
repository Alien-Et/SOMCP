package com.soreverse.mcp

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** 界面语言选择（从外观页迁移，外观页已废除） */
@Composable
internal fun SettingsLanguagePage(t: UiText, language: String, onLanguage: (String) -> Unit) {
    PageScroll {
        GlassGroup(title = t.language) {
            ChipRow(
                listOf(
                    "system" to if (t.zh) "跟随系统" else "System",
                    "zh" to "中文",
                    "en" to "English"
                ),
                language,
                onLanguage
            )
        }
        GlassGroup(
            title = if (t.zh) "说明" else "Note",
        ) {
            Text(
                if (t.zh) {
                    "语言切换立即生效，无需重启服务。"
                } else {
                    "Language switches apply immediately, no restart needed."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
