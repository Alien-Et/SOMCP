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
package com.soreverse.mcp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

internal enum class MainTab { Service, Logs, Settings }
internal enum class SetupTarget { KeepAlive }
internal enum class SettingsDest {
    Root,
    ServiceConfig,
    Appearance,
    KeepAlive,
    Access,
    Limits,
    Export,
    Audit,
    Blutter,
    Probe,
    ToolStats,
    Disclaimer,
    BackupRestore
}

internal data class UiText(
    val zh: Boolean,
    val appTitle: String,
    val subtitle: String,
    val state: String,
    val running: String,
    val stopped: String,
    val power: String,
    val directory: String,
    val chooseDirectory: String,
    val noDirectory: String,
    val port: String,
    val links: String,
    val logs: String,
    val tools: String,
    val settings: String,
    val language: String,
    val theme: String,
    val keepAlive: String,
    val floating: String,
    val wakeLock: String,
    val predictiveBack: String,
    val battery: String,
    val permissions: String,
    val test: String,
    val copied: String,
    val externalProbe: String,
    val probeServiceUrl: String,
    val reachabilityHelp: String,
    val instructions: String,
    val instructionsBody: String,
    val disclaimer: String,
    val disclaimerBody: String,
    val about: String,
    val aboutBody: String,
    val joinQqGroup: String,
    val accept: String,
    val decline: String,
    val firstRunDisclaimerTitle: String,
    val privacyConsentTitle: String,
    val privacyConsentBody: String,
    val privacyConsentAllow: String,
    val privacyConsentDeny: String,
    val externalProbeExample: String,
    val backupRestore: String,
    val backupRestoreSubtitle: String,
    val backupLocal: String,
    val backupExport: String,
    val backupImport: String,
    val backupIncludeSecrets: String,
    val backupExportSuccess: String,
    val backupImportSuccess: String,
    val backupImportError: String,
    val backupSecretsMasked: String,
    val backupEncryptToggle: String,
    val backupEncryptPassword: String,
    val backupEncryptConfirm: String,
    val backupEncryptPasswordHint: String,
    val backupDecryptPassword: String,
    val backupDecryptPasswordHint: String,
    val backupPasswordMismatch: String,
    val backupPasswordRequired: String,
    val backupDecryptFailed: String,
    val backupEncryptWarning: String,
    val backupEncryptWarningTitle: String,
    val backupEncrypt: String,
    val backupPasswordPrompt: String,
    val backupPasswordConfirm: String,
    val backupDecrypting: String,
    val backupIncludeSecretsSubtitle: String,
    val backupEncryptToggleSubtitle: String,
    val backupPasswordPlaceholder: String,
    val backupHistory: String,
    val backupHistoryEmpty: String,
    val backupRestoreAction: String,
    val backupSecretsRequireEncryption: String,
    val confirm: String,
    val cancel: String
)
