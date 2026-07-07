package com.example.displayapp.di

import android.content.Context
import android.content.pm.PackageManager
import com.example.displayapp.BuildConfig
import com.example.displayapp.data.bluetooth.BluetoothDataSource
import com.example.displayapp.data.bluetooth.SwitchableDataSource
import com.example.displayapp.data.bluetooth.ble.BleDataSource
import com.example.displayapp.data.bluetooth.controller.BleController
import com.example.displayapp.data.bluetooth.controller.BluetoothController
import com.example.displayapp.data.diagnostics.DiagnosticsRepository
import com.example.displayapp.data.energy.EfficiencyTracker
import com.example.displayapp.data.location.FusedLocationRepository
import com.example.displayapp.data.navigation.GraphHopperNavigationProvider
import com.example.displayapp.data.navigation.NavigationCoordinator
import com.example.displayapp.data.navigation.RouteNavigator
import com.example.displayapp.data.navigation.SimulatedNavigationProvider
import com.example.displayapp.data.navigation.graphhopper.GraphHopperGeocoder
import com.example.displayapp.data.navigation.graphhopper.GraphHopperRoutePlanner
import com.example.displayapp.domain.repository.Geocoder
import com.example.displayapp.domain.repository.NavigationProvider
import com.example.displayapp.domain.repository.RoutePlanner
import kotlinx.coroutines.flow.filterNotNull
import com.example.displayapp.data.notification.PhoneNotificationSender
import com.example.displayapp.data.permissions.PermissionStatusProvider
import com.example.displayapp.data.preferences.AppPreferences
import com.example.displayapp.data.preferences.DevicePreferences
import com.example.displayapp.data.preferences.ThemePreferences
import com.example.displayapp.data.persistence.RetentionPolicy
import com.example.displayapp.data.persistence.StorageInfoProvider
import com.example.displayapp.data.persistence.TelemetryDatabase
import com.example.displayapp.data.persistence.TelemetryLogger
import com.example.displayapp.data.persistence.TelemetryReplaySource
import com.example.displayapp.data.persistence.TripSessionManager
import com.example.displayapp.data.persistence.export.CsvExporter
import com.example.displayapp.data.protocol.TelemetryMapper
import com.example.displayapp.data.replay.RoomTripReplaySource
import com.example.displayapp.data.repository.AppPreferencesRepositoryImpl
import com.example.displayapp.data.repository.ThemeRepositoryImpl
import com.example.displayapp.data.repository.TripRepositoryImpl
import com.example.displayapp.data.repository.VehicleRepositoryImpl
import com.example.displayapp.data.system.WifiStateMonitor
import com.example.displayapp.data.simulator.SampleTripSeeder
import com.example.displayapp.data.simulator.SimulatedDataSource
import com.example.displayapp.data.simulator.TelemetryScenario
import com.example.displayapp.domain.replay.TripReplaySource
import com.example.displayapp.domain.repository.AppPreferencesRepository
import com.example.displayapp.domain.repository.LocationRepository
import com.example.displayapp.domain.repository.ThemeRepository
import com.example.displayapp.domain.repository.TripRepository
import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.repository.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    val locationRepository: LocationRepository by lazy {
        FusedLocationRepository(context)
    }

    /**
     * Active map renderer (MapLibre). Behind the renderer-neutral
     * [com.example.displayapp.presentation.ui.maps.MapProvider] interface so the map
     * SDK is swappable without touching any screen. Reads the tile style URL from
     * `BuildConfig.MAP_STYLE_URL` (local.properties) — blank → placeholder gate.
     */
    val mapProvider: com.example.displayapp.presentation.ui.maps.MapProvider by lazy {
        com.example.displayapp.presentation.ui.maps.MapLibreMapProvider(BuildConfig.MAP_STYLE_URL)
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
        NavigationCoordinator(navigationProvider, routeNavigator, appScope)
    }

    val tripRepository: TripRepository by lazy {
        TripRepositoryImpl(tripDao, telemetryDao)
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
        SampleTripSeeder(tripDao, telemetryDao)
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
     * always targets the live link; [com.example.displayapp.service.NotificationRelayService]
     * reads it via the app container.
     */
    val phoneNotificationSender: PhoneNotificationSender by lazy {
        PhoneNotificationSender(bluetoothDataSource, diagnosticsRepository)
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
    }

    /**
     * Start/stop a live **simulator session** from the Settings toggle.
     *
     * Selecting the simulator source alone leaves it idle (DISCONNECTED) — nothing
     * ever calls [VehicleRepository.connect] on it, unlike the real-device path which
     * goes through [com.example.displayapp.service.TelemetryService]. So turning the
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
     */
    fun restartDataSource() {
        switchableDataSource.swap(createDelegate())
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
