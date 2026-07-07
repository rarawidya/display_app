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
import androidx.compose.runtime.remember
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.displayapp.DisplayApp
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.presentation.ui.charts.ChartsScreen
import com.example.displayapp.presentation.ui.connection.BluetoothQuickSheet
import com.example.displayapp.presentation.ui.connection.BluetoothStatusPopover
import com.example.displayapp.presentation.ui.dashboard.DashboardScreen
import com.example.displayapp.presentation.ui.device.DeviceScanScreen
import com.example.displayapp.presentation.ui.home.HomeScreen
import com.example.displayapp.presentation.ui.home.LastRide
import com.example.displayapp.presentation.ui.logs.LogsScreen
import com.example.displayapp.presentation.ui.logs.TripDetailScreen
import com.example.displayapp.presentation.ui.maps.NavigationScreen
import com.example.displayapp.presentation.ui.settings.DeveloperScreen
import com.example.displayapp.presentation.ui.settings.SettingsScreen
import com.example.displayapp.presentation.viewmodel.BluetoothViewModel
import com.example.displayapp.presentation.viewmodel.BluetoothViewModelFactory
import com.example.displayapp.presentation.viewmodel.ChartsViewModel
import com.example.displayapp.presentation.viewmodel.ChartsViewModelFactory
import com.example.displayapp.presentation.viewmodel.DashboardViewModel
import com.example.displayapp.presentation.viewmodel.DashboardViewModelFactory
import com.example.displayapp.presentation.viewmodel.LogsViewModel
import com.example.displayapp.presentation.viewmodel.LogsViewModelFactory
import com.example.displayapp.presentation.viewmodel.MapsViewModel
import com.example.displayapp.presentation.viewmodel.MapsViewModelFactory
import com.example.displayapp.presentation.viewmodel.SettingsViewModel
import com.example.displayapp.presentation.viewmodel.SettingsViewModelFactory
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

    val cockpitRoutes = setOf(Destination.Home.route, Destination.Drive.route, Destination.Charts.route, Destination.Logs.route)
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
        startDestination = Destination.Home.route,
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
            // The scan screen now uses BluetoothViewModel directly — same
            // VM the Drive page's long-press sheet uses, so the two surfaces
            // share state (controller, paired list, scan flow).
            val btVm: BluetoothViewModel = viewModel(
                factory = BluetoothViewModelFactory(
                    controller = container.bluetoothController,
                    repository = container.vehicleRepository,
                    devicePreferences = container.devicePreferences,
                    appContext = context.applicationContext
                )
            )
            // DashboardVM is still surfaced here so "Use Simulator" can kick off
            // a simulated connection and let the LaunchedEffect below route to Drive.
            val dashboardVm: DashboardViewModel = viewModel(
                factory = DashboardViewModelFactory(container.vehicleRepository, container.efficiencyTracker, container.diagnosticsRepository)
            )

            val connectionState by container.vehicleRepository.connectionState
                .collectAsStateWithLifecycle()

            // Auto-navigate to Home when the connection becomes live.
            LaunchedEffect(connectionState) {
                if (connectionState == ConnectionState.CONNECTED) {
                    navController.navigate(Destination.Home.route) {
                        popUpTo(Destination.Scan.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            // Back button only when this isn't the first screen the user lands on.
            val canGoBack = navController.previousBackStackEntry != null
            DeviceScanScreen(
                viewModel = btVm,
                onNavigateToDashboard = {
                    navController.navigate(Destination.Home.route) {
                        popUpTo(Destination.Scan.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onUseSimulator = {
                    container.switchDataSource(simulator = true)
                    dashboardVm.connect("SIM")
                    // LaunchedEffect above routes to Drive once state == CONNECTED.
                },
                onBack = if (canGoBack) ({ navController.popBackStack() }) else null
            )
        }

        composable(Destination.Home.route) {
            val vm: DashboardViewModel = viewModel(
                factory = DashboardViewModelFactory(container.vehicleRepository, container.efficiencyTracker, container.diagnosticsRepository)
            )
            val btVm: BluetoothViewModel = viewModel(
                factory = BluetoothViewModelFactory(
                    controller = container.bluetoothController,
                    repository = container.vehicleRepository,
                    devicePreferences = container.devicePreferences,
                    appContext = context.applicationContext
                )
            )
            // Vehicle name for the greeting — last connected device, else a friendly default.
            val savedDevice by container.devicePreferences.lastDevice
                .collectAsStateWithLifecycle(initialValue = null)

            // Bluetooth pairing sheet — the Home page's scan/pair surface.
            var showSheet by rememberSaveable { mutableStateOf(false) }

            // Prompt to turn Bluetooth ON once when the Home page first appears.
            // Only fires when the adapter is actually off and we already hold the
            // connect permission (launching ACTION_REQUEST_ENABLE without it throws).
            val enableLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
            ) { result ->
                btVm.onEnableResult(result.resultCode == android.app.Activity.RESULT_OK)
            }
            var promptedEnable by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (promptedEnable) return@LaunchedEffect
                promptedEnable = true
                val connectGranted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.BLUETOOTH_CONNECT
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val adapterOff = container.bluetoothController.adapterState.value ==
                    com.example.displayapp.data.bluetooth.controller.AdapterState.OFF
                if (connectGranted && adapterOff) {
                    runCatching { enableLauncher.launch(btVm.enableIntent()) }
                }
            }

            val recentTrips by container.tripRepository.observeAllTrips()
                .collectAsStateWithLifecycle(initialValue = emptyList())
            val lastRide = remember(recentTrips) {
                recentTrips.firstOrNull { it.endTime != null }?.let { t ->
                    LastRide(
                        distanceMeters = t.distanceMeters,
                        durationSec = ((t.endTime!! - t.startTime) / 1000L).coerceAtLeast(0L),
                        startMs = t.startTime
                    )
                }
            }
            HomeScreen(
                viewModel = vm,
                lastRide = lastRide,
                deviceName = savedDevice?.name?.takeIf { it.isNotBlank() && it != "Unknown" } ?: "My Scooter",
                onStartMonitoring = {
                    navController.navigateTopLevel(Destination.Drive)
                },
                onOpenSettings = {
                    navController.navigate(Destination.Settings.route) { launchSingleTop = true }
                },
                onOpenHistory = {
                    navController.navigateTopLevel(Destination.Logs)
                },
                onOpenBluetooth = { showSheet = true },
                onEditDevice = {
                    navController.navigate(Destination.Scan.route) { launchSingleTop = true }
                }
            )

            if (showSheet) {
                BluetoothQuickSheet(
                    viewModel = btVm,
                    onDismiss = { showSheet = false }
                )
            }
        }

        composable(Destination.Drive.route) {
            val vm: DashboardViewModel = viewModel(
                factory = DashboardViewModelFactory(container.vehicleRepository, container.efficiencyTracker, container.diagnosticsRepository)
            )
            val mapsVm: MapsViewModel = viewModel(
                factory = MapsViewModelFactory(container.locationRepository)
            )
            val btVm: BluetoothViewModel = viewModel(
                factory = BluetoothViewModelFactory(
                    controller = container.bluetoothController,
                    repository = container.vehicleRepository,
                    devicePreferences = container.devicePreferences,
                    appContext = context.applicationContext
                )
            )
            val wifiConnected by container.wifiStateMonitor.isWifiConnected
                .collectAsStateWithLifecycle(initialValue = false)

            // Sheet + popover are siblings of the cockpit content. Both live at
            // the NavHost level so the BrandHeader callbacks can flip them.
            var showSheet by rememberSaveable { mutableStateOf(false) }
            var showPopover by rememberSaveable { mutableStateOf(false) }
            val btState by btVm.uiState.collectAsStateWithLifecycle()

            DashboardScreen(
                viewModel = vm,
                mapsViewModel = mapsVm,
                wifiConnected = wifiConnected,
                onConnectionTap = { showPopover = true },
                onBluetoothLongPress = { showSheet = true },
                bluetoothAnchor = { _ ->
                    BluetoothStatusPopover(
                        expanded = showPopover,
                        adapterState = btState.adapterState,
                        // Use the raw live state so CONNECTING/RECONNECTING aren't
                        // collapsed to DISCONNECTED (which offered a "Reconnect"
                        // button racing an in-flight reconnect).
                        connectionState = btState.connectionState,
                        connectedName = btState.connected?.name,
                        previouslyConnectedName = btState.previouslyConnected?.name,
                        onDismiss = { showPopover = false },
                        onDisconnect = { btVm.disconnect() },
                        onReconnect = { btVm.reconnectLast() },
                        onOpenSheet = { showSheet = true }
                    )
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

            if (showSheet) {
                BluetoothQuickSheet(
                    viewModel = btVm,
                    onDismiss = { showSheet = false }
                )
            }
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
            val versionInfo = androidx.compose.runtime.remember { container.versionInfo() }
            val versionName = versionInfo.first
            val buildNumber = versionInfo.second
            val settingsVm: SettingsViewModel = viewModel(
                factory = SettingsViewModelFactory(
                    themeRepository = container.themeRepository,
                    appPreferences = container.appPreferencesRepository,
                    devicePreferences = container.devicePreferences,
                    vehicleRepository = container.vehicleRepository,
                    diagnosticsRepository = container.diagnosticsRepository,
                    storageProvider = container.storageInfoProvider,
                    permissionProvider = container.permissionStatusProvider,
                    onSimulatorModeChange = { useSim -> container.setSimulatorSession(useSim) },
                    onSimulatorScenarioChange = { scenario ->
                        container.simulatorScenario = scenario
                        container.restartDataSource()
                    },
                    appVersion = versionName,
                    appBuildNumber = buildNumber
                )
            )
            SettingsScreen(
                viewModel = settingsVm,
                permissionProvider = container.permissionStatusProvider,
                onBack = { navController.popBackStack() },
                onOpenDeveloper = {
                    navController.navigate(Destination.Developer.route) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Destination.Developer.route) {
            val devVersionInfo = remember { container.versionInfo() }
            val versionName = devVersionInfo.first
            val buildNumber = devVersionInfo.second
            val settingsVm: SettingsViewModel = viewModel(
                key = "settings-vm",
                factory = SettingsViewModelFactory(
                    themeRepository = container.themeRepository,
                    appPreferences = container.appPreferencesRepository,
                    devicePreferences = container.devicePreferences,
                    vehicleRepository = container.vehicleRepository,
                    diagnosticsRepository = container.diagnosticsRepository,
                    storageProvider = container.storageInfoProvider,
                    permissionProvider = container.permissionStatusProvider,
                    onSimulatorModeChange = { useSim -> container.setSimulatorSession(useSim) },
                    onSimulatorScenarioChange = { scenario ->
                        container.simulatorScenario = scenario
                        container.restartDataSource()
                    },
                    appVersion = versionName,
                    appBuildNumber = buildNumber
                )
            )
            DeveloperScreen(
                viewModel = settingsVm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Destination.Charts.route) {
            val vm: ChartsViewModel = viewModel(
                factory = ChartsViewModelFactory(container.vehicleRepository, container.efficiencyTracker)
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
