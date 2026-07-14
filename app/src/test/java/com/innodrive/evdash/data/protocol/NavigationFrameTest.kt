package com.innodrive.evdash.data.protocol

import com.innodrive.evdash.domain.model.Maneuver
import com.innodrive.evdash.domain.model.NavState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-exact self-tests for [NavigationSchema]. The golden frames were produced by
 * the **reference Cap'n Proto compiler** (`capnp encode navigation.capnp …`, v1.0.1)
 * for the payload, then wrapped in the shared `[0xAA][LEN][navType‖capnp][CRC16-LE]`
 * frame. So these assert the hand-built writer matches the canonical wire layout the
 * board's generated decoder expects — the same guarantee `PhoneNotificationFrameTest`
 * gives for the notification path.
 */
class NavigationFrameTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // capnp encode navigation.capnp NavInstruction '(routeId=287454020,
    //   distanceRemainingM=5300, etaSeconds=840, seq=7, state=navigating,
    //   maneuver=turnLeft, distanceToTurnM=200, nextManeuver=turnRight,
    //   nextDistanceM=45, roundaboutExit=0, speedLimitKmh=50, streetName="Jl. Sudirman")'
    private val navInstructionGolden = hex(
        "aa49020000000008000000000000000400010044332211b414000048030000" +
            "070001000400c80007002d000032000000000000010000006a0000004a6c2e20" +
            "53756469726d616e00000000b514"
    )

    // capnp encode navigation.capnp RouteSummary '(routeId=287454020,
    //   totalDistanceM=5300, totalDurationSec=840, maneuverCount=12,
    //   polylineChunks=0, schemaVersion=1, destinationName="Kantor")'
    private val routeSummaryGolden = hex(
        "aa31010000000005000000000000000200010044332211b4140000480300000c" +
            "000001010000003a0000004b616e746f720000f3e1"
    )

    // Arrived, empty streetName → exercises the empty-Text (1-byte NUL list) path.
    private val navArrivedGolden = hex(
        "aa4102000000000700000000000000040001004433221100000000000000002a" +
            "00040011000000000000000000000000000000010000000a000000000000000000" +
            "00008d13"
    )

    @Test
    fun navInstruction_matches_reference_compiler_frame() {
        val frame = NavigationSchema.frameNavInstruction(
            NavigationSchema.NavInstruction(
                routeId = 287454020L,
                distanceRemainingM = 5300L,
                etaSeconds = 840L,
                seq = 7,
                state = NavState.Navigating.wire,
                maneuver = Maneuver.TurnLeft.wire,
                distanceToTurnM = 200,
                nextManeuver = Maneuver.TurnRight.wire,
                nextDistanceM = 45,
                roundaboutExit = 0,
                speedLimitKmh = 50,
                streetName = "Jl. Sudirman",
            ),
        )
        assertEquals(navInstructionGolden.toList(), frame.toList())
    }

    @Test
    fun routeSummary_matches_reference_compiler_frame() {
        val frame = NavigationSchema.frameRouteSummary(
            NavigationSchema.RouteSummary(
                routeId = 287454020L,
                totalDistanceM = 5300L,
                totalDurationSec = 840L,
                maneuverCount = 12,
                polylineChunks = 0,
                schemaVersion = 1,
                destinationName = "Kantor",
            ),
        )
        assertEquals(routeSummaryGolden.toList(), frame.toList())
    }

    @Test
    fun navInstruction_empty_street_matches_reference() {
        val frame = NavigationSchema.frameNavInstruction(
            NavigationSchema.NavInstruction(
                routeId = 287454020L,
                distanceRemainingM = 0L,
                etaSeconds = 0L,
                seq = 42,
                state = NavState.Arrived.wire,
                maneuver = Maneuver.Arrive.wire,
                distanceToTurnM = 0,
                nextManeuver = Maneuver.None.wire,
                nextDistanceM = 0,
                roundaboutExit = 0,
                speedLimitKmh = 0,
                streetName = "",
            ),
        )
        assertEquals(navArrivedGolden.toList(), frame.toList())
    }

    // capnp encode navigation.capnp RouteChunk '(routeId=287454020, anchorLatE7=-72659000,
    //   anchorLonE7=1127521000, index=0, total=2, deltas=[100,-50,110,-40])'
    private val routeChunkGolden = hex(
        "aa31030000000005000000000000000200010044332211c84fabfbe89a3443" +
            "0002000001000000230000006400ceff6e00d8ff4df1"
    )

    @Test
    fun routeChunk_matches_reference_compiler_frame() {
        val frame = NavigationSchema.frameRouteChunk(
            NavigationSchema.RouteChunk(
                routeId = 287454020L,
                anchorLatE7 = -72659000,
                anchorLonE7 = 1127521000,
                index = 0,
                total = 2,
                deltas = shortArrayOf(100, -50, 110, -40),
            ),
        )
        assertEquals(routeChunkGolden.toList(), frame.toList())
    }

    @Test
    fun frame_structure_and_crc_are_consistent() {
        val frame = NavigationSchema.frameNavInstruction(
            NavigationSchema.NavInstruction(
                routeId = 287454020L, distanceRemainingM = 5300L, etaSeconds = 840L,
                seq = 7, state = NavState.Navigating.wire, maneuver = Maneuver.TurnLeft.wire,
                distanceToTurnM = 200, nextManeuver = Maneuver.TurnRight.wire, nextDistanceM = 45,
                roundaboutExit = 0, speedLimitKmh = 50, streetName = "Jl. Sudirman",
            ),
        )
        assertEquals("sync byte", 0xAA, frame[0].toInt() and 0xFF)
        val len = frame[1].toInt() and 0xFF
        assertEquals("frame = SYNC + LEN + payload + CRC", len + 4, frame.size)
        assertEquals("navType is the first payload byte",
            NavigationSchema.NAV_TYPE_NAV_INSTRUCTION, frame[2].toInt() and 0xFF)

        // CRC16-CCITT over LEN ‖ payload, little-endian.
        val lenAndPayload = frame.copyOfRange(1, 2 + len)
        val expectedCrc = Crc16.compute(lenAndPayload)
        val wireCrc = (frame[frame.size - 2].toInt() and 0xFF) or
            ((frame[frame.size - 1].toInt() and 0xFF) shl 8)
        assertEquals(expectedCrc, wireCrc)
    }
}
