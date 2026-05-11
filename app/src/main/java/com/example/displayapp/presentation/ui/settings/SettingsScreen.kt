package com.example.displayapp.presentation.ui.settings

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.domain.model.ThemeMode
import com.example.displayapp.domain.model.ThemeSettings
import com.example.displayapp.presentation.ui.common.GlassCard
import com.example.displayapp.presentation.ui.common.SectionHeader
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.viewmodel.ThemeViewModel
import com.example.displayapp.ui.theme.Dim

/**
 * Settings screen — primarily a theme picker today, extensible for future preferences.
 *
 * Architecture:
 * - Stateless [SettingsContent] for previews; [SettingsScreen] is the VM-bound entry.
 * - Theme preview cards render miniature dashboard mocks in each candidate scheme
 *   so users see what they're picking before committing.
 * - Dynamic Color (Material You) is gated to Android 12+ and hidden below.
 */
@Composable
fun SettingsScreen(
    viewModel: ThemeViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        onModeSelected = viewModel::setMode,
        onDynamicColorToggle = viewModel::setDynamicColor,
        onBack = onBack
    )
}

@Composable
fun SettingsContent(
    settings: ThemeSettings,
    onModeSelected: (ThemeMode) -> Unit,
    onDynamicColorToggle: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SettingsTopBar(onBack = onBack) },
        // Outer AppNavHost Scaffold already supplied the system-bar insets.
        // Leaving this at the default (WindowInsets.systemBars) would re-add them.
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { inner ->
        SettingsBody(
            settings = settings,
            onModeSelected = onModeSelected,
            onDynamicColorToggle = onDynamicColorToggle,
            contentPadding = inner
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  Top bar                                                                   */
/* -------------------------------------------------------------------------- */

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = EvIcons.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent
        ),
        // The outer AppNavHost Scaffold already consumed the status-bar inset.
        // TopAppBar's default windowInsets would re-add it here — kill it.
        windowInsets = WindowInsets(0, 0, 0, 0)
    )
}

/* -------------------------------------------------------------------------- */
/*  Body                                                                      */
/* -------------------------------------------------------------------------- */

@Composable
private fun SettingsBody(
    settings: ThemeSettings,
    onModeSelected: (ThemeMode) -> Unit,
    onDynamicColorToggle: (Boolean) -> Unit,
    contentPadding: PaddingValues
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dim.screenGutter, vertical = Dim.md),
        verticalArrangement = Arrangement.spacedBy(Dim.lg)
    ) {
        // ----- Appearance section -----
        SectionHeader(title = "Appearance")
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Dim.md)) {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Choose how the cockpit looks. System follows your device's day/night setting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dim.sm)
                ) {
                    ThemeOptions.forEach { option ->
                        ThemePreviewCard(
                            option = option,
                            selected = settings.mode == option.mode,
                            onClick = { onModeSelected(option.mode) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // ----- Dynamic color (Android 12+) -----
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SectionHeader(title = "Material You")
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Dynamic color",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Derive colors from your wallpaper. Overrides the EV-blue brand palette.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.useDynamicColor,
                        onCheckedChange = onDynamicColorToggle
                    )
                }
            }
        }

        // ----- About -----
        SectionHeader(title = "About")
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Dim.xs)) {
                Text(
                    text = "DisplayApp",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "EV telemetry cockpit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(Dim.lg))
    }
}

/* -------------------------------------------------------------------------- */
/*  Theme preview card                                                        */
/* -------------------------------------------------------------------------- */

private data class ThemeOption(
    val mode: ThemeMode,
    val label: String,
    val swatchBg: Color,
    val swatchSurface: Color,
    val swatchPrimary: Color,
    val swatchOnSurface: Color
)

// Stable preview swatches — purposely hard-coded so each card always looks
// like that theme, regardless of the *active* MaterialTheme.
private val ThemeOptions = listOf(
    ThemeOption(
        mode = ThemeMode.LIGHT,
        label = "Light",
        swatchBg = Color(0xFFF3F6FB),
        swatchSurface = Color(0xFFFFFFFF),
        swatchPrimary = Color(0xFF1E5BD8),
        swatchOnSurface = Color(0xFF0A0F1F)
    ),
    ThemeOption(
        mode = ThemeMode.DARK,
        label = "Dark",
        swatchBg = Color(0xFF05070A),
        swatchSurface = Color(0xFF161B23),
        swatchPrimary = Color(0xFF4FA3FF),
        swatchOnSurface = Color(0xFFE6EDF3)
    ),
    ThemeOption(
        mode = ThemeMode.SYSTEM,
        label = "System",
        // Half-and-half swatch communicates "follows device"
        swatchBg = Color(0xFF05070A),
        swatchSurface = Color(0xFFFFFFFF),
        swatchPrimary = Color(0xFF4FA3FF),
        swatchOnSurface = Color(0xFFE6EDF3)
    )
)

@Composable
private fun ThemePreviewCard(
    option: ThemeOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Animated border highlights the active selection without re-laying-out
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outlineVariant,
        label = "theme-border"
    )
    val borderWidth = if (selected) 2.dp else 1.dp

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(Dim.cardCorner))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Dim.cardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(borderWidth, borderColor),
        tonalElevation = if (selected) 6.dp else 2.dp
    ) {
        Column(
            modifier = Modifier.padding(Dim.md),
            verticalArrangement = Arrangement.spacedBy(Dim.sm)
        ) {
            ThemeMiniature(option = option)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

/**
 * Miniature dashboard mock — background bar + speedometer dot + two metric pills.
 * Uses the option's *fixed* swatch colors so each preview always shows that mode.
 *
 * For SYSTEM, splits the miniature diagonally so users can tell at a glance
 * that this option follows device settings.
 */
@Composable
private fun ThemeMiniature(option: ThemeOption) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (option.mode == ThemeMode.SYSTEM) {
                    Brush.horizontalGradient(
                        0f to Color(0xFFF3F6FB),
                        0.49f to Color(0xFFF3F6FB),
                        0.51f to Color(0xFF05070A),
                        1f to Color(0xFF05070A)
                    )
                } else {
                    Brush.verticalGradient(listOf(option.swatchBg, option.swatchSurface))
                }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top: faux status line
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier
                        .height(4.dp)
                        .width(20.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(option.swatchPrimary)
                )
                Box(
                    Modifier
                        .height(4.dp)
                        .width(12.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(option.swatchOnSurface.copy(alpha = 0.4f))
                )
            }
            // Bottom: speedometer-like dot
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(option.swatchPrimary.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(option.swatchPrimary)
                    )
                }
            }
        }
    }
}
