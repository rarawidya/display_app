package com.innodrive.evdash.domain.repository

import com.innodrive.evdash.domain.model.ThemeMode
import com.innodrive.evdash.domain.model.ThemeSettings
import kotlinx.coroutines.flow.Flow

/**
 * Source of truth for user theme preferences.
 * Implementations back this with persistent storage (DataStore).
 */
interface ThemeRepository {
    val settings: Flow<ThemeSettings>
    suspend fun setMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
}
