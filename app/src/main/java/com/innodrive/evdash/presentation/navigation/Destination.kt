package com.innodrive.evdash.presentation.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.innodrive.evdash.presentation.ui.icons.EvIcons

/**
 * Single source of truth for navigation routes.
 * Adding a screen = adding one [TopLevel] entry — the bottom bar reads this list.
 */
sealed class Destination(val route: String) {

    /** Pre-connect device picker. Start destination — gates entry to the cockpit. */
    data object Scan   : Destination("scan")

    /** Landing overview shown after connect — hero CTA, health, today's summary. */
    data object Home   : Destination("home")

    data object Drive  : Destination("drive")
    data object Charts : Destination("charts")
    data object Logs   : Destination("logs")

    /** Theme & app preferences. Reached from the Drive top-bar gear icon. */
    data object Settings : Destination("settings")

    /** Hidden developer/diagnostics surface. Unlocked by tapping the Settings
     *  version row 7 times — then reachable from a row added to About. */
    data object Developer : Destination("developer")

    /** Fullscreen map + navigation. Reached from the Drive mini-map card. */
    data object Navigation : Destination("navigation")

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
    TopLevel(Destination.Home,   "Home",  EvIcons.Home),
    TopLevel(Destination.Drive,  "Drive", EvIcons.Drive),
    TopLevel(Destination.Charts, "Chart", EvIcons.Charts),
    TopLevel(Destination.Logs,   "Logs",  EvIcons.Logs)
)
