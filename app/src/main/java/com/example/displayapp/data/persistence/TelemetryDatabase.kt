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
    version = 6,
    exportSchema = false
)
abstract class TelemetryDatabase : RoomDatabase() {

    abstract fun telemetryDao(): TelemetryDao
    abstract fun tripDao(): TripDao
    abstract fun faultEventDao(): FaultEventDao

    companion object {
        // The migration list is referenced by name from
        // app/src/androidTest/... TelemetryDatabaseMigrationTest. Surfaced as
        // `internal` so a regression in `migrate()` shows up in CI before it
        // ever runs against a user's on-device data.

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
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
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
         * v2 → v3: real per-channel temperatures.
         *
         * Pre-v3 samples have a single motor `temperature` field plus heuristic
         * battery/controller offsets computed in the ViewModel. v3 splits them
         * into independent wire-sourced channels. Old rows default to 0 °C for
         * both new columns; the UI surfaces those as 0 / "—" rather than
         * fabricating heuristics.
         */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE telemetry ADD COLUMN batteryTemperature INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE telemetry ADD COLUMN controllerTemperature INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v3 → v4: retire `odometer` and `indicators` columns.
         *
         * The canonical telemetry model no longer exposes either field —
         * distance is integrated from speed × dt in the analytics layer, and
         * blinker / headlamp state is no longer surfaced anywhere in the UI.
         *
         * SQLite before 3.35 (Android < API 31) can't `DROP COLUMN`, so we
         * rebuild the table: CREATE new, INSERT carrying over the kept
         * columns, DROP old, RENAME, recreate indices. The new table keeps
         * the same id space so foreign keys from elsewhere stay valid.
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE telemetry_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        tripId INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        speed INTEGER NOT NULL,
                        battery INTEGER NOT NULL,
                        voltage INTEGER NOT NULL,
                        current INTEGER NOT NULL,
                        temperature INTEGER NOT NULL,
                        mode INTEGER NOT NULL,
                        batteryTemperature INTEGER NOT NULL DEFAULT 0,
                        controllerTemperature INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(tripId) REFERENCES trips(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO telemetry_new (
                        id, tripId, timestamp, speed, battery, voltage, current,
                        temperature, mode, batteryTemperature, controllerTemperature
                    )
                    SELECT id, tripId, timestamp, speed, battery, voltage, current,
                           temperature, mode, batteryTemperature, controllerTemperature
                    FROM telemetry
                """.trimIndent())
                db.execSQL("DROP TABLE telemetry")
                db.execSQL("ALTER TABLE telemetry_new RENAME TO telemetry")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_telemetry_tripId_timestamp ON telemetry(tripId, timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_telemetry_timestamp ON telemetry(timestamp)")
            }
        }

        /**
         * v4 → v5: per-trip analytics aggregates.
         *
         * Adds avg/max power and peak temps to `trips` so Logs row cards and
         * Trip Detail can surface them without a per-row scan of telemetry.
         * Pre-v5 trips default to 0; UI renders "—" for those.
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN avgPowerW100 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trips ADD COLUMN maxPowerW100 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trips ADD COLUMN peakMotorTempC INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trips ADD COLUMN peakBatteryTempC INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trips ADD COLUMN peakControllerTempC INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5 → v6: persist per-sample derived fields (rpm, power).
         *
         * rpm and power are derived in TelemetryDerivations; live samples will
         * carry the value computed at decode time. Persisting them means a
         * future change to either formula (e.g. real motor-RPM wire channel,
         * or a different power model) doesn't retroactively change historical
         * trips — replay / CSV / Trip Detail read the value that was
         * recorded.
         *
         * Both columns are nullable; pre-v6 rows carry NULL and
         * TelemetryDerivations.decodeEntity falls back to recomputing them
         * from the wire fields. That preserves historical chart shapes for
         * old trips without bloating storage with backfilled derivations.
         */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE telemetry ADD COLUMN rpm INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE telemetry ADD COLUMN powerW REAL DEFAULT NULL")
            }
        }

        /**
         * All migrations live in this list. Adding a new one is append-only —
         * future SOH / fault-watchdog / eco-score work will declare
         * `Migration(6, 7)` above and append it here.
         */
        internal val MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6
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
