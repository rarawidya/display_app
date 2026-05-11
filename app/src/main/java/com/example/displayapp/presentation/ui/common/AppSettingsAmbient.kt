package com.example.displayapp.presentation.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.domain.model.AppSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * CompositionLocal that exposes the live [AppSettings] snapshot to any
 * composable in the tree.
 *
 * Why a CompositionLocal instead of threading prefs through every ViewModel?
 *  - Units (km/h vs mph, °C vs °F, 12h vs 24h) are a cross-cutting *display*
 *    concern, not a domain concern. Forcing every VM to combine its data with
 *    AppSettings doubles the plumbing without making testing meaningfully
 *    easier.
 *  - Compose's MaterialTheme uses the same pattern for color/typography.
 *  - Reads are scoped — a composable that doesn't read [LocalAppSettings.current]
 *    won't recompose when units change, just like with theme colors.
 *
 * The default value (`AppSettings()`) means screens that compose outside the
 * provided scope (previews, tests) still get a sane default and never crash.
 */
val LocalAppSettings = compositionLocalOf { AppSettings() }

/**
 * Provider — typically used at the app root (MainActivity) so the whole
 * UI tree observes the live settings flow.
 *
 * Reads via [collectAsStateWithLifecycle] so the upstream flow pauses while
 * the activity isn't RESUMED. The provider re-composes only its content slot
 * when the snapshot changes; downstream composables that read
 * [LocalAppSettings] recompose at the field-read site only.
 */
@Composable
fun ProvideAppSettings(
    settingsFlow: StateFlow<AppSettings>,
    content: @Composable () -> Unit
) {
    val settings by settingsFlow.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalAppSettings provides settings) {
        content()
    }
}
