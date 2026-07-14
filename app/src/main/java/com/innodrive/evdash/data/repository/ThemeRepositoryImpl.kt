package com.innodrive.evdash.data.repository

import com.innodrive.evdash.data.preferences.ThemePreferences
import com.innodrive.evdash.domain.model.ThemeMode
import com.innodrive.evdash.domain.model.ThemeSettings
import com.innodrive.evdash.domain.repository.ThemeRepository
import kotlinx.coroutines.flow.Flow

class ThemeRepositoryImpl(
    private val prefs: ThemePreferences
) : ThemeRepository {

    override val settings: Flow<ThemeSettings> = prefs.settings

    override suspend fun setMode(mode: ThemeMode) = prefs.setMode(mode)

    override suspend fun setDynamicColor(enabled: Boolean) = prefs.setDynamicColor(enabled)
}
