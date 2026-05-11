package com.example.displayapp.presentation.state

import androidx.compose.runtime.Immutable
import com.example.displayapp.data.diagnostics.DiagnosticsLogEntry
import com.example.displayapp.data.diagnostics.DiagnosticsSnapshot
import com.example.displayapp.data.permissions.PermissionItem
import com.example.displayapp.data.persistence.StorageInfo
import com.example.displayapp.data.preferences.SavedDevice
import com.example.displayapp.domain.model.AppSettings
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.domain.model.ThemeSettings

/**
 * Full UI state for the Settings screen.
 *
 * Aggregates everything the screen renders into a single @Immutable snapshot —
 * one Flow → one collect → one recomposition root. Subcards read sub-fields
 * via `MaterialTheme.colorScheme`-style direct property reads, so a change in,
 * say, [diagnostics] doesn't recompose the units section.
 */
@Immutable
data class SettingsUiState(
    val theme: ThemeSettings = ThemeSettings(),
    val app: AppSettings = AppSettings(),
    val savedDevice: SavedDevice? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val permissions: List<PermissionItem> = emptyList(),
    val diagnostics: DiagnosticsSnapshot = DiagnosticsSnapshot(),
    val diagnosticsLogs: List<DiagnosticsLogEntry> = emptyList(),
    val storage: StorageInfo? = null,
    val appVersion: String = "",
    val appBuildNumber: String = ""
)
