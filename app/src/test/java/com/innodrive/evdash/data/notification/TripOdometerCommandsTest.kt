package com.innodrive.evdash.data.notification

import com.innodrive.evdash.data.protocol.FrameEncoder
import com.innodrive.evdash.data.protocol.PhoneNotificationSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trip-odometer reset commands (docs/capnpble_new.md §5b) ride the frozen control
 * contract — category 32, appName "EVD", command in title — and must send the current
 * per-trip titles (`ODO_RESET_TRIP_A` / `ODO_RESET_TRIP_B`), not the deprecated legacy
 * `ODO_RESET_TRIP`.
 */
class TripOdometerCommandsTest {

    @Test
    fun `reset trip A is the per-trip control command`() {
        val cmd = TripOdometerCommands.resetTripA()
        assertEquals("ODO_RESET_TRIP_A", cmd.title)
        assertEquals(PhoneNotificationSchema.CATEGORY_CONTROL, cmd.category)
        assertEquals("EVD", cmd.appName)
        assertEquals(0, cmd.id)
        assertEquals("", cmd.body)
    }

    @Test
    fun `reset trip B is the per-trip control command`() {
        val cmd = TripOdometerCommands.resetTripB()
        assertEquals("ODO_RESET_TRIP_B", cmd.title)
        assertEquals(PhoneNotificationSchema.CATEGORY_CONTROL, cmd.category)
        assertEquals("EVD", cmd.appName)
        assertEquals(0, cmd.id)
        assertEquals("", cmd.body)
    }

    @Test
    fun `reset frames fit one GATT write`() {
        listOf(TripOdometerCommands.resetTripA(), TripOdometerCommands.resetTripB()).forEach {
            val frame = FrameEncoder.encode(PhoneNotificationSchema.encode(it))
            assertTrue("frame ${it.title} is ${frame.size} B", frame.size <= 244)
        }
    }
}
