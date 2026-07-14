package com.innodrive.evdash.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "device_prefs")

/**
 * Persists the last connected Bluetooth device for auto-reconnect on startup.
 */
class DevicePreferences(private val context: Context) {

    val lastDevice: Flow<SavedDevice?> = context.dataStore.data.map { prefs ->
        val address = prefs[KEY_LAST_ADDRESS] ?: return@map null
        SavedDevice(
            address = address,
            name = prefs[KEY_LAST_NAME] ?: "Unknown",
            autoConnect = prefs[KEY_AUTO_CONNECT] ?: true
        )
    }

    suspend fun saveDevice(address: String, name: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_ADDRESS] = address
            prefs[KEY_LAST_NAME] = name
        }
    }

    suspend fun setAutoConnect(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_CONNECT] = enabled
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    companion object {
        private val KEY_LAST_ADDRESS = stringPreferencesKey("last_bt_address")
        private val KEY_LAST_NAME = stringPreferencesKey("last_bt_name")
        private val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
    }
}

data class SavedDevice(
    val address: String,
    val name: String,
    val autoConnect: Boolean = true
)
