// SPDX-License-Identifier: AGPL-3.0-or-later
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
package com.soreverse.mcp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.SettingsStore

private fun settingsTitle(t: UiText, dest: SettingsDest): String = when (dest) {
    SettingsDest.Root -> t.settings
    SettingsDest.ServiceConfig -> if (t.zh) "服务配置" else "Service Configuration"
    SettingsDest.KeepAlive -> t.keepAlive
    SettingsDest.Access -> if (t.zh) "访问控制" else "MCP Access"
    SettingsDest.Language -> t.language
    SettingsDest.Logs -> t.logs
    SettingsDest.Limits -> if (t.zh) "返回数量" else "Result Limits"
    SettingsDest.Export -> if (t.zh) "导出" else "Export"
    SettingsDest.Audit -> if (t.zh) "编辑校验与审计" else "Edit & Audit"
    SettingsDest.Blutter -> "Blutter"
    SettingsDest.Probe -> t.externalProbe
    SettingsDest.ToolStats -> if (t.zh) "工具调用审计" else "Tool Call Audit"
    SettingsDest.Disclaimer -> t.disclaimer
    SettingsDest.BackupRestore -> t.backupRestore
}

/** M3 列表分组容器 */
@Composable
private fun SettingsListGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Glass.card)
            .border(1.dp, Glass.stroke, RoundedCornerShape(20.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        content()
    }
}

/** M3 列表行（图标 + 标题 + 副标题 + 箭头） */
@Composable
private fun SettingsListRow(title: String, subtitle: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SettingsTile(title: String, subtitle: String, icon: ImageVector, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .clip(shape)
            .background(Glass.card)
            .border(1.dp, Glass.stroke, shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Text(
            title,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 4.dp)
    )
}

@Composable
private fun TileGrid(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        content()
    }
}


@Composable
internal fun SettingsHub(
    modifier: Modifier = Modifier,
    backProgress: Float,
    t: UiText,
    settings: SettingsStore,
    language: String,
    onLanguage: (String) -> Unit,
    themeMode: String,
    onTheme: (String) -> Unit,
    accentColor: String,
    onAccent: (String) -> Unit,
    pureBlackDark: Boolean,
    onPureBlack: (Boolean) -> Unit,
    uiDensity: String,
    onDensity: (String) -> Unit,
    cornerStyle: String,
    onCorner: (String) -> Unit,
    motionMode: String,
    onMotion: (String) -> Unit,
    showAdvancedHome: Boolean,
    onShowAdvancedHome: (Boolean) -> Unit,
    highContrast: Boolean,
    onHighContrast: (Boolean) -> Unit,
    textScale: String,
    onTextScale: (String) -> Unit,
    predictiveBack: Boolean,
    onPredictiveBack: (Boolean) -> Unit,
    dest: SettingsDest,
    onDest: (SettingsDest) -> Unit,
    onBack: () -> Unit
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = t.settings,
                subtitle = if (t.zh) "常用在前，极客选项更深一层" else "Common first, power options deeper"
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LocalUiMetrics.current.pagePad)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 分组标题
                // 分组标题（M3 SectionHeader 风格）
                SectionTitle(if (t.zh) "常用" else "Essentials")
                SettingsListGroup {
                    SettingsListRow(
                        if (t.zh) "服务配置" else "Service",
                        if (t.zh) "端口 / 地址 / 工具" else "Port / URLs / tools",
                        Icons.Default.Settings,
                        MaterialTheme.colorScheme.primary,
                        { onDest(SettingsDest.ServiceConfig) }
                    )
                    SettingsListRow(
                        if (t.zh) "保活" else "Keep-alive",
                        if (t.zh) "唤醒锁 / 自启" else "Wake lock / boot",
                        Icons.Default.PowerSettingsNew,
                        AppPalette.green,
                        { onDest(SettingsDest.KeepAlive) }
                    )
                    SettingsListRow(
                        if (t.zh) "访问" else "Access",
                        if (t.zh) "免 key / 认证" else "No key / auth",
                        Icons.Default.Lock,
                        AppPalette.orange,
                        { onDest(SettingsDest.Access) }
                    )
                    SettingsListRow(
                        if (t.zh) "语言" else "Language",
                        if (t.zh) "界面语言" else "Interface language",
                        Icons.Default.Info,
                        AppPalette.indigo,
                        { onDest(SettingsDest.Language) }
                    )
                }

                SectionTitle(if (t.zh) "工具" else "Tools")
                SettingsListGroup {
                    SettingsListRow(
                        if (t.zh) "审计" else "Audit",
                        if (t.zh) "编辑校验" else "Edit checks",
                        Icons.Default.EditNote,
                        AppPalette.purple,
                        { onDest(SettingsDest.Audit) }
                    )
                    SettingsListRow(
                        "Blutter",
                        if (t.zh) "Flutter 分析" else "Flutter analysis",
                        Icons.Default.Memory,
                        AppPalette.indigo,
                        { onDest(SettingsDest.Blutter) }
                    )
                    SettingsListRow(
                        if (t.zh) "探测" else "Probe",
                        if (t.zh) "环境探测" else "Environment probe",
                        Icons.Default.BugReport,
                        AppPalette.green,
                        { onDest(SettingsDest.Probe) }
                    )
                    SettingsListRow(
                        if (t.zh) "调用审计" else "Tool stats",
                        if (t.zh) "工具调用统计" else "Tool call stats",
                        Icons.Default.Analytics,
                        AppPalette.teal,
                        { onDest(SettingsDest.ToolStats) }
                    )
                }

                SectionTitle(if (t.zh) "其他" else "Other")
                SettingsListGroup {
                    SettingsListRow(
                        if (t.zh) "日志" else "Logs",
                        if (t.zh) "运行日志" else "Runtime logs",
                        Icons.Default.Info,
                        AppPalette.teal,
                        { onDest(SettingsDest.Logs) }
                    )
                    SettingsListRow(
                        if (t.zh) "导出" else "Export",
                        if (t.zh) "导出产物" else "Export artifacts",
                        Icons.Default.FileDownload,
                        AppPalette.orange,
                        { onDest(SettingsDest.Export) }
                    )
                    SettingsListRow(
                        if (t.zh) "备份恢复" else "Backup",
                        if (t.zh) "备份 / 还原" else "Backup / restore",
                        Icons.Default.CloudUpload,
                        AppPalette.indigo,
                        { onDest(SettingsDest.BackupRestore) }
                    )
                }
                // License footer
                Text(
                    "${com.soreverse.mcp.core.Provenance.PROJECT} · ${com.soreverse.mcp.core.Provenance.LICENSE}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(
                        top = 4.dp,
                        bottom = 8.dp
                    ).align(Alignment.CenterHorizontally)
                )
            }
        }
        if (dest != SettingsDest.Root) {
            Surface(
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationX = size.width * backProgress
                    alpha = 1f - 0.12f * backProgress
                },
                color = MaterialTheme.colorScheme.background
            ) {
                Column(Modifier.fillMaxSize()) {
                    ScreenHeader(
                        title = settingsTitle(t, dest),
                        showBack = true,
                        onBack = onBack
                    )
                    Box(Modifier.fillMaxSize()) {
                        when (dest) {
                            SettingsDest.ServiceConfig -> SettingsServiceConfigPage(t, settings)

                            SettingsDest.KeepAlive -> SettingsKeepAlivePage(t, settings)

                            SettingsDest.Access -> SettingsAccessPage(t, settings)

                            SettingsDest.Language -> SettingsLanguagePage(t, language, onLanguage)

                            SettingsDest.Logs -> LogsTab(t = t, settings = settings)

                            SettingsDest.Limits -> SettingsLimitsPage(t, settings)

                            SettingsDest.Export -> SettingsExportPage(t, settings)

                            SettingsDest.Audit -> SettingsAuditPage(t, settings)

                            SettingsDest.Blutter -> SettingsBlutterPage(t)





                            SettingsDest.Probe -> SettingsProbePage(t, settings)

                            SettingsDest.BackupRestore -> SettingsBackupRestorePage(t, settings)

                            SettingsDest.ToolStats -> PageScroll {
                                GlassGroup {
                                    Column(Modifier.padding(12.dp)) {
                                        ToolStatsSection(t, settings)
                                    }
                                }
                            }

                            SettingsDest.Disclaimer -> PageScroll {
                                GlassGroup {
                                    Text(
                                        t.disclaimerBody,
                                        modifier = Modifier.padding(14.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                GlassGroup(title = if (t.zh) "开源许可" else "License") {
                                    Text(
                                        if (t.zh) {
                                            "${com.soreverse.mcp.core.Provenance.PROJECT} 是 AGPL-3.0-only 自由软件。任何再分发（含修改、改名、二次打包版本）必须保留本版权与许可声明、继续以 AGPL-3.0-only 授权，并向每一位接收者提供完整对应源代码。"
                                        } else {
                                            "${com.soreverse.mcp.core.Provenance.PROJECT} is AGPL-3.0-only free software. Any redistribution must retain this notice, remain under AGPL-3.0-only, and provide complete corresponding source code."
                                        },
                                        modifier = Modifier.padding(14.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // 【魔改】关于/致谢页已移除
                            SettingsDest.Root -> Unit
                            else -> { /* 已移除的页面 */ }
                        }
                    }
                }
            }
        }
    }
}
