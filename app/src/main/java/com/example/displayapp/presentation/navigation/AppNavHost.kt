package com.example.displayapp.presentation.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.displayapp.DisplayApp
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.presentation.ui.charts.ChartsScreen
import com.example.displayapp.presentation.ui.dashboard.DashboardScreen
import com.example.displayapp.presentation.ui.device.DeviceScanScreen
import com.example.displayapp.presentation.ui.logs.LogsScreen
import com.example.displayapp.presentation.ui.logs.TripDetailScreen
import com.example.displayapp.presentation.ui.maps.NavigationScreen
import com.example.displayapp.presentation.ui.settings.SettingsScreen
import com.example.displayapp.presentation.viewmodel.ChartsViewModel
import com.example.displayapp.presentation.viewmodel.ChartsViewModelFactory
import com.example.displayapp.presentation.viewmodel.DashboardViewModel
import com.example.displayapp.presentation.viewmodel.DashboardViewModelFactory
import com.example.displayapp.presentation.viewmodel.DeviceViewModel
import com.example.displayapp.presentation.viewmodel.DeviceViewModelFactory
import com.example.displayapp.presentation.viewmodel.LogsViewModel
import com.example.displayapp.presentation.viewmodel.LogsViewModelFactory
import com.example.displayapp.presentation.viewmodel.MapsViewModel
import com.example.displayapp.presentation.viewmodel.MapsViewModelFactory
import com.example.displayapp.presentation.viewmodel.ThemeViewModel
import com.example.displayapp.presentation.viewmodel.ThemeViewModelFactory
import com.example.displayapp.presentation.viewmodel.TripDetailViewModel
import com.example.displayapp.presentation.viewmodel.TripDetailViewModelFactory

/**
 * Root navigation container.
 *
 * Architecture:
 * - One Scaffold + one NavHost. The bottom bar persists across cockpit tabs
 *   (Drive/Charts/Logs) and is hidden on Scan and TripDetail for full-bleed UX.
 * - Scan is the start destination — gates entry to the cockpit so the user
 *   either picks a device (real BT through TelemetryService) or chooses
 *   simulator before any telemetry-driven UI shows up.
 * - Once [ConnectionState.CONNECTED] is reached, navigation auto-pushes Drive.
 * - Each cockpit tab gets its own ViewModel scoped to the back-stack entry.
 *
 * The Drive tab's connection-status chip routes back to Scan instead of
 * opening an in-screen sheet, so there's a single place to manage devices.
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController()
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    val currentRoute = currentDestination?.route

    val cockpitRoutes = setOf(Destination.Drive.route, Destination.Charts.route, Destination.Logs.route)
    // Hide the bottom bar on Navigation too — it's a fullscreen immersive surface.
    val showBottomBar = currentRoute in cockpitRoutes

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                EvBottomBar(
                    currentDestination = currentDestination,
                    onTabSelected = { dest -> navController.navigateTopLevel(dest) }
                )
            }
        }
    ) { innerPadding ->
        AppNavGraph(
            navController = navController,
            contentPadding = innerPadding
        )
    }
}

@Composable
private fun AppNavGraph(
    navController: NavHostController,
    contentPadding: PaddingValues
) {
    val context = LocalContext.current
    val container = (context.applicationContext as DisplayApp).appContainer

    NavHost(
        navController = navController,
        startDestination = Destination.Scan.route,
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        // Cheap cross-tab transitions — keep them quick so realtime UX feels snappy
        enterTransition = {
            slideInHorizontally(tween(220)) { it / 8 } + fadeIn(tween(220))
        },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(160)) },
        popExitTransition = {
            slideOutHorizontally(tween(220)) { it / 8 } + fadeOut(tween(220))
        }
    ) {
        composable(Destination.Scan.route) {
            val deviceVm: DeviceViewModel = viewModel(
                factory = DeviceViewModelFactory(
                    container.vehicleRepository,
                    container.devicePreferences
                )
            )
            // Also surface the dashboard VM here so "Use Simulator" can route to Drive
            val dashboardVm: DashboardViewModel = viewModel(
                factory = DashboardViewModelFactory(container.vehicleRepository)
            )

            val scanState by deviceVm.uiState.collectAsStateWithLifecycle()

            // Auto-navigate to Drive when the connection becomes live
            LaunchedEffect(scanState.connectionState) {
                if (scanState.connectionState == ConnectionState.CONNECTED) {
                    navController.navigate(Destination.Drive.route) {
                        popUpTo(Destination.Scan.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            DeviceScanScreen(
                viewModel = deviceVm,
                onNavigateToDashboard = {
                    navController.navigate(Destination.Drive.route) {
                        popUpTo(Destination.Scan.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onUseSimulator = {
                    container.switchDataSource(simulator = true)
                    dashboardVm.connect("SIM")
                    // Connection becoming CONNECTED triggers the LaunchedEffect above
                }
            )
        }

        composable(Destination.Drive.route) {
            val vm: DashboardViewModel = viewModel(
                factory = DashboardViewModelFactory(container.vehicleRepository)
            )
            val mapsVm: MapsViewModel = viewModel(
                factory = MapsViewModelFactory(container.locationRepository)
            )
            val wifiConnected by container.wifiStateMonitor.isWifiConnected
                .collectAsStateWithLifecycle(initialValue = false)
            DashboardScreen(
                viewModel = vm,
                mapsViewModel = mapsVm,
                wifiConnected = wifiConnected,
                onConnectionTap = {
                    navController.navigate(Destination.Scan.route) {
                        launchSingleTop = true
                    }
                },
                onSettingsTap = {
                    navController.navigate(Destination.Settings.route) {
                        launchSingleTop = true
                    }
                },
                onOpenNavigation = {
                    navController.navigate(Destination.Navigation.route) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Destination.Navigation.route) {
            val mapsVm: MapsViewModel = viewModel(
                factory = MapsViewModelFactory(container.locationRepository)
            )
            NavigationScreen(
                viewModel = mapsVm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Destination.Settings.route) {
            val themeVm: ThemeViewModel = viewModel(
                factory = ThemeViewModelFactory(container.themeRepository)
            )
            SettingsScreen(
                viewModel = themeVm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Destination.Charts.route) {
            val vm: ChartsViewModel = viewModel(
                factory = ChartsViewModelFactory(container.vehicleRepository)
            )
            ChartsScreen(viewModel = vm)
        }

        composable(Destination.Logs.route) {
            val vm: LogsViewModel = viewModel(
                factory = LogsViewModelFactory(container.tripRepository, container.csvExporter)
            )
            LogsScreen(
                viewModel = vm,
                tripRepository = container.tripRepository,
                onTripClick = { id ->
                    navController.navigate(Destination.TripDetail.routeFor(id))
                }
            )
        }

        composable(
            route = Destination.TripDetail.route,
            arguments = listOf(
                navArgument(Destination.TripDetail.ARG_TRIP_ID) { type = NavType.LongType }
            )
        ) { entry ->
            val tripId = entry.arguments?.getLong(Destination.TripDetail.ARG_TRIP_ID) ?: -1L
            val detailVm: TripDetailViewModel = viewModel(
                factory = TripDetailViewModelFactory(container.tripRepository, container.csvExporter)
            )
            TripDetailScreen(
                tripId = tripId,
                viewModel = detailVm,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

private fun NavHostController.navigateTopLevel(dest: Destination) {
    navigate(dest.route) {
        // Pop up to the start destination of the cockpit subgraph so back doesn't pile up tabs
        popUpTo(graph.findStartDestination().id) { saveState = true }
        // Avoid re-launching the same destination
        launchSingleTop = true
        // Restore state when re-selecting a previously selected item
        restoreState = true
    }
}
