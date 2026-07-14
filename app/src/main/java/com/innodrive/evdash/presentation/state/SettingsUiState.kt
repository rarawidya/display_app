package com.innodrive.evdash.presentation.state

import androidx.compose.runtime.Immutable
import com.innodrive.evdash.data.diagnostics.DiagnosticsLogEntry
import com.innodrive.evdash.data.diagnostics.DiagnosticsSnapshot
import com.innodrive.evdash.data.permissions.PermissionItem
import com.innodrive.evdash.data.persistence.StorageInfo
import com.innodrive.evdash.data.preferences.SavedDevice
import com.innodrive.evdash.domain.model.AppSettings
import com.innodrive.evdash.domain.model.ConnectionState
import com.innodrive.evdash.domain.model.ThemeSettings

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
