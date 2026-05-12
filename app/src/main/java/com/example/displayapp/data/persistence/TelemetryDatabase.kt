package com.example.displayapp.data.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
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

        /**
         * v1 → v2: per-trip real energy accounting.
         *
         * Pre-v2 trips will read energyUsedWh = energyRegenWh = 0; the UI
         * surfaces those as "—" so the absence of data is honest rather than
         * the old heuristic's fake precision.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE trips ADD COLUMN energyUsedWh REAL NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE trips ADD COLUMN energyRegenWh REAL NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * All migrations live in this list. Adding a new one is append-only —
         * future SOH / fault-watchdog / eco-score work will declare
         * `Migration(2, 3)` above and add it to this array.
         */
        private val MIGRATIONS = arrayOf(
            MIGRATION_1_2
        )

        private fun buildDatabase(context: Context): TelemetryDatabase {
            // No fallback to destructive migration — we want to preserve trip
            // history across schema bumps. Real migrations declared above.
            return Room.databaseBuilder(
                context.applicationContext,
                TelemetryDatabase::class.java,
                "ev_telemetry.db"
            )
                .addMigrations(*MIGRATIONS)
                .build()
        }
    }
}
