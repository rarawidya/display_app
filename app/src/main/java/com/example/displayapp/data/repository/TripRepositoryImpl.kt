package com.example.displayapp.data.repository

import com.example.displayapp.data.persistence.dao.FaultEventDao
import com.example.displayapp.data.persistence.dao.TelemetryDao
import com.example.displayapp.data.persistence.dao.TripDao
import com.example.displayapp.data.persistence.entity.FaultEventEntity
import com.example.displayapp.data.persistence.entity.TelemetryEntity
import com.example.displayapp.data.persistence.entity.TripEntity
import com.example.displayapp.domain.repository.TripRepository
import kotlinx.coroutines.flow.Flow

class TripRepositoryImpl(
    private val tripDao: TripDao,
    private val telemetryDao: TelemetryDao,
    private val faultEventDao: FaultEventDao
) : TripRepository {

    override fun observeAllTrips(): Flow<List<TripEntity>> = tripDao.observeAll()

    override suspend fun getTrip(tripId: Long): TripEntity? = tripDao.getById(tripId)

    override suspend fun getTripTelemetry(tripId: Long): List<TelemetryEntity> =
        telemetryDao.getByTrip(tripId)

    override suspend fun getDownsampledTelemetry(
        tripId: Long,
        sampleEvery: Int
    ): List<TelemetryEntity> = telemetryDao.getDownsampled(tripId, sampleEvery.coerceAtLeast(1))

    override suspend fun getTripFaults(tripId: Long): List<FaultEventEntity> =
        faultEventDao.getByTrip(tripId)

    override suspend fun deleteTrip(tripId: Long) {
        // Telemetry rows are removed by the ForeignKey CASCADE on TripEntity.id.
        tripDao.deleteById(tripId)
    }
}
