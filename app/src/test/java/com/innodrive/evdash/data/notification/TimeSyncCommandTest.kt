package com.innodrive.evdash.data.notification

import com.innodrive.evdash.data.protocol.FrameEncoder
import com.innodrive.evdash.data.protocol.PhoneNotificationSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TIME_SYNC` (docs/TIME-SYNC-INTEGRATION.md) rides the frozen control contract —
 * category 32, appName "EVD", command in title, `epoch`/`tz_offset_min` key=value
 * lines in body — and must fit one acknowledged GATT write.
 */
class TimeSyncCommandTest {

    @Test
    fun `builds the control command with epoch and signed tz offset in the body`() {
        // Doc example: WIB 2026-07-15 18:30:00 local = 11:30:00Z, UTC+7 (420 min).
        val cmd = TimeSyncCommand.build(epochSeconds = 1784219400L, tzOffsetMinutes = 420)

        assertEquals(TimeSyncCommand.CMD_TIME_SYNC, cmd.title)
        assertEquals(PhoneNotificationSchema.CATEGORY_CONTROL, cmd.category)
        assertEquals("EVD", cmd.appName)
        assertEquals(0, cmd.id)
        assertEquals("epoch=1784219400\ntz_offset_min=420", cmd.body)
    }

    @Test
    fun `negative tz offset (west of UTC) is signed`() {
        val cmd = TimeSyncCommand.build(epochSeconds = 1784219400L, tzOffsetMinutes = -300)
        assertEquals("epoch=1784219400\ntz_offset_min=-300", cmd.body)
    }

    @Test
    fun `frame fits one GATT write`() {
        val frame = FrameEncoder.encode(
            PhoneNotificationSchema.encode(
                TimeSyncCommand.build(epochSeconds = 1784219400L, tzOffsetMinutes = 420),
            ),
        )
        assertTrue("frame is ${frame.size} B", frame.size <= 244)
    }
}
