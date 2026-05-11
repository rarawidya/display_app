package com.example.displayapp.domain.repository

import com.example.displayapp.domain.model.ThemeMode
import com.example.displayapp.domain.model.ThemeSettings
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
