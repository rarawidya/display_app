package com.innodrive.evdash.domain.model

/**
 * User-selectable theme preference.
 *
 * - [SYSTEM] follows the device's day/night setting (default).
 * - [LIGHT] / [DARK] override regardless of system.
 *
 * Kept framework-free so it can be referenced from data + presentation layers
 * without pulling Android dependencies into domain.
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM;

    companion object {
        fun fromKey(raw: String?): ThemeMode = when (raw) {
            LIGHT.name -> LIGHT
            DARK.name  -> DARK
            else       -> SYSTEM
        }
    }
}

/**
 * Snapshot of all theme-related settings persisted for the user.
 */
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** Android 12+ Material You. Ignored on lower API levels at the theme layer. */
    val useDynamicColor: Boolean = false
)
