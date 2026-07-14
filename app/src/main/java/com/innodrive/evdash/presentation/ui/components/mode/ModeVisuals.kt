package com.innodrive.evdash.presentation.ui.components.mode

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.innodrive.evdash.domain.model.VehicleMode
import com.innodrive.evdash.ui.theme.ModeEco
import com.innodrive.evdash.ui.theme.ModeNormal
import com.innodrive.evdash.ui.theme.ModePark
import com.innodrive.evdash.ui.theme.ModeRegen
import com.innodrive.evdash.ui.theme.ModeSport

/**
 * Single source of truth for everything visual a [VehicleMode] implies —
 * label, accent color, behavior text, and how strongly the cockpit should
 * react (glow intensity, ambient tint).
 *
 * Why a catalog?
 *
 *  - The Drive segmented selector, the Charts header badge, the Trip Detail
 *    hero, the ambient background glow and the speedometer accent all consume
 *    the same enum. Without a catalog each surface would re-derive colors and
 *    labels in slightly different ways, and adding a new mode would touch six
 *    files. With it, [VehicleMode.visuals] is the only function that knows.
 *
 *  - It keeps composables free of per-mode `when` blocks. The active mode just
 *    asks for its style; widgets render whatever's in the [ModeVisuals]
 *    instance.
 *
 *  - [intensity] is a unitless 0..1 multiplier consumers can use for glow
 *    alphas, animation amplitude, etc. — PARK is dormant (0), REGEN/SPORT
 *    fully lit (1).
 */
@Immutable
data class ModeVisuals(
    val mode: VehicleMode,
    val label: String,
    val short: String,
    val accent: Color,
    val behavior: String,
    val intensity: Float
)

fun VehicleMode.visuals(): ModeVisuals = when (this) {
    VehicleMode.PARK   -> ModeVisuals(
        mode = this,
        label = "PARK",
        short = "P",
        accent = ModePark,
        behavior = "Stationary",
        intensity = 0f
    )
    VehicleMode.ECO    -> ModeVisuals(
        mode = this,
        label = "ECO",
        short = "E",
        accent = ModeEco,
        behavior = "Efficiency priority",
        intensity = 0.55f
    )
    VehicleMode.NORMAL -> ModeVisuals(
        mode = this,
        label = "NORMAL",
        short = "N",
        accent = ModeNormal,
        behavior = "Balanced drive",
        intensity = 0.7f
    )
    VehicleMode.SPORT  -> ModeVisuals(
        mode = this,
        label = "SPORT",
        short = "S",
        accent = ModeSport,
        behavior = "Maximum acceleration",
        intensity = 1f
    )
    VehicleMode.REGEN  -> ModeVisuals(
        mode = this,
        label = "REGEN",
        short = "R",
        accent = ModeRegen,
        behavior = "Energy recovery active",
        intensity = 1f
    )
}
