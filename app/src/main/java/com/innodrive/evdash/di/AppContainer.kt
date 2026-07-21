package com.innodrive.evdash.di

import android.content.Context
import android.content.pm.PackageManager
import com.innodrive.evdash.BuildConfig
import com.innodrive.evdash.data.bluetooth.BluetoothDataSource
import com.innodrive.evdash.data.bluetooth.SwitchableDataSource
import com.innodrive.evdash.data.bluetooth.ble.BleDataSource
import com.innodrive.evdash.data.bluetooth.controller.BleController
import com.innodrive.evdash.data.bluetooth.controller.BluetoothController
import com.innodrive.evdash.data.diagnostics.DiagnosticsRepository
import com.innodrive.evdash.data.energy.EfficiencyTracker
import com.innodrive.evdash.data.location.FusedLocationRepository
import com.innodrive.evdash.data.location.SimulatedLocationRepository
import com.innodrive.evdash.data.location.SwitchableLocationRepository
import com.innodrive.evdash.data.navigation.GraphHopperNavigationProvider
import com.innodrive.evdash.data.navigation.NavigationCoordinator
import com.innodrive.evdash.data.navigation.RouteNavigator
import com.innodrive.evdash.data.navigation.SimulatedNavigationProvider
import com.innodrive.evdash.data.navigation.graphhopper.GraphHopperGeocoder
import com.innodrive.evdash.data.navigation.graphhopper.GraphHopperRoutePlanner
import com.innodrive.evdash.domain.repository.Geocoder
import com.innodrive.evdash.domain.repository.NavigationProvider
import com.innodrive.evdash.domain.repository.RoutePlanner
import kotlinx.coroutines.flow.filterNotNull
import com.innodrive.evdash.data.notification.CallControlHandler
import com.innodrive.evdash.data.notification.BoardWifiConnector
import com.innodrive.evdash.data.notification.CallStateRelay
import com.innodrive.evdash.data.notification.PhoneNotificationSender
import com.innodrive.evdash.data.notification.TimeSyncCoordinator
import com.innodrive.evdash.data.permissions.PermissionStatusProvider
import com.innodrive.evdash.data.preferences.AppPreferences
import com.innodrive.evdash.data.preferences.DevicePreferences
import com.innodrive.evdash.data.preferences.LastRouteStore
import com.innodrive.evdash.data.preferences.ThemePreferences
import com.innodrive.evdash.data.persistence.RetentionPolicy
import com.innodrive.evdash.data.persistence.StorageInfoProvider
import com.innodrive.evdash.data.persistence.TelemetryDatabase
import com.innodrive.evdash.data.persistence.TelemetryLogger
import com.innodrive.evdash.data.persistence.TelemetryReplaySource
import com.innodrive.evdash.data.persistence.TripSessionManager
import com.innodrive.evdash.data.persistence.export.CsvExporter
import com.innodrive.evdash.data.protocol.TelemetryMapper
import com.innodrive.evdash.data.replay.RoomTripReplaySource
import com.innodrive.evdash.data.repository.AppPreferencesRepositoryImpl
import com.innodrive.evdash.data.repository.ThemeRepositoryImpl
import com.innodrive.evdash.data.repository.TripRepositoryImpl
import com.innodrive.evdash.data.repository.VehicleRepositoryImpl
import com.innodrive.evdash.data.system.HotspotStateMonitor
import com.innodrive.evdash.data.system.WifiStateMonitor
import com.innodrive.evdash.data.simulator.SampleTripSeeder
import com.innodrive.evdash.data.simulator.SimulatedDataSource
import com.innodrive.evdash.data.simulator.TelemetryScenario
import com.innodrive.evdash.domain.replay.TripReplaySource
import com.innodrive.evdash.domain.repository.AppPreferencesRepository
import com.innodrive.evdash.domain.repository.LocationRepository
import com.innodrive.evdash.domain.repository.ThemeRepository
import com.innodrive.evdash.domain.repository.TripRepository
import com.innodrive.evdash.domain.model.GeoLocation
import com.innodrive.evdash.domain.repository.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(private val context: Context) {

    /** App-lived scope for container-owned coroutines (e.g. the nav demo). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var useSimulator: Boolean = true
    var simulatorScenario: TelemetryScenario = TelemetryScenario.CITY_CRUISE

    // Preferences
    val devicePreferences: DevicePreferences by lazy {
        DevicePreferences(context)
    }

    private val themePreferences: ThemePreferences by lazy {
        ThemePreferences(context)
    }

    val themeRepository: ThemeRepository by lazy {
        ThemeRepositoryImpl(themePreferences)
    }

    private val appPreferences: AppPreferences by lazy { AppPreferences(context) }

    val appPreferencesRepository: AppPreferencesRepository by lazy {
        AppPreferencesRepositoryImpl(appPreferences)
    }

    val diagnosticsRepository: DiagnosticsRepository by lazy { DiagnosticsRepository() }

    val permissionStatusProvider: PermissionStatusProvider by lazy {
        PermissionStatusProvider(context)
    }

    val storageInfoProvider: StorageInfoProvider by lazy {
        StorageInfoProvider(context, tripDao, telemetryDao)
    }

    fun versionInfo(): Pair<String, String> {
        val pm = context.packageManager
        return try {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, 0)
            val versionName = info.versionName ?: ""
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION") info.versionCode.toString()
            }
            versionName to versionCode
        } catch (_: PackageManager.NameNotFoundException) {
            "" to ""
        }
    }

    val wifiStateMonitor: WifiStateMonitor by lazy {
        WifiStateMonitor(context)
    }

    /** Phone hotspot (soft-AP) on/off — the Home/Drive header indicator. */
    val hotspotStateMonitor: HotspotStateMonitor by lazy {
        HotspotStateMonitor(context)
    }

    /** Real GPS — Play Services fused provider. Active whenever the simulator is off. */
    private val fusedLocationRepository: LocationRepository by lazy {
        FusedLocationRepository(context)
    }

    /**
     * Hardware-free location: the "driver" walks the active route (see
     * [SimulatedLocationRepository]). Paced by the live simulated vehicle speed so the
     * Drive gauge and the map move together. Wired to the nav session's active route by
     * [navigationCoordinator].
     */
    val simulatedLocationRepository: SimulatedLocationRepository by lazy {
        SimulatedLocationRepository(
            scope = appScope,
            speedKmh = { vehicleRepository.vehicleData.value.speed.toFloat() },
        )
    }

    /**
     * Stable location facade — swaps fused ↔ simulated with the data source so the map,
     * nav provider, and coordinator (which capture this once) always read the source
     * that matches the current simulator toggle. See [SwitchableLocationRepository].
     */
    private val switchableLocationRepository: SwitchableLocationRepository by lazy {
        SwitchableLocationRepository(
            if (useSimulator) simulatedLocationRepository else fusedLocationRepository
        )
    }

    val locationRepository: LocationRepository get() = switchableLocationRepository

    /**
     * A location-permission grant arrived — force the shared location source to
     * re-subscribe so the app-scoped nav coordinator/provider (which collect it once)
     * recover. Fused GPS otherwise stays completed-and-silent from the pre-grant attempt.
     */
    fun onLocationPermissionGranted() {
        switchableLocationRepository.restart()
    }

    /**
     * Active map renderer (MapLibre). Behind the renderer-neutral
     * [com.innodrive.evdash.presentation.ui.maps.MapProvider] interface so the map
     * SDK is swappable without touching any screen. Reads the tile style URL from
     * `BuildConfig.MAP_STYLE_URL` (local.properties) — blank → placeholder gate.
     */
    val mapProvider: com.innodrive.evdash.presentation.ui.maps.MapProvider by lazy {
        com.innodrive.evdash.presentation.ui.maps.MapLibreMapProvider(BuildConfig.MAP_STYLE_URL)
    }

    /**
     * Routing provider behind the [RoutePlanner] port — GraphHopper hosted Directions
     * API today (swap the implementation for ORS/HERE/self-hosted with no downstream
     * change). Gated on `GRAPHHOPPER_API_KEY`; blank → not configured.
     */
    val routePlanner: RoutePlanner by lazy {
        GraphHopperRoutePlanner(BuildConfig.GRAPHHOPPER_API_KEY)
    }

    /** Place search behind the [Geocoder] port — GraphHopper Geocoding API (same key). */
    val geocoder: Geocoder by lazy {
        GraphHopperGeocoder(BuildConfig.GRAPHHOPPER_API_KEY)
    }

    /**
     * Production [NavigationProvider]: GraphHopper routing + the provider-independent
     * `RouteProgressTracker`. Its `NavProgress` is the single source of truth that will
     * feed both the Drive map overlay and the BLE `NavInstruction` stream (via
     * `RouteNavigator`). The Developer demo keeps using `SimulatedNavigationProvider`
     * until the overlay wiring lands.
     */
    val navigationProvider: NavigationProvider by lazy {
        GraphHopperNavigationProvider(
            planner = routePlanner,
            locations = locationRepository.location.filterNotNull(),
            scope = appScope,
        )
    }

    /** Production navigation → BLE: streams the GraphHopper NavProgress to the controller. */
    private val routeNavigator: RouteNavigator by lazy {
        RouteNavigator(navigationProvider, bluetoothDataSource, appScope)
    }

    /**
     * The one shared navigation session. A destination pick drives BOTH the phone map
     * overlay (route geometry + live state) and the BLE `NavInstruction` stream from a
     * single `NavProgress` — the convergence point. App-scoped singleton so the Drive
     * mini-map and the Navigation screen observe the same session.
     */
    val navigationCoordinator: NavigationCoordinator by lazy {
        NavigationCoordinator(
            provider = navigationProvider,
            routeNavigator = routeNavigator,
            routePlanner = routePlanner,
            locations = locationRepository.location.filterNotNull(),
            scope = appScope,
            lastRouteStore = lastRouteStore,
        ).also { coordinator ->
            // In simulator mode, walk the phone's location along the confirmed route so
            // the map puck + nav progress advance without real GPS. In real mode the
            // simulated source isn't the active delegate, so we skip it (setRoute(null))
            // to avoid running an unseen walk coroutine.
            appScope.launch {
                coordinator.activeRoute.collect { plan ->
                    simulatedLocationRepository.setRoute(if (useSimulator) plan else null)
                }
            }
        }
    }

    /** Last navigated route, persisted for the Home page's Last Ride thumbnail. */
    val lastRouteStore: LastRouteStore by lazy { LastRouteStore(context) }

    val tripRepository: TripRepository by lazy {
        TripRepositoryImpl(tripDao, telemetryDao, faultEventDao)
    }

    val tripReplaySource: TripReplaySource by lazy {
        RoomTripReplaySource(telemetryDao)
    }

    // Database
    private val database: TelemetryDatabase by lazy {
        TelemetryDatabase.getInstance(context)
    }

    // DAOs
    // Was private; surfaced to package level so TripRepository / RoomTripReplaySource
    // can read telemetry rows without going through another wrapper.
    internal val telemetryDao by lazy { database.telemetryDao() }
    val tripDao by lazy { database.tripDao() }
    private val faultEventDao by lazy { database.faultEventDao() }

    // Persistence layer
    val telemetryLogger: TelemetryLogger by lazy {
        TelemetryLogger(telemetryDao)
    }

    val tripSessionManager: TripSessionManager by lazy {
        TripSessionManager(tripDao, telemetryDao, faultEventDao, telemetryLogger)
    }

    val telemetryReplaySource: TelemetryReplaySource by lazy {
        TelemetryReplaySource(telemetryDao)
    }

    val csvExporter: CsvExporter by lazy {
        CsvExporter(context, telemetryDao, tripDao)
    }

    val retentionPolicy: RetentionPolicy by lazy {
        RetentionPolicy(telemetryDao, tripDao, faultEventDao)
    }

    /**
     * Seeds a handful of demo trips on first launch when the simulator is on,
     * so the Logs page isn't empty for users running hardware-free. Idempotent —
     * skips when the trips table already has rows.
     */
    val sampleTripSeeder: SampleTripSeeder by lazy {
        SampleTripSeeder(tripDao, telemetryDao, faultEventDao)
    }

    // Bluetooth data source.
    //
    // The repository binds to a single [SwitchableDataSource] facade whose identity
    // never changes; switching simulator ↔ real swaps the delegate *inside* it, so
    // the repository (and every ViewModel observing it) always talks to the active
    // source. Before this facade the repository captured the first delegate forever,
    // making switchDataSource() a silent no-op — real-device connects kept running
    // the simulator and disconnects never moved the UI off CONNECTED.
    // Real (non-simulator) transport is BLE/GATT — the controller is a BLE peripheral
    // (docs/EVDISPLAY_BLE_COMMUNICATION.md). Classic SPP was retired in Phase 5.
    private fun createDelegate(): BluetoothDataSource =
        if (useSimulator) SimulatedDataSource(simulatorScenario) else BleDataSource(context)

    private val switchableDataSource: SwitchableDataSource by lazy {
        SwitchableDataSource(createDelegate())
    }

    val bluetoothDataSource: BluetoothDataSource get() = switchableDataSource

    val telemetryMapper: TelemetryMapper by lazy { TelemetryMapper() }

    /**
     * Pushes phone notifications to the board over the active transport's command
     * channel (0xAF07). Bound to the stable [bluetoothDataSource] facade so it
     * always targets the live link; [com.innodrive.evdash.service.NotificationRelayService]
     * reads it via the app container.
     */
    val phoneNotificationSender: PhoneNotificationSender by lazy {
        PhoneNotificationSender(bluetoothDataSource, diagnosticsRepository, appScope)
    }

    /**
     * Reads the stored hotspot credentials and pushes them to the board's Wi-Fi STA
     * (SSID → PSK → JOIN). Shared by the Settings action and the one-tap header
     * hotspot button so both hand the display its Wi-Fi through one code path.
     */
    val boardWifiConnector: BoardWifiConnector by lazy {
        BoardWifiConnector(appPreferencesRepository, phoneNotificationSender)
    }

    /**
     * Pushes the phone's wall clock to the board on every connect + every ~5 min +
     * on a timezone/DST change (docs/TIME-SYNC-INTEGRATION.md). The board has no RTC,
     * so it boots to 2018 until the phone syncs it. Started once from
     * [com.innodrive.evdash.DisplayApp.onCreate].
     */
    val timeSyncCoordinator: TimeSyncCoordinator by lazy {
        TimeSyncCoordinator(
            context = context,
            sender = phoneNotificationSender,
            connectionState = vehicleRepository.connectionState,
            prefs = appPreferencesRepository,
            scope = appScope,
        )
    }

    /**
     * Call capture (docs/NOTIFICATION-APP-FIXME.md §3) — calls don't arrive through
     * the NotificationListenerService, so a TelephonyCallback mirrors ringing/
     * in-call/ended to the board as `category=1` frames. Follows the same relay
     * toggle as [phoneNotificationSender]; started once in DisplayApp.onCreate.
     */
    val callStateRelay: CallStateRelay by lazy {
        CallStateRelay(context, phoneNotificationSender, appPreferencesRepository, appScope)
    }

    /**
     * The reverse direction (docs/CALL-CONTROL-INTEGRATION.md): board button taps
     * arrive on the `0xAF05` uplink and answer/end the phone call. Bound to the
     * stable [bluetoothDataSource] facade; started once in DisplayApp.onCreate.
     */
    val callControlHandler: CallControlHandler by lazy {
        CallControlHandler(context, bluetoothDataSource, appPreferencesRepository, appScope)
    }

    val vehicleRepository: VehicleRepository by lazy {
        VehicleRepositoryImpl(bluetoothDataSource, telemetryMapper, tripSessionManager, diagnosticsRepository)
    }

    /**
     * Adapter/discovery surface for the quick-settings sheet — BLE (`BluetoothLeScanner`,
     * filtered on the controller's service UUID). Independent from [bluetoothDataSource]
     * so the sheet's lifecycle never interferes with the connection pipeline.
     */
    val bluetoothController: BluetoothController by lazy {
        BleController(context.applicationContext)
    }

    /**
     * Live efficiency state (rolling Wh/km, range estimate, instant power).
     * Subscribes to vehicleData + connectionState at first access; the
     * DashboardViewModel reads its state flow.
     */
    val efficiencyTracker: EfficiencyTracker by lazy {
        EfficiencyTracker(
            vehicleData = vehicleRepository.vehicleData,
            connectionState = vehicleRepository.connectionState
        )
    }

    fun switchDataSource(simulator: Boolean) {
        val alreadyMatches = simulator == useSimulator &&
            switchableDataSource.active.let { active ->
                if (simulator) active is SimulatedDataSource else active is BleDataSource
            }
        if (alreadyMatches) return
        useSimulator = simulator
        switchableDataSource.swap(createDelegate())
        // Keep the location source in lock-step: simulated (route-walking) in sim mode,
        // real fused GPS otherwise.
        switchableLocationRepository.swap(
            if (simulator) simulatedLocationRepository else fusedLocationRepository
        )
    }

    /**
     * Start/stop a live **simulator session** from the Settings toggle.
     *
     * Selecting the simulator source alone leaves it idle (DISCONNECTED) — nothing
     * ever calls [VehicleRepository.connect] on it, unlike the real-device path which
     * goes through [com.innodrive.evdash.service.TelemetryService]. So turning the
     * toggle on also opens a session so telemetry streams immediately; turning it off
     * swaps back to the real transport, and that swap tears the simulator down (see
     * [SwitchableDataSource.swap]), so no explicit disconnect is needed.
     */
    suspend fun setSimulatorSession(useSim: Boolean) {
        switchDataSource(simulator = useSim)
        if (useSim) vehicleRepository.connect(SIMULATOR_SESSION_ADDRESS)
    }


    /**
     * Force-restart the current data source — used when the simulator scenario
     * changes and the running SimulatedDataSource needs to be replaced.
     *
     * A freshly constructed [SimulatedDataSource] is idle (it only emits after
     * [connect]), and [SwitchableDataSource.swap] resets state to DISCONNECTED — so
     * without re-opening the session the scenario change would freeze telemetry and
     * flip the UI to "Disconnected". Reconnect (simulator only) so the new scenario
     * streams immediately; a real link is left alone (scenario has no effect there).
     */
    fun restartDataSource() {
        switchableDataSource.swap(createDelegate())
        if (useSimulator) {
            appScope.launch { vehicleRepository.connect(SIMULATOR_SESSION_ADDRESS) }
        }
    }

    /**
     * Provider-agnostic navigation orchestrator wired to the hardware-free
     * [SimulatedNavigationProvider] for the Developer-screen demo. Streams
     * NavInstruction/RouteSummary frames through the active transport's nav channel
     * (`0xAF06`); in simulator mode `SimulatedDataSource` logs them (`NavSim` tag).
     * Swap the provider for a real routing-SDK adapter to go live.
     */
    private val navDemoNavigator: RouteNavigator by lazy {
        RouteNavigator(SimulatedNavigationProvider(), bluetoothDataSource, appScope)
    }

    /** Developer demo: play a scripted route → nav frames (watch logcat `NavSim`). */
    fun startNavDemo() {
        navDemoNavigator.startNavigation(GeoLocation(latitude = -6.2088, longitude = 106.8456))
    }

    /** Stop the nav demo and send a cancel frame. */
    fun stopNavDemo() {
        navDemoNavigator.cancelNavigation()
    }

    companion object {
        /** Synthetic address for the simulated session — ignored by SimulatedDataSource. */
        private const val SIMULATOR_SESSION_ADDRESS = "SIMULATOR"
    }
}
