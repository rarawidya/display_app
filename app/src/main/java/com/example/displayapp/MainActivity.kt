package com.example.displayapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.displayapp.presentation.navigation.AppNavHost
import com.example.displayapp.presentation.ui.permissions.PermissionHandler
import com.example.displayapp.presentation.viewmodel.ThemeViewModel
import com.example.displayapp.presentation.viewmodel.ThemeViewModelFactory
import com.example.displayapp.ui.theme.DisplayAppTheme

/**
 * Single Activity. All UI is Compose, all navigation lives in [AppNavHost].
 *
 * Theme is hoisted here so the entire UI tree sits inside a single
 * [DisplayAppTheme] — when the user changes mode in Settings, only the root
 * MaterialTheme provider recomposes and its animated [androidx.compose.material3.ColorScheme]
 * smoothly transitions every consumer.
 *
 * Edge-to-edge is enabled so the cockpit fills the screen — the bottom nav
 * adds its own [navigationBarsPadding] internally.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val container = (applicationContext as DisplayApp).appContainer
            val themeVm: ThemeViewModel = viewModel(
                factory = ThemeViewModelFactory(container.themeRepository)
            )
            val themeSettings by themeVm.settings.collectAsStateWithLifecycle()

            DisplayAppTheme(
                themeMode = themeSettings.mode,
                useDynamicColor = themeSettings.useDynamicColor
            ) {
                PermissionHandler {
                    AppNavHost()
                }
            }
        }
    }
}
