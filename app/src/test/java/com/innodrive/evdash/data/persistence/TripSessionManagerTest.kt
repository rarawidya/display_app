package com.innodrive.evdash.data.persistence

import com.innodrive.evdash.data.persistence.dao.FaultEventDao
import com.innodrive.evdash.data.persistence.dao.TelemetryDao
import com.innodrive.evdash.data.persistence.dao.TripDao
import com.innodrive.evdash.data.persistence.entity.FaultEventEntity
import com.innodrive.evdash.data.persistence.entity.TelemetryEntity
import com.innodrive.evdash.data.persistence.entity.TripEntity
import com.innodrive.evdash.domain.model.VehicleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the auto-recording lifecycle: finalize on stop, trivial-trip
 * discard, and startup cleanup of trips a killed process left in-progress.
 * These back the "Last Ride reflects real rides" fix — the card reads the newest
 * completed trip, so finalize/discard correctness is what makes it accurate.
 */
class TripSessionManagerTest {

    private val tripDao = FakeTripDao()

    private fun manager() = TripSessionManager(
        tripDao = tripDao,
        telemetryDao = FakeTelemetryDao(),
        faultEventDao = FakeFaultEventDao(),
        telemetryLogger = TelemetryLogger(FakeTelemetryDao()),
        scope = CoroutineScope(Dispatchers.Unconfined)
    )

    private fun data(speed: Int, t: Long, battery: Int = 80) =
        VehicleData(speed = speed, batteryPercent = battery, timestamp = t)

    @Test
    fun `a real ride finalizes with distance and end time`() = runBlocking {
        val tsm = manager()
        tsm.startTrip(data(speed = 0, t = 0, battery = 80))
        // 36 km/h = 10 m/s for 10 s ≈ 95 m (trapezoidal from a standing start).
        for (t in 1000L..10_000L step 1000L) tsm.onTelemetryUpdate(data(speed = 36, t = t))
        tsm.stopTrip(data(speed = 36, t = 10_000L))

        assertFalse(tsm.isRecording)
        val trips = tripDao.getAll()
        assertEquals(1, trips.size)
        val trip = trips.first()
        assertNotNull("endTime must be set on finalize", trip.endTime)
        assertTrue("distance should exceed the discard floor", trip.distanceMeters > 50L)
        assertEquals(80, trip.startBattery)
    }

    @Test
    fun `a trivial roll is discarded rather than saved`() = runBlocking {
        val tsm = manager()
        tsm.startTrip(data(speed = 0, t = 0))
        // 10 km/h for 1 s ≈ 1.4 m — below MIN_RECORDED_TRIP_METERS.
        tsm.onTelemetryUpdate(data(speed = 10, t = 1000L))
        tsm.stopTrip(data(speed = 10, t = 1000L))

        assertFalse(tsm.isRecording)
        assertTrue("trivial trip must not persist", tripDao.getAll().isEmpty())
    }

    @Test
    fun `discardDanglingTrips drops in-progress rows but keeps completed ones`() = runBlocking {
        // A completed trip and a dangling (endTime null) one left by a crash.
        tripDao.insert(TripEntity(startTime = 1000L, endTime = 2000L, startBattery = 90))
        tripDao.insert(TripEntity(startTime = 3000L, endTime = null, startBattery = 88))

        manager().discardDanglingTrips()

        val remaining = tripDao.getAll()
        assertEquals(1, remaining.size)
        assertNotNull(remaining.first().endTime) // the completed one survives
    }

    @Test
    fun `discardDanglingTrips is a no-op when nothing is dangling`() = runBlocking {
        tripDao.insert(TripEntity(startTime = 1000L, endTime = 2000L, startBattery = 90))
        manager().discardDanglingTrips()
        assertEquals(1, tripDao.getAll().size)
    }
}

/* ----------------------------- in-memory fakes ---------------------------- */

private class FakeTripDao : TripDao {
    private val trips = linkedMapOf<Long, TripEntity>()
    private var nextId = 1L

    override suspend fun insert(trip: TripEntity): Long {
        val id = nextId++
        trips[id] = trip.copy(id = id)
        return id
    }

    override suspend fun update(trip: TripEntity) { trips[trip.id] = trip }
    override suspend fun getAll(): List<TripEntity> = trips.values.toList()
    override suspend fun getById(id: Long): TripEntity? = trips[id]
    override suspend fun getActiveTrip(): TripEntity? = trips.values.firstOrNull { it.endTime == null }
    override suspend fun deleteById(id: Long) { trips.remove(id) }
    override suspend fun deleteDangling(): Int {
        val ids = trips.values.filter { it.endTime == null }.map { it.id }
        ids.forEach { trips.remove(it) }
        return ids.size
    }
    override suspend fun deleteOlderThan(cutoffMs: Long): Int = 0
    override fun observeAll(): Flow<List<TripEntity>> = throw NotImplementedError()
}

private class FakeTelemetryDao : TelemetryDao {
    override suspend fun insertBatch(samples: List<TelemetryEntity>) { /* no-op */ }
    override suspend fun getRange(tripId: Long, startMs: Long, endMs: Long): List<TelemetryEntity> = emptyList()
    override suspend fun getByTrip(tripId: Long): List<TelemetryEntity> = emptyList()
    override fun observeByTrip(tripId: Long): Flow<List<TelemetryEntity>> = throw NotImplementedError()
    override suspend fun countByTrip(tripId: Long): Long = 0
    override suspend fun deleteOlderThan(cutoffMs: Long): Int = 0
    override suspend fun getOldestTimestamp(): Long? = null
    override suspend fun getDownsampled(tripId: Long, sampleEvery: Int): List<TelemetryEntity> = emptyList()
}

private class FakeFaultEventDao : FaultEventDao {
    override suspend fun insert(event: FaultEventEntity) { /* no-op */ }
    override suspend fun getByTrip(tripId: Long): List<FaultEventEntity> = emptyList()
    override suspend fun getRecent(limit: Int): List<FaultEventEntity> = emptyList()
    override fun observeAll(): Flow<List<FaultEventEntity>> = throw NotImplementedError()
    override suspend fun deleteOlderThan(cutoffMs: Long): Int = 0
}
