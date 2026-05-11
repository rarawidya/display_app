package com.example.displayapp.presentation.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.example.displayapp.presentation.ui.icons.EvIcons

/**
 * Single source of truth for navigation routes.
 * Adding a screen = adding one [TopLevel] entry — the bottom bar reads this list.
 */
sealed class Destination(val route: String) {

    /** Pre-connect device picker. Start destination — gates entry to the cockpit. */
    data object Scan   : Destination("scan")

    data object Drive  : Destination("drive")
    data object Charts : Destination("charts")
    data object Logs   : Destination("logs")

    /** Theme & app preferences. Reached from the Drive top-bar gear icon. */
    data object Settings : Destination("settings")

    /** Detail route reached from Logs. Carries trip id as a path arg. */
    data object TripDetail : Destination("trip/{tripId}") {
        const val ARG_TRIP_ID = "tripId"
        fun routeFor(id: Long) = "trip/$id"
    }
}

/** Tabs shown in the bottom navigation, in order. */
data class TopLevel(
    val destination: Destination,
    val label: String,
    val icon: ImageVector
)

val BottomTabs = listOf(
    TopLevel(Destination.Drive,  "Drive", EvIcons.Drive),
    TopLevel(Destination.Charts, "Chart", EvIcons.Charts),
    TopLevel(Destination.Logs,   "Logs",  EvIcons.Logs)
)
