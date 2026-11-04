/*
SPDX-License-Identifier: AGPL-3.0-or-later

Copyright (C) 2026 bilieebiliee1-design

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program. If not, see <https://www.gnu.org/licenses/>.
*/
package com.soreverse.mcp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.SettingsStore
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

typealias AndroidSettings = android.provider.Settings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        }
        setContent { SoReverseApp() }
    }
}

// 【魔改】IntegrityGate 已移除（不做完整性校验）

private fun localizedResources(context: Context, locale: java.util.Locale): android.content.res.Resources {
    val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
    return context.createConfigurationContext(config).resources
}

private fun textFor(mode: String, context: Context): UiText {
    val forcedZh = mode == "zh"
    val forcedEn = mode == "en"
    val systemZh = Locale.getDefault().language == "zh"
    val zh = forcedZh || (!forcedEn && mode == "system" && systemZh)
    // When the user forces a language, override the resource configuration so
    // values-zh/ vs values/ resolves deterministically regardless of device locale.
    val res = when {
        forcedZh -> localizedResources(context, java.util.Locale.CHINESE)

        forcedEn -> localizedResources(context, java.util.Locale.ENGLISH)

        // system mode respects the device locale, including zh systems.
        systemZh -> localizedResources(context, java.util.Locale.CHINESE)

        else -> context.resources
    }
    fun s(id: Int): String = res.getString(id)
    return UiText(
        zh = zh,
        appTitle = s(R.string.app_title),
        subtitle = s(R.string.app_subtitle),
        state = s(R.string.state),
        running = s(R.string.running),
        stopped = s(R.string.stopped),
        power = s(R.string.power),
        directory = s(R.string.directory),
        chooseDirectory = s(R.string.choose_directory),
        noDirectory = s(R.string.no_directory),
        port = s(R.string.port),
        links = s(R.string.links),
        logs = s(R.string.logs),
        tools = s(R.string.toolset),
        settings = s(R.string.settings),
        language = s(R.string.language),
        theme = s(R.string.theme),
        keepAlive = s(R.string.keep_alive),
        floating = s(R.string.floating),
        wakeLock = s(R.string.wake_lock),
        predictiveBack = s(R.string.predictive_back),
        battery = s(R.string.battery),
        permissions = s(R.string.permissions),
        test = s(R.string.test),
        copied = s(R.string.copied),
        externalProbe = s(R.string.external_probe),
        probeServiceUrl = s(R.string.probe_service_url),
        reachabilityHelp = s(R.string.reachability_help),
        instructions = s(R.string.instructions),
        instructionsBody = s(R.string.instructions_body),
        disclaimer = s(R.string.disclaimer),
        disclaimerBody = s(R.string.disclaimer_body),
        about = s(R.string.about),
        aboutBody = s(R.string.about_body),
        joinQqGroup = s(R.string.join_qq_group),
        accept = s(R.string.accept),
        decline = s(R.string.decline),
        firstRunDisclaimerTitle = s(R.string.first_run_disclaimer_title),
        privacyConsentTitle = s(R.string.privacy_consent_title),
        privacyConsentBody = s(R.string.privacy_consent_body),
        privacyConsentAllow = s(R.string.privacy_consent_allow),
        privacyConsentDeny = s(R.string.privacy_consent_deny),
        externalProbeExample = s(R.string.external_probe_example),
        backupRestore = s(R.string.backup_restore),
        backupRestoreSubtitle = s(R.string.backup_restore_subtitle),
        backupLocal = s(R.string.backup_local),
        backupExport = s(R.string.backup_export),
        backupImport = s(R.string.backup_import),
        backupIncludeSecrets = s(R.string.backup_include_secrets),
        backupExportSuccess = s(R.string.backup_export_success),
        backupImportSuccess = s(R.string.backup_import_success),
        backupImportError = s(R.string.backup_import_error),
        backupSecretsMasked = s(R.string.backup_secrets_masked),
        backupEncryptToggle = s(R.string.backup_encrypt_toggle),
        backupEncryptPassword = s(R.string.backup_encrypt_password),
        backupEncryptConfirm = s(R.string.backup_encrypt_confirm),
        backupEncryptPasswordHint = s(R.string.backup_encrypt_password_hint),
        backupDecryptPassword = s(R.string.backup_decrypt_password),
        backupDecryptPasswordHint = s(R.string.backup_decrypt_password_hint),
        backupPasswordMismatch = s(R.string.backup_password_mismatch),
        backupPasswordRequired = s(R.string.backup_password_required),
        backupDecryptFailed = s(R.string.backup_decrypt_failed),
        backupEncryptWarning = s(R.string.backup_encrypt_warning),
        backupEncryptWarningTitle = s(R.string.backup_encrypt_warning_title),
        backupEncrypt = s(R.string.backup_encrypt),
        backupPasswordPrompt = s(R.string.backup_password_prompt),
        backupPasswordConfirm = s(R.string.backup_password_confirm),
        backupDecrypting = s(R.string.backup_decrypting),
        backupIncludeSecretsSubtitle = s(R.string.backup_include_secrets_subtitle),
        backupEncryptToggleSubtitle = s(R.string.backup_encrypt_toggle_subtitle),
        backupPasswordPlaceholder = s(R.string.backup_password_placeholder),
        backupHistory = s(R.string.backup_history),
        backupHistoryEmpty = s(R.string.backup_history_empty),
        backupRestoreAction = s(R.string.backup_restore_action),
        backupSecretsRequireEncryption = s(R.string.backup_secrets_require_encryption),
        confirm = s(R.string.confirm),
        cancel = s(R.string.cancel)
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalAnimationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
private fun SoReverseApp() {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val appScope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(MainTab.Service) }
    var settingsDest by remember { mutableStateOf(SettingsDest.Root) }
    var language by remember { mutableStateOf(settings.language) }
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var accentColor by remember { mutableStateOf(settings.accentColor) }
    var pureBlackDark by remember { mutableStateOf(settings.pureBlackDark) }
    var uiDensity by remember { mutableStateOf(settings.uiDensity) }
    var cornerStyle by remember { mutableStateOf(settings.cornerStyle) }
    var motionMode by remember { mutableStateOf(settings.motionMode) }
    var showAdvancedHome by remember { mutableStateOf(settings.showAdvancedHome) }
    var highContrast by remember { mutableStateOf(settings.highContrast) }
    var textScale by remember { mutableStateOf(settings.textScale) }
    var predictiveBack by remember { mutableStateOf(settings.predictiveBackEnabled) }
    var disclaimerAccepted by remember { mutableStateOf(settings.disclaimerAccepted) }
    var backProgress by remember { mutableStateOf(0f) }
    val t = textFor(language, context)
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val accent = AppPalette.accent(accentColor, dark)
    val colors = if (dark) {
        appDarkColors(
            accent,
            pureBlackDark,
            highContrast
        )
    } else {
        appLightColors(accent, highContrast)
    }
    val metrics = remember(uiDensity, cornerStyle) { uiMetrics(uiDensity, cornerStyle) }
    val typography = remember(textScale) { scaledTypography(textScaleFactor(textScale)) }
    val reduceMotion = when (motionMode) {
        "reduced" -> true
        "full" -> false
        else -> false
    }
    // 【魔改】已移除版本更新检查
    val inInternalDetail = tab == MainTab.Settings && settingsDest != SettingsDest.Root

    fun closeInternalDetail() {
        if (inInternalDetail) settingsDest = SettingsDest.Root
    }

    if (Build.VERSION.SDK_INT >= 34) {
        PredictiveBackHandler(enabled = inInternalDetail && predictiveBack) { progress ->
            try {
                progress.collect { event -> backProgress = event.progress }
                closeInternalDetail()
                backProgress = 0f
            } catch (_: CancellationException) {
                backProgress = 0f
            }
        }
    }

    LaunchedEffect(tab, settingsDest, predictiveBack) {
        if (!inInternalDetail || !predictiveBack) backProgress = 0f
    }

    DisposableEffect(predictiveBack, tab, settingsDest) {
        val callback = object : OnBackPressedCallback(
            inInternalDetail && (!predictiveBack || Build.VERSION.SDK_INT < 34)
        ) {
            override fun handleOnBackPressed() {
                closeInternalDetail()
            }
        }
        (context as ComponentActivity).onBackPressedDispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalUiMetrics provides metrics,
        LocalReduceMotion provides reduceMotion
    ) {
        MaterialTheme(colorScheme = colors, typography = typography) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Soft ambient wash — depth without fake glass blobs.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(
                                        alpha = if (dark) 0.10f else 0.06f
                                    ),
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.background
                                )
                            )
                        )
                )
                Scaffold(
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (!inInternalDetail) {
                            FloatingDock(
                                tab = tab,
                                t = t,
                                onTab = {
                                    if (it != tab) {
                                        tab = it
                                        if (it != MainTab.Settings) settingsDest = SettingsDest.Root
                                    }
                                }
                            )
                        }
                    }
                ) { padding ->
                    Box(
                        Modifier
                            .padding(padding)
                            .fillMaxSize()
                    ) {
                        val animMs = if (reduceMotion) 0 else 180
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = {
                                (
                                    fadeIn(tween(animMs)) +
                                        scaleIn(
                                            initialScale = 0.985f,
                                            animationSpec = tween(animMs)
                                        )
                                    ) togetherWith
                                    (
                                        fadeOut(tween((animMs * 0.75f).toInt().coerceAtLeast(0))) +
                                            scaleOut(
                                                targetScale = 0.99f,
                                                animationSpec = tween(
                                                    (
                                                        animMs *
                                                            0.75f
                                                        ).toInt().coerceAtLeast(0)
                                                )
                                            )
                                        )
                            },
                            label = "main-nav"
                        ) { currentTab ->
                            when (currentTab) {
                                MainTab.Service -> ServiceTab(
                                    t = t,
                                    settings = settings,
                                    onOpenKeepAlive = {
                                        tab = MainTab.Settings
                                        settingsDest = SettingsDest.KeepAlive
                                    },
                                )

                                MainTab.Logs -> LogsTab(t = t, settings = settings)

                                MainTab.Settings -> SettingsHub(
                                    modifier = Modifier,
                                    backProgress = backProgress,
                                    t = t,
                                    settings = settings,
                                    language = language,
                                    onLanguage = {
                                        language = it
                                        settings.language = it
                                    },
                                    themeMode = themeMode,
                                    onTheme = {
                                        themeMode = it
                                        settings.themeMode = it
                                    },
                                    accentColor = accentColor,
                                    onAccent = {
                                        accentColor = it
                                        settings.accentColor = it
                                    },
                                    pureBlackDark = pureBlackDark,
                                    onPureBlack = {
                                        pureBlackDark = it
                                        settings.pureBlackDark = it
                                    },
                                    uiDensity = uiDensity,
                                    onDensity = {
                                        uiDensity = it
                                        settings.uiDensity = it
                                    },
                                    cornerStyle = cornerStyle,
                                    onCorner = {
                                        cornerStyle = it
                                        settings.cornerStyle = it
                                    },
                                    motionMode = motionMode,
                                    onMotion = {
                                        motionMode = it
                                        settings.motionMode = it
                                    },
                                    showAdvancedHome = showAdvancedHome,
                                    onShowAdvancedHome = {
                                        showAdvancedHome = it
                                        settings.showAdvancedHome =
                                            it
                                    },
                                    highContrast = highContrast,
                                    onHighContrast = {
                                        highContrast = it
                                        settings.highContrast = it
                                    },
                                    textScale = textScale,
                                    onTextScale = {
                                        textScale = it
                                        settings.textScale = it
                                    },
                                    predictiveBack = predictiveBack,
                                    onPredictiveBack = {
                                        predictiveBack = it
                                        settings.predictiveBackEnabled =
                                            it
                                    },
                                    dest = settingsDest,
                                    onDest = { settingsDest = it },
                                    onBack = { settingsDest = SettingsDest.Root }
                                )
                            }
                        }
                    }
                }
            }
            if (!disclaimerAccepted) {
                AlertDialog(
                    onDismissRequest = {},
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    title = { Text(t.firstRunDisclaimerTitle, fontWeight = FontWeight.SemiBold) },
                    text = { Text(t.disclaimerBody) },
                    confirmButton = {
                        Button(
                            onClick = {
                                settings.disclaimerAccepted = true
                                disclaimerAccepted = true
                            },
                            shape = RoundedCornerShape(LocalUiMetrics.current.controlRadius),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White
                            )
                        ) { Text(t.accept) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            (context as? Activity)?.finish()
                        }) { Text(t.decline) }
                    }
                )
            }
            // 【魔改】已移除版本更新提示弹窗
        }
    }
}

@Composable
private fun FloatingDock(tab: MainTab, t: UiText, onTab: (MainTab) -> Unit) {
    val items = listOf(
        Triple(MainTab.Service, Icons.Default.Home, if (t.zh) "控制" else "Console"),
        Triple(MainTab.Logs, Icons.Default.Terminal, t.logs),
        Triple(MainTab.Settings, Icons.Default.Settings, t.settings)
    )
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (value, icon, label) ->
                CapsuleTab(
                    icon = icon,
                    label = label,
                    selected = tab == value,
                    onClick = { onTab(value) }
                )
            }
        }
    }
}