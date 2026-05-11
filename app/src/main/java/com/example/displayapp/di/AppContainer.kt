package com.example.displayapp.di

import android.content.Context
import android.content.pm.PackageManager
import com.example.displayapp.data.bluetooth.BluetoothDataSource
import com.example.displayapp.data.diagnostics.DiagnosticsRepository
import com.example.displayapp.data.location.FusedLocationRepository
import com.example.displayapp.data.permissions.PermissionStatusProvider
import com.example.displayapp.data.preferences.AppPreferences
import com.example.displayapp.data.preferences.DevicePreferences
import com.example.displayapp.data.preferences.ThemePreferences
import com.example.displayapp.data.bluetooth.SppDataSource
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
import com.example.displayapp.data.simulator.SimulatedDataSource
import com.example.displayapp.data.simulator.TelemetryScenario
import com.example.displayapp.domain.replay.TripReplaySource
import com.example.displayapp.domain.repository.AppPreferencesRepository
import com.example.displayapp.domain.repository.LocationRepository
import com.example.displayapp.domain.repository.ThemeRepository
import com.example.displayapp.domain.repository.TripRepository
import com.example.displayapp.domain.repository.VehicleRepository

class AppContainer(private val context: Context) {

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

    // Bluetooth data source
    private var _dataSource: BluetoothDataSource? = null

    val bluetoothDataSource: BluetoothDataSource
        get() {
            if (_dataSource == null) {
                _dataSource = if (useSimulator) {
                    SimulatedDataSource(simulatorScenario)
                } else {
                    SppDataSource(context)
                }
            }
            return _dataSource!!
        }

    val telemetryMapper: TelemetryMapper by lazy { TelemetryMapper() }

    val vehicleRepository: VehicleRepository by lazy {
        VehicleRepositoryImpl(bluetoothDataSource, telemetryMapper, tripSessionManager)
    }

    fun switchDataSource(simulator: Boolean) {
        if (simulator == useSimulator && _dataSource != null) return
        _dataSource?.close()
        _dataSource = null
        useSimulator = simulator
    }

    /**
     * Force-restart the current data source — used when the simulator scenario
     * changes and the running SimulatedDataSource needs to be replaced.
     */
    fun restartDataSource() {
        _dataSource?.close()
        _dataSource = null
        // Next read of bluetoothDataSource lazy-creates with the current useSimulator + simulatorScenario.
    }
}
