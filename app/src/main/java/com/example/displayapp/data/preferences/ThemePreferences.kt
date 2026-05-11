package com.example.displayapp.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.displayapp.domain.model.ThemeMode
import com.example.displayapp.domain.model.ThemeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Separate DataStore file from device prefs so theme settings survive a "clear devices" action.
private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_prefs")

/**
 * Persists user theme preferences (mode + dynamic color) via Jetpack DataStore.
 *
 * Read path is a [Flow] so the UI can react in real time without app restart.
 */
class ThemePreferences(private val context: Context) {

    val settings: Flow<ThemeSettings> = context.themeDataStore.data.map { prefs ->
        ThemeSettings(
            mode = ThemeMode.fromKey(prefs[KEY_MODE]),
            useDynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: false
        )
    }

    suspend fun setMode(mode: ThemeMode) {
        context.themeDataStore.edit { it[KEY_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.themeDataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    private companion object {
        val KEY_MODE = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }
}
