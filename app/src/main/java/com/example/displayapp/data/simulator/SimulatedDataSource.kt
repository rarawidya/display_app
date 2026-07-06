package com.example.displayapp.data.simulator

import com.example.displayapp.data.bluetooth.BluetoothDataSource
import com.example.displayapp.data.protocol.FrameEncoder
import com.example.displayapp.data.protocol.TelemetrySchema
import com.example.displayapp.domain.model.BluetoothDeviceInfo
import com.example.displayapp.domain.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Simulated data source that generates properly framed Cap'n Proto telemetry packets.
 * Supports multiple [TelemetryScenario]s for testing different UI states.
 */
class SimulatedDataSource(
    private val scenario: TelemetryScenario = TelemetryScenario.CITY_CRUISE
) : BluetoothDataSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var emitJob: Job? = null
    private val scenarioGenerator = ScenarioGenerator(scenario)

    private val _incomingData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val incomingData: SharedFlow<ByteArray> = _incomingData.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    override val discoveredDevices: StateFlow<List<BluetoothDeviceInfo>> = _discoveredDevices.asStateFlow()

    private var tick = 0L
    private var bootTimeMs = 0L

    override fun startDiscovery() {
        _connectionState.value = ConnectionState.SCANNING
        scope.launch {
            delay(500)
            _discoveredDevices.value = listOf(
                BluetoothDeviceInfo("EV-Sim [${scenario.name}]", "00:11:22:33:44:55"),
                BluetoothDeviceInfo("EV-Controller-Mock", "AA:BB:CC:DD:EE:FF")
            )
            delay(1000)
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    override fun stopDiscovery() {
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun connect(address: String) {
        _connectionState.value = ConnectionState.CONNECTING
        delay(600)
        _connectionState.value = ConnectionState.CONNECTED
        bootTimeMs = System.currentTimeMillis()
        tick = 0L
        Timber.i("Simulator connected [scenario=${scenario.name}]")
        startEmitting()
    }

    override fun disconnect() {
        emitJob?.cancel()
        emitJob = null
        _connectionState.value = ConnectionState.DISCONNECTED
        Timber.i("Simulator disconnected")
    }

    override fun close() {
        disconnect()
        scope.cancel()
    }

    private fun startEmitting() {
        emitJob?.cancel()
        emitJob = scope.launch {
            while (true) {
                val frame = generateFrame()
                _incomingData.emit(frame)
                delay(50) // 20 Hz
            }
        }
    }

    private fun generateFrame(): ByteArray {
        tick++
        val sf = scenarioGenerator.generate(tick)

        val speedKmh = sf.speedKmh.toInt().coerceIn(0, 65535).toShort()
        // Board publishes speedKmh = rpm * 83 / 1000; invert so the wire pair stays
        // self-consistent (rpm ≈ speed * 12.05).
        val rpm = (sf.speedKmh * 1000.0 / 83.0).toInt().coerceIn(0, 65535).toShort()
        val batteryDeciVolts = (sf.voltageV * 10).toInt().coerceIn(0, 65535).toShort()
        // Real firmware sends motorCurrentRaw = 0 (uncalibrated). The simulator sends
        // whole-amp counts so the Drive power/current tiles show plausible motion.
        val motorCurrentRaw = sf.currentA.toInt().coerceIn(-32768, 32767).toShort()

        // flags: engineRunning while connected, moving when there's road speed,
        // brake when the pack is taking regen current (decelerating).
        var flags = 0x01
        if (sf.speedKmh > 0.5) flags = flags or 0x04
        if (sf.currentA < -1.0) flags = flags or 0x02

        val capnpPayload = TelemetrySchema.buildMessage {
            setBatteryDeciVolts(batteryDeciVolts)
            setMotorCurrentRaw(motorCurrentRaw)
            setRpm(rpm)
            setSpeedKmh(speedKmh)
            setControllerTempC(sf.controllerTempC.coerceIn(-128, 127).toByte())
            setMotorTempC(sf.tempC.coerceIn(-128, 127).toByte())
            setDriveMode(wireDriveMode(sf.mode))
            setFlags(flags.toByte())
            setFaultCode(0)
            setSeq((tick and 0xFFFFFFFFL).toInt())
            setBatteryPercent(sf.battery.coerceIn(0, 100).toByte())
        }

        return FrameEncoder.encode(capnpPayload)
    }

    /** VehicleMode ordinal (0-4) → wire driveMode (1 Eco / 2 Urban / 3 Sport). */
    private fun wireDriveMode(modeOrdinal: Int): Byte = when (modeOrdinal) {
        1 -> 1 // ECO
        3 -> 3 // SPORT
        else -> 2 // PARK / NORMAL / REGEN → Urban
    }
}
