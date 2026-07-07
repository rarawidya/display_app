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

    // Synthetic link RSSI so the Home "Bluetooth" stat shows a plausible dBm in demos.
    private val _rssi = MutableStateFlow<Int?>(null)
    override val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    private var tick = 0L
    private var bootTimeMs = 0L

    // Board-integrated odometer, in metres. Lifetime seeded to a plausible total;
    // trip accumulates from 0 per session. Doubles so low-speed sub-metre steps
    // don't truncate away before they add up.
    private var odoMetersAccum = 4_567_800.0
    private var tripMetersAccum = 0.0

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
        _rssi.value = -55
        Timber.i("Simulator connected [scenario=${scenario.name}]")
        startEmitting()
    }

    override fun disconnect() {
        emitJob?.cancel()
        emitJob = null
        _rssi.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
        Timber.i("Simulator disconnected")
    }

    /** No physical board — log the nav frame so the RouteNavigator path is demoable. */
    override suspend fun writeNav(frame: ByteArray, reliable: Boolean): Boolean {
        Timber.tag("NavSim").d("writeNav ${frame.size}B reliable=$reliable navType=0x%02X"
            .format(if (frame.size > 2) frame[2].toInt() and 0xFF else 0))
        return true
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
                // Drift the synthetic RSSI every ~2 s (40 frames) for a lifelike readout.
                if (tick % 40 == 0L) _rssi.value = -50 - ((tick / 40) % 20).toInt()
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
        // `currentMotor` is signed deci-amps on the wire (÷10 = A). Emit the scenario's
        // amps × 10 so the ÷10 decode lands back on the intended current.
        val currentMotor = (sf.currentA * 10).toInt().coerceIn(-32768, 32767).toShort()

        // flags: engineRunning while connected, moving when there's road speed,
        // brake when the pack is taking regen current (decelerating).
        var flags = 0x01
        if (sf.speedKmh > 0.5) flags = flags or 0x04
        if (sf.currentA < -1.0) flags = flags or 0x02

        // Integrate distance at the 20 Hz emit rate (50 ms/frame) so odometer +
        // trip climb realistically with speed.
        val stepMeters = sf.speedKmh / 3.6 * 0.05
        odoMetersAccum += stepMeters
        tripMetersAccum += stepMeters

        // Battery pack current (whole A, signed): positive = charging. Under regen
        // (negative motor current) the pack is charging, so flip the motor-side sign.
        val batteryCurrent = (-sf.currentA).toInt().coerceIn(-128, 127).toByte()
        // Pack runs cooler than the motor; loosely track it with a floor.
        val batteryTempC = (sf.tempC - 12).coerceIn(-128, 127).toByte()

        val capnpPayload = TelemetrySchema.buildMessage {
            setBatteryDeciVolts(batteryDeciVolts)
            setBatteryPercent(sf.battery.coerceIn(0, 100).toByte())
            setBatteryCurrent(batteryCurrent)
            setCurrentMotor(currentMotor)
            setRpm(rpm)
            setSpeedKmh(speedKmh)
            setControllerTempC(sf.controllerTempC.coerceIn(-128, 127).toByte())
            setMotorTempC(sf.tempC.coerceIn(-128, 127).toByte())
            setBatteryTempC(batteryTempC)
            setDriveMode(wireDriveMode(sf.mode))
            setFlags(flags.toByte())
            setFaultCode(0)
            setSeq((tick and 0xFFFFFFFFL).toInt())
            setOdoMeters(odoMetersAccum.toInt())
            setTripMeters(tripMetersAccum.toInt())
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
