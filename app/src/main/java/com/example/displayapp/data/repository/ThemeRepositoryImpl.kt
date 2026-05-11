package com.example.displayapp.data.repository

import com.example.displayapp.data.preferences.ThemePreferences
import com.example.displayapp.domain.model.ThemeMode
import com.example.displayapp.domain.model.ThemeSettings
import com.example.displayapp.domain.repository.ThemeRepository
import kotlinx.coroutines.flow.Flow

class ThemeRepositoryImpl(
    private val prefs: ThemePreferences
) : ThemeRepository {

    override val settings: Flow<ThemeSettings> = prefs.settings

    override suspend fun setMode(mode: ThemeMode) = prefs.setMode(mode)

    override suspend fun setDynamicColor(enabled: Boolean) = prefs.setDynamicColor(enabled)
}
