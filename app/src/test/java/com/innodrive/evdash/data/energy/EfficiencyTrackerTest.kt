package com.innodrive.evdash.data.energy

import com.innodrive.evdash.domain.model.ConnectionState
import com.innodrive.evdash.domain.model.VehicleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the rolling Wh/km + range tracker.
 *
 * The tracker is flow-driven, so we back its scope with [Dispatchers.Unconfined]
 * — StateFlow updates then propagate to the internal collector synchronously on
 * the test thread, making assertions deterministic without a test dispatcher
 * dependency. All timing comes from sample timestamps (the class reads no clock).
 */
class EfficiencyTrackerTest {

    private val data = MutableStateFlow(VehicleData(timestamp = 1000L))
    private val conn = MutableStateFlow(ConnectionState.CONNECTED)

    private fun tracker(windowMs: Long = EfficiencyTracker.WINDOW_MS) = EfficiencyTracker(
        vehicleData = data,
        connectionState = conn,
        windowMs = windowMs,
        scope = CoroutineScope(Dispatchers.Unconfined)
    )

    private fun push(voltage: Float, current: Float, speed: Int, battery: Int, t: Long) {
        data.value = VehicleData(
            voltage = voltage, current = current, speed = speed,
            batteryPercent = battery, timestamp = t
        )
    }

    @Test
    fun `standing still yields null wh per km but live instant power`() {
        val t = tracker()
        push(voltage = 72f, current = 10f, speed = 0, battery = 50, t = 1000)
        push(voltage = 72f, current = 10f, speed = 0, battery = 50, t = 2000)
        val s = t.state.value
        assertNull(s.whPerKm)           // no distance in the window
        assertNull(s.rangeKm)
        assertEquals(720f, s.instantPowerW, 1e-2f)
        assertEquals(0f, s.regenPowerW, 0f)
    }

    @Test
    fun `constant drive computes wh per km as power over speed`() {
        val t = tracker()
        // 72 V × 10 A = 720 W at 36 km/h → 720 / 36 = 20 Wh/km.
        push(72f, 10f, speed = 36, battery = 50, t = 1000)
        push(72f, 10f, speed = 36, battery = 50, t = 2000)
        val s = t.state.value
        assertNotNull(s.whPerKm)
        assertEquals(20f, s.whPerKm!!, 1e-2f)
        // range = battery% × packKwh × 10 / whPerKm = 50 × 1.7 × 10 / 20 = 42.5 km.
        assertEquals(42.5f, s.rangeKm!!, 1e-1f)
    }

    @Test
    fun `first computed value is the raw value then EWMA blends`() {
        // Small window so old samples drop and raw changes cleanly between ticks.
        val t = tracker(windowMs = 1500)
        push(72f, 10f, speed = 36, battery = 50, t = 1000)
        push(72f, 10f, speed = 36, battery = 50, t = 2000) // raw = 20 → smoothed = 20 (first)
        assertEquals(20f, t.state.value.whPerKm!!, 1e-2f)
        // Window trims to {2000, 3000}; raw over them = avg(720,1440)/36 = 30.
        // EWMA: 0.15×30 + 0.85×20 = 21.5.
        push(72f, 20f, speed = 36, battery = 50, t = 3000)
        assertEquals(21.5f, t.state.value.whPerKm!!, 1e-2f)
    }

    @Test
    fun `regen shows as regen power not discharge`() {
        val t = tracker()
        push(voltage = 72f, current = -10f, speed = 0, battery = 50, t = 1000)
        val s = t.state.value
        assertEquals(0f, s.instantPowerW, 0f)
        assertEquals(720f, s.regenPowerW, 1e-2f)
    }

    @Test
    fun `disconnect resets the tracker`() {
        val t = tracker()
        push(72f, 10f, speed = 36, battery = 50, t = 1000)
        push(72f, 10f, speed = 36, battery = 50, t = 2000)
        assertNotNull(t.state.value.whPerKm)

        conn.value = ConnectionState.DISCONNECTED
        val s = t.state.value
        assertNull(s.whPerKm)
        assertNull(s.rangeKm)
        assertEquals(0f, s.instantPowerW, 0f)
        assertEquals(0f, s.regenPowerW, 0f)
    }
}
