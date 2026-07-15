package com.innodrive.evdash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.innodrive.evdash.presentation.navigation.AppNavHost
import com.innodrive.evdash.presentation.ui.common.BrandSplash
import com.innodrive.evdash.presentation.ui.common.ProvideAppSettings
import com.innodrive.evdash.presentation.ui.permissions.PermissionHandler
import com.innodrive.evdash.presentation.viewmodel.RootViewModel
import com.innodrive.evdash.presentation.viewmodel.RootViewModelFactory
import com.innodrive.evdash.ui.theme.DisplayAppTheme

/**
 * Single Activity. All UI is Compose, all navigation lives in [AppNavHost].
 *
 * Activity-scoped state hub:
 *  - [RootViewModel] observes theme + app preferences.
 *  - [DisplayAppTheme] reads the theme snapshot.
 *  - [ProvideAppSettings] makes the live [com.innodrive.evdash.domain.model.AppSettings]
 *    available to every composable via [com.innodrive.evdash.presentation.ui.common.LocalAppSettings].
 *
 * That way unit changes (km/h ↔ mph, °C ↔ °F, 12h ↔ 24h) propagate through the
 * whole UI tree without threading prefs through every per-screen ViewModel.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate so the splash theme is swapped to
        // the post-splash theme (Theme.DisplayApp) before the window is drawn.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val container = (applicationContext as DisplayApp).appContainer
            val rootVm: RootViewModel = viewModel(
                factory = RootViewModelFactory(
                    container.themeRepository,
                    container.appPreferencesRepository
                )
            )
            val themeSettings by rootVm.themeSettings.collectAsStateWithLifecycle()

            DisplayAppTheme(
                themeMode = themeSettings.mode,
                useDynamicColor = themeSettings.useDynamicColor
            ) {
                ProvideAppSettings(settingsFlow = rootVm.appSettings) {
                    PermissionHandler {
                        Box(Modifier.fillMaxSize()) {
                            AppNavHost()

                            // Branded overlay carrying the tagline the OS splash
                            // can't show; fades out after a short beat.
                            var showSplash by remember { mutableStateOf(true) }
                            LaunchedEffect(Unit) {
                                delay(1400)
                                showSplash = false
                            }
                            AnimatedVisibility(
                                visible = showSplash,
                                exit = fadeOut(animationSpec = tween(500)),
                            ) {
                                BrandSplash()
                            }
                        }
                    }
                }
            }
        }
    }
}
