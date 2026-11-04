package com.soreverse.mcp

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.service.McpForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun ServiceTab(t: UiText, settings: SettingsStore, onOpenKeepAlive: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var port by remember { mutableStateOf(settings.port.toString()) }
    var running by remember { mutableStateOf(McpForegroundService.isRunning()) }
    var portStatus by remember { mutableStateOf(portStatusText(settings.port, running, t.zh)) }
    var endpoints by remember {
        mutableStateOf(filteredEndpoints(context, settings, settings.port))
    }
    var setupPrompt by remember { mutableStateOf<SetupTarget?>(null) }
    var showStartDiagnosis by remember { mutableStateOf(false) }
    var quickPublicUrl by remember { mutableStateOf<String?>(null) }
    var apkConnected by remember { mutableStateOf<Boolean?>(null) }
    var apkToolNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var showToolCatalog by remember { mutableStateOf(false) }
    var keepAliveReady by remember { mutableStateOf(isKeepAliveReady(context, settings)) }
    LaunchedEffect(Unit) {
        // 【魔改】APK MCP 桥接已移除
        apkConnected = false
        while (true) {
            running = McpForegroundService.isRunning()
            val typedPort = port.toIntOrNull() ?: settings.port
            portStatus = portStatusText(typedPort, running && typedPort == settings.port, t.zh)
            val currentEndpoints = filteredEndpoints(context, settings, typedPort)
            if (currentEndpoints != endpoints) endpoints = currentEndpoints
            // 【魔改】隧道与桥接状态已移除
            quickPublicUrl = null
            apkToolNames = emptyList()
            apkConnected = false
            keepAliveReady = isKeepAliveReady(context, settings)
            delay(1_000)
        }
    }
    LaunchedEffect(t.zh, running, port) {
        val typedPort = port.toIntOrNull() ?: settings.port
        portStatus = portStatusText(typedPort, running && typedPort == settings.port, t.zh)
    }

    fun applyPort() {
        val nextPort = port.toIntOrNull()?.coerceIn(1024, 65535) ?: 8000
        port = nextPort.toString()
        val conflict = !isPortAvailable(nextPort, running && nextPort == settings.port)
        portStatus = portStatusText(nextPort, running, t.zh, conflict)
        if (conflict) return
        settings.port = nextPort
        endpoints = filteredEndpoints(context, settings, nextPort)
        if (running) {
            McpForegroundService.start(context)
        }
    }
    fun startServerUnchecked() {
        val nextPort = port.toIntOrNull()?.coerceIn(1024, 65535) ?: 8000
        port = nextPort.toString()
        if (!isPortAvailable(nextPort, false)) {
            portStatus = portStatusText(nextPort, false, t.zh, conflict = true)
            Toast.makeText(
                context,
                if (t.zh) "端口被占用" else "Port is in use",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        settings.port = nextPort
        runCatching { McpForegroundService.start(context) }
            .onSuccess {
                running = true
                portStatus = if (t.zh) "正在启动服务…" else "Starting service…"
                endpoints = filteredEndpoints(context, settings, settings.port)
            }
            .onFailure { error ->
                AppLog.e("Unable to start MCP service: ${error.message}")
                portStatus = if (t.zh) "服务启动失败" else "Service failed to start"
                Toast.makeText(
                    context,
                    error.message ?: if (t.zh) "服务启动失败" else "Service failed to start",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    val toggleServer = {
        if (running) {
            McpForegroundService.stop(context)
            running = false
        } else if ((settings.apkMcpMergeTools && apkConnected != true) ||
            !keepAliveReady
        ) {
            showStartDiagnosis = true
        } else {
            startServerUnchecked()
        }
    }

    val loopbackUrl =
        endpoints.firstOrNull { it.url.contains("127.0.0.1") }?.url
            ?: "http://127.0.0.1:${settings.port}/mcp"
    val lanUrl = endpoints.firstOrNull {
        !it.url.contains("127.0.0.1") && !it.url.contains("[::1]")
    }?.url.orEmpty()
    fun secured(url: String): String = if (settings.authEnabled &&
        settings.accessToken.isNotBlank()
    ) {
        "$url?token=${settings.accessToken}"
    } else {
        url
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = if (t.zh) "控制台" else "Console",
            subtitle = if (t.zh) "本地 MCP 服务" else "Local MCP service",
            trailing = {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = if (t.zh) "设置" else "Settings",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        )
        val pad = LocalUiMetrics.current.pagePad
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = pad)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionTitle(
                if (t.zh) "服务状态" else "Service status",
                if (t.zh) "本地 MCP · 端口 $${settings.port}" else "Local MCP · port $${settings.port}"
            )
            // 大状态卡：运行状态 + 端口 + 启动/停止按钮
            StatusCard(
                t = t,
                running = running,
                port = settings.port,
                onPower = toggleServer
            )

            SectionTitle(if (t.zh) "保活状态" else "Keep-alive")
            // 保活状态行
            StatusRow(
                label = if (t.zh) "保活" else "Keep-alive",
                value = if (keepAliveReady) (if (t.zh) "已就绪" else "Ready") else (if (t.zh) "未就绪" else "Not ready"),
                ok = keepAliveReady,
                hint = if (t.zh) "后台运行可能被系统中断 · 轻点设置" else "Background may be interrupted · tap to configure",
                onClick = { setupPrompt = SetupTarget.KeepAlive }
            )

            SectionTitle(if (t.zh) "连接地址" else "Connect")
            // 连接地址卡
            EndpointBoard(
                t = t,
                loopback = secured(loopbackUrl),
                lan = lanUrl,
                onCopyLoopback = { copy(context, secured(loopbackUrl), t.copied) },
                onCopyLan = { copy(context, secured(lanUrl), t.copied) },
            )
        }
    }

    if (showToolCatalog) {
        AlertDialog(
            onDismissRequest = { showToolCatalog = false },
            title = { Text(if (t.zh) "MCP 工具列表" else "MCP Tool Catalog") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    ToolSummary(t, apkToolNames)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showToolCatalog = false
                }) { Text(if (t.zh) "完成" else "Done") }
            }
        )
    }

    setupPrompt?.let { target ->
        AlertDialog(
            onDismissRequest = { setupPrompt = null },
            title = { Text(setupPromptTitle(target, t.zh)) },
            text = {
                Text(
                    setupPromptBody(target, t.zh, keepAliveReady)
                )
            },
            confirmButton = {
                Button(onClick = {
                    setupPrompt = null
                    when (target) {
                        SetupTarget.KeepAlive -> onOpenKeepAlive()
                    }
                }) { Text(if (t.zh) "继续设置" else "Continue setup") }
            },
            dismissButton = {
                TextButton(onClick = {
                    setupPrompt = null
                }) { Text(if (t.zh) "暂不设置" else "Not now") }
            }
        )
    }
    if (showStartDiagnosis) {
        AlertDialog(
            onDismissRequest = { showStartDiagnosis = false },
            title = { Text(if (t.zh) "启动前诊断" else "Pre-start diagnosis") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagnosisLine(
                        if (t.zh) "保活" else "Keep-alive",
                        keepAliveReady,
                        if (t.zh) "后台运行可能被系统中断" else "The system may stop background work"
                    )
                    Text(if (t.zh) "仍要启动吗？" else "Start anyway?", fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                Button(onClick = {
                    showStartDiagnosis = false
                    startServerUnchecked()
                }) { Text(if (t.zh) "仍然启动" else "Start anyway") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showStartDiagnosis = false
                }) { Text(if (t.zh) "返回设置" else "Review settings") }
            }
        )
    }
}

@Composable
private fun StatusCard(t: UiText, running: Boolean, port: Int, onPower: () -> Unit, modifier: Modifier = Modifier) {
    val statusColor = if (running) statusSuccess() else MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(32.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "power-press"
    )
    val reduceMotion = LocalReduceMotion.current
    // 呼吸脉冲：运行中状态点外圈扩散
    val pulse by animateFloatAsState(if (running) 1f else 0f, label = "pulse")
    Box(
        modifier
            .graphicsLayer {
                scaleX = if (reduceMotion) 1f else scale
                scaleY = if (reduceMotion) 1f else scale
            }
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        statusColor.copy(alpha = if (running) 0.24f else 0.16f),
                        statusColor.copy(alpha = if (running) 0.10f else 0.06f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                    )
                )
            )
            .border(
                BorderStroke(1.5.dp, statusColor.copy(alpha = if (running) 0.40f else 0.24f)),
                shape
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onPower)
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 左侧：状态图标（呼吸圈）
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.14f))
                    .border(BorderStroke(1.5.dp, statusColor.copy(alpha = 0.44f)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (running) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.10f + 0.14f * pulse))
                    )
                }
                Icon(
                    if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = t.power,
                    tint = statusColor,
                    modifier = Modifier.size(32.dp)
                )
            }
            // 中间：状态文字
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    if (running) (if (t.zh) "运行中" else "Running") else (if (t.zh) "已停止" else "Stopped"),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    letterSpacing = (-0.8).sp
                )
                Text(
                    if (running) {
                        if (t.zh) "端口 $port · 轻点停止" else "Port $port · tap to stop"
                    } else {
                        if (t.zh) "端口 $port · 轻点启动" else "Port $port · tap to start"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                    fontWeight = FontWeight.Medium
                )
            }
            // 右侧：大号按钮
            Surface(
                shape = CircleShape,
                color = statusColor,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                onClick = onPower
            ) {
                Text(
                    if (running) (if (t.zh) "停止" else "Stop") else (if (t.zh) "启动" else "Start"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean, hint: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (ok) statusSuccess() else MaterialTheme.colorScheme.error
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed) 0.08f else 0f, label = "ready-press")
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
                RoundedCornerShape(20.dp)
            )
            .background(color.copy(alpha = alpha))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun EndpointBoard(
    t: UiText,
    loopback: String,
    lan: String,
    onCopyLoopback: () -> Unit,
    onCopyLan: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModernCardBare(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (t.zh) "连接地址" else "Connect",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            StatusDot(ok = loopback.isNotBlank())
        }
        Spacer(Modifier.height(12.dp))
        EndpointRow(
            label = if (t.zh) "本机回环" else "Loopback",
            value = loopback,
            copyLabel = if (t.zh) "复制" else "Copy",
            onCopy = onCopyLoopback,
        )
        Spacer(Modifier.height(4.dp))
        EndpointRow(
            label = if (t.zh) "局域网" else "LAN",
            value = lan.ifBlank { if (t.zh) "暂无可用地址" else "No LAN address" },
            copyLabel = if (t.zh) "复制" else "Copy",
            onCopy = onCopyLan,
            enabled = lan.isNotBlank()
        )
    }
}

@Composable
private fun EndpointDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
    )
}

@Composable
private fun EndpointRow(label: String, value: String, copyLabel: String, onCopy: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            MonoText(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                maxLines = 1
            )
        }
        if (enabled) {
            CopyChip(copyLabel, onClick = onCopy)
        }
    }
}

@Composable
private fun DiagnosisLine(label: String, ok: Boolean, detail: String) {
    val color = if (ok) statusSuccess() else MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color))
        Column {
            Text(
                "$label · ${if (ok) "OK" else "!"}",
                fontWeight = FontWeight.SemiBold,
                color = color
            )
            if (!ok) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun setupPromptTitle(target: SetupTarget, zh: Boolean): String =
    if (zh) "保活状态" else "Keep-alive status"

private fun setupPromptBody(target: SetupTarget, zh: Boolean, keepAliveReady: Boolean): String = when (target) {
    SetupTarget.KeepAlive -> if (zh) {
        if (keepAliveReady) "保活已就绪。是否查看唤醒锁、电池优化和开机自启设置？" else "保活尚未就绪，服务在后台可能被系统中断。是否继续设置？"
    } else if (keepAliveReady) {
        "Keep-alive is ready. Review its settings?"
    } else {
        "Keep-alive is not ready. Background service may be stopped. Continue setup?"
    }
}

@Composable
private fun QuickLinkCard(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
                shape
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
