package com.example.displayapp.data.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.displayapp.data.persistence.dao.FaultEventDao
import com.example.displayapp.data.persistence.dao.TelemetryDao
import com.example.displayapp.data.persistence.dao.TripDao
import com.example.displayapp.data.persistence.entity.FaultEventEntity
import com.example.displayapp.data.persistence.entity.TelemetryEntity
import com.example.displayapp.data.persistence.entity.TripEntity

@Database(
    entities = [
        TelemetryEntity::class,
        TripEntity::class,
        FaultEventEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TelemetryDatabase : RoomDatabase() {

    abstract fun telemetryDao(): TelemetryDao
    abstract fun tripDao(): TripDao
    abstract fun faultEventDao(): FaultEventDao

    companion object {
        @Volatile
        private var INSTANCE: TelemetryDatabase? = null

        fun getInstance(context: Context): TelemetryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): TelemetryDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                TelemetryDatabase::class.java,
                "ev_telemetry.db"
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
