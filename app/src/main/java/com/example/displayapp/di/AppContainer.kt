package com.example.displayapp.di

import android.content.Context
import com.example.displayapp.data.bluetooth.BluetoothDataSource
import com.example.displayapp.data.preferences.DevicePreferences
import com.example.displayapp.data.preferences.ThemePreferences
import com.example.displayapp.data.bluetooth.SppDataSource
import com.example.displayapp.data.persistence.RetentionPolicy
import com.example.displayapp.data.persistence.TelemetryDatabase
import com.example.displayapp.data.persistence.TelemetryLogger
import com.example.displayapp.data.persistence.TelemetryReplaySource
import com.example.displayapp.data.persistence.TripSessionManager
import com.example.displayapp.data.persistence.export.CsvExporter
import com.example.displayapp.data.protocol.TelemetryMapper
import com.example.displayapp.data.repository.ThemeRepositoryImpl
import com.example.displayapp.data.repository.VehicleRepositoryImpl
import com.example.displayapp.data.system.WifiStateMonitor
import com.example.displayapp.data.simulator.SimulatedDataSource
import com.example.displayapp.data.simulator.TelemetryScenario
import com.example.displayapp.domain.repository.ThemeRepository
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

    val wifiStateMonitor: WifiStateMonitor by lazy {
        WifiStateMonitor(context)
    }

    // Database
    private val database: TelemetryDatabase by lazy {
        TelemetryDatabase.getInstance(context)
    }

    // DAOs
    private val telemetryDao by lazy { database.telemetryDao() }
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
}
