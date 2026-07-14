package com.innodrive.evdash.data.navigation

import com.innodrive.evdash.domain.model.GeoLocation
import com.innodrive.evdash.domain.model.Maneuver
import com.innodrive.evdash.domain.model.NavState
import com.innodrive.evdash.domain.model.RouteManeuver
import com.innodrive.evdash.domain.model.RoutePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the provider-independent [RouteProgressTracker] with a synthetic plan (no
 * GraphHopper, no network). A straight ~334 m route along the equator: vertices at
 * lng 0/0.001/0.002/0.003 (≈111 m apart); turn-left at vertex 2, arrive at vertex 3.
 */
class RouteProgressTrackerTest {

    private fun geo(lat: Double, lng: Double) = GeoLocation(latitude = lat, longitude = lng)

    private val plan = RoutePlan(
        origin = geo(0.0, 0.0),
        destination = geo(0.0, 0.003),
        polyline = listOf(geo(0.0, 0.0), geo(0.0, 0.001), geo(0.0, 0.002), geo(0.0, 0.003)),
        maneuvers = listOf(
            RouteManeuver(Maneuver.Depart, geo(0.0, 0.0), "Jl. Sudirman", 222, polylineIndex = 0),
            RouteManeuver(Maneuver.TurnLeft, geo(0.0, 0.002), "Jl. Thamrin", 111, polylineIndex = 2),
            RouteManeuver(Maneuver.Arrive, geo(0.0, 0.003), "", 0, polylineIndex = 3),
        ),
        distanceMeters = 334,
        durationSeconds = 60,
        destinationName = "Kantor",
    )

    private val tracker = RouteProgressTracker(plan, routeId = 7L)

    @Test
    fun near_start_targets_the_upcoming_turn() {
        val p = tracker.onLocation(geo(0.0, 0.0001)) // ~11 m along
        assertEquals(NavState.Navigating, p.state)
        assertEquals(Maneuver.TurnLeft, p.maneuver)          // next real turn
        assertEquals("Jl. Thamrin", p.streetName)
        assertEquals(Maneuver.Arrive, p.nextManeuver)        // look-ahead
        assertEquals(7L, p.routeId)
        assertTrue("distanceToTurn ~211 m", p.distanceToTurnM in 180..235)
        assertTrue("remaining ~323 m", p.distanceRemainingM in 300..335)
    }

    @Test
    fun countdown_decreases_as_we_approach_the_turn() {
        val far = tracker.onLocation(geo(0.0, 0.0001)).distanceToTurnM
        val near = tracker.onLocation(geo(0.0, 0.0019)).distanceToTurnM // just before the turn
        assertTrue("countdown shrinks ($near < $far)", near < far)
    }

    @Test
    fun reaching_the_destination_is_arrived() {
        val p = tracker.onLocation(geo(0.0, 0.003))
        assertEquals(NavState.Arrived, p.state)
        assertEquals(Maneuver.Arrive, p.maneuver)
        assertTrue(p.distanceRemainingM <= RouteProgressTracker.ARRIVE_RADIUS_M.toInt())
    }

    @Test
    fun deviating_from_the_route_is_off_route() {
        val p = tracker.onLocation(geo(0.001, 0.001)) // ~111 m off the line
        assertEquals(NavState.OffRoute, p.state)
    }
}
