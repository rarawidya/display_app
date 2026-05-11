package com.example.displayapp.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.displayapp.domain.model.ThemeMode

// ---------------------------------------------------------------------------
// Static color schemes — EV-blue primary, semantic accents preserved
// ---------------------------------------------------------------------------

private val DarkColors = darkColorScheme(
    primary              = EvBlue,
    onPrimary            = EvBlueInk,
    primaryContainer     = EvBlueNavy,
    onPrimaryContainer   = EvBlueIce,

    secondary            = EvBlueCyan,
    onSecondary          = Color(0xFF002B36),
    secondaryContainer   = Color(0xFF003B47),
    onSecondaryContainer = Color(0xFFB8ECF7),

    tertiary             = EvBlueBright,
    onTertiary           = EvBlueInk,
    tertiaryContainer    = Color(0xFF1A3E73),
    onTertiaryContainer  = EvBlueIce,

    error                = EvRed,
    onError              = Color.White,
    errorContainer       = Color(0xFF690005),
    onErrorContainer     = Color(0xFFFFDAD6),

    background              = CarbonBg,
    onBackground            = CarbonOnSurface,
    surface                 = CarbonSurface,
    onSurface               = CarbonOnSurface,
    surfaceVariant          = CarbonSurfaceHi,
    onSurfaceVariant        = CarbonOnVariant,
    surfaceContainer        = CarbonSurfaceHi,
    surfaceContainerHigh    = CarbonSurfaceTop,
    surfaceContainerHighest = CarbonSurfaceTop,
    outline                 = CarbonOutline,
    outlineVariant          = Color(0xFF1F2630)
)

private val LightColors = lightColorScheme(
    primary              = EvBlueDeep,
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFFD5E3FF),
    onPrimaryContainer   = EvBlueInk,

    secondary            = EvBlueCyanDeep,
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFFBFEAF5),
    onSecondaryContainer = Color(0xFF002B36),

    tertiary             = EvBlue,
    onTertiary           = Color.White,
    tertiaryContainer    = Color(0xFFD9E7FF),
    onTertiaryContainer  = EvBlueInk,

    error                = Color(0xFFBA1A1A),
    onError              = Color.White,
    errorContainer       = Color(0xFFFFDAD6),
    onErrorContainer     = Color(0xFF410002),

    background              = PaperBg,
    onBackground            = PaperOnSurface,
    surface                 = PaperSurface,
    onSurface               = PaperOnSurface,
    surfaceVariant          = PaperSurfaceHi,
    onSurfaceVariant        = PaperOnVariant,
    surfaceContainer        = PaperSurfaceHi,
    surfaceContainerHigh    = Color(0xFFDFE7F4),
    surfaceContainerHighest = Color(0xFFD3DCEC),
    outline                 = PaperOutline,
    outlineVariant          = Color(0xFFE0E6F0)
)

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

/**
 * Root MaterialTheme provider.
 *
 * Theme strategy:
 * - [themeMode] resolves to either explicit light/dark or follows the system.
 * - [useDynamicColor] opts in to Material You on Android 12+ (falls back silently).
 * - The active [ColorScheme] is *animated* via [animateColorScheme] so that
 *   switching theme produces a smooth crossfade rather than a hard cut — only
 *   the colors actually visible recompose, not the entire UI tree.
 *
 * @param themeMode user preference; null/default behaves as [ThemeMode.SYSTEM].
 * @param useDynamicColor opt-in Material You wallpaper-derived palette.
 * @param animationSpec animation curve for color transitions.
 */
@Composable
fun DisplayAppTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    useDynamicColor: Boolean = false,
    animationSpec: AnimationSpec<Color> = tween(durationMillis = 420),
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT  -> false
        ThemeMode.DARK   -> true
        ThemeMode.SYSTEM -> systemDark
    }

    val context = LocalContext.current
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val targetScheme = remember(darkTheme, useDynamicColor, dynamicAvailable) {
        when {
            useDynamicColor && dynamicAvailable && darkTheme  -> dynamicDarkColorScheme(context)
            useDynamicColor && dynamicAvailable && !darkTheme -> dynamicLightColorScheme(context)
            darkTheme  -> DarkColors
            else       -> LightColors
        }
    }

    val animatedScheme = animateColorScheme(targetScheme, animationSpec)

    // Sync system bars with the resolved mode so status/nav icons stay readable.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = animatedScheme,
        typography  = Typography,
        shapes      = EvShapes,
        content     = content
    )
}

// ---------------------------------------------------------------------------
// Animated color scheme helper
// ---------------------------------------------------------------------------

/**
 * Builds a [ColorScheme] whose every color animates from its previous value
 * to the target value when [target] changes.
 *
 * This is the standard Compose pattern for smooth theme transitions. Each
 * [animateColorAsState] is its own snapshot state object, so only widgets
 * that read a particular color from [MaterialTheme.colorScheme] recompose
 * when *that* color changes — the rest of the tree is unaffected.
 */
@Composable
private fun animateColorScheme(
    target: ColorScheme,
    spec: AnimationSpec<Color>
): ColorScheme {
    val primary              by animateColorAsState(target.primary,              spec, label = "primary")
    val onPrimary            by animateColorAsState(target.onPrimary,            spec, label = "onPrimary")
    val primaryContainer     by animateColorAsState(target.primaryContainer,     spec, label = "primaryContainer")
    val onPrimaryContainer   by animateColorAsState(target.onPrimaryContainer,   spec, label = "onPrimaryContainer")
    val inversePrimary       by animateColorAsState(target.inversePrimary,       spec, label = "inversePrimary")

    val secondary            by animateColorAsState(target.secondary,            spec, label = "secondary")
    val onSecondary          by animateColorAsState(target.onSecondary,          spec, label = "onSecondary")
    val secondaryContainer   by animateColorAsState(target.secondaryContainer,   spec, label = "secondaryContainer")
    val onSecondaryContainer by animateColorAsState(target.onSecondaryContainer, spec, label = "onSecondaryContainer")

    val tertiary             by animateColorAsState(target.tertiary,             spec, label = "tertiary")
    val onTertiary           by animateColorAsState(target.onTertiary,           spec, label = "onTertiary")
    val tertiaryContainer    by animateColorAsState(target.tertiaryContainer,    spec, label = "tertiaryContainer")
    val onTertiaryContainer  by animateColorAsState(target.onTertiaryContainer,  spec, label = "onTertiaryContainer")

    val error                by animateColorAsState(target.error,                spec, label = "error")
    val onError              by animateColorAsState(target.onError,              spec, label = "onError")
    val errorContainer       by animateColorAsState(target.errorContainer,       spec, label = "errorContainer")
    val onErrorContainer     by animateColorAsState(target.onErrorContainer,     spec, label = "onErrorContainer")

    val background           by animateColorAsState(target.background,           spec, label = "background")
    val onBackground         by animateColorAsState(target.onBackground,         spec, label = "onBackground")
    val surface              by animateColorAsState(target.surface,              spec, label = "surface")
    val onSurface            by animateColorAsState(target.onSurface,            spec, label = "onSurface")
    val surfaceVariant       by animateColorAsState(target.surfaceVariant,       spec, label = "surfaceVariant")
    val onSurfaceVariant     by animateColorAsState(target.onSurfaceVariant,     spec, label = "onSurfaceVariant")
    val surfaceTint          by animateColorAsState(target.surfaceTint,          spec, label = "surfaceTint")
    val inverseSurface       by animateColorAsState(target.inverseSurface,       spec, label = "inverseSurface")
    val inverseOnSurface     by animateColorAsState(target.inverseOnSurface,     spec, label = "inverseOnSurface")

    val surfaceContainer        by animateColorAsState(target.surfaceContainer,        spec, label = "surfaceContainer")
    val surfaceContainerHigh    by animateColorAsState(target.surfaceContainerHigh,    spec, label = "surfaceContainerHigh")
    val surfaceContainerHighest by animateColorAsState(target.surfaceContainerHighest, spec, label = "surfaceContainerHighest")
    val surfaceContainerLow     by animateColorAsState(target.surfaceContainerLow,     spec, label = "surfaceContainerLow")
    val surfaceContainerLowest  by animateColorAsState(target.surfaceContainerLowest,  spec, label = "surfaceContainerLowest")

    val outline              by animateColorAsState(target.outline,              spec, label = "outline")
    val outlineVariant       by animateColorAsState(target.outlineVariant,       spec, label = "outlineVariant")
    val scrim                by animateColorAsState(target.scrim,                spec, label = "scrim")

    return ColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = surfaceTint,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        surfaceBright = target.surfaceBright,
        surfaceDim = target.surfaceDim,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
    )
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)
