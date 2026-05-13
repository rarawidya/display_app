package com.example.displayapp.data.persistence

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration regression tests.
 *
 * Phase 1/2 audit invariant: every Room migration must be additive and
 * preserve every row of every prior version. `fallbackToDestructiveMigration`
 * is OFF in production — a buggy migration crashes startup instead of
 * silently erasing trip history, but that means a bug is a hard-stop. These
 * tests run each migration against a hand-built source schema and assert:
 *
 *   - the migration `migrate(...)` call doesn't throw,
 *   - the post-migration table has the expected new columns,
 *   - existing rows are preserved (id, values, and any column carried over),
 *   - default values render exactly as the UI expects ("—" sites read NULL
 *     for nullable columns and 0 for NOT NULL DEFAULT 0).
 *
 * The DDL strings below capture each schema version *as it was* at that
 * version — they're frozen snapshots. Don't update them when the live
 * entity classes change; that defeats the test. New schema versions add a
 * new V(n+1) DDL plus a new test that walks v(n) → v(n+1).
 */
@RunWith(AndroidJUnit4::class)
class TelemetryDatabaseMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val factory = FrameworkSQLiteOpenHelperFactory()
    private val createdDbNames = mutableListOf<String>()

    @After
    fun cleanup() {
        createdDbNames.forEach { context.deleteDatabase(it) }
    }

    // ── full chain 1 → 6 ────────────────────────────────────────────────────

    @Test
    fun fullChain_v1_to_v6_preservesRowsAndAppliesEveryMigration() {
        val name = "migration-chain-1-to-6.db"
        val helper = openWithDdl(name, V1_DDL)
        helper.writableDatabase.use { db ->
            // Seed v1-shaped data: one trip, one telemetry row, one fault.
            db.execSQL(
                "INSERT INTO trips (id, startTime, endTime, distanceMeters, " +
                    "maxSpeedKmh10, avgSpeedKmh10, startBattery, endBattery, sampleCount) " +
                    "VALUES (1, 1000, 2000, 1500, 450, 300, 80, 65, 100)"
            )
            db.execSQL(
                "INSERT INTO telemetry (id, tripId, timestamp, speed, battery, voltage, " +
                    "current, temperature, mode, odometer, indicators) " +
                    "VALUES (1, 1, 1500, 452, 75, 7250, 800, 38, 2, 12345, 0)"
            )
            db.execSQL(
                "INSERT INTO fault_events (id, tripId, timestamp, type, severity, message) " +
                    "VALUES (1, 1, 1500, 'TEST', 1, 'test fault')"
            )

            // Apply every migration in sequence.
            for (m in TelemetryDatabase.MIGRATIONS) {
                m.migrate(db)
            }

            // Trip survived all five migrations + carries v2/v5 defaults.
            db.query(
                "SELECT id, distanceMeters, energyUsedWh, energyRegenWh, " +
                    "avgPowerW100, maxPowerW100, peakMotorTempC FROM trips WHERE id = 1"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1L, c.getLong(0))
                assertEquals(1500L, c.getLong(1))
                // v2 defaults — pre-v2 trips read 0, UI renders "—"
                assertEquals(0.0, c.getDouble(2), 0.0)
                assertEquals(0.0, c.getDouble(3), 0.0)
                // v5 defaults
                assertEquals(0L, c.getLong(4))
                assertEquals(0L, c.getLong(5))
                assertEquals(0L, c.getLong(6))
            }

            // Telemetry row survived rebuild (3→4) and gained v3/v6 columns.
            db.query(
                "SELECT id, speed, batteryTemperature, controllerTemperature, " +
                    "rpm, powerW FROM telemetry WHERE id = 1"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1L, c.getLong(0))
                assertEquals(452L, c.getLong(1))   // wire scale preserved
                assertEquals(0L, c.getLong(2))     // v3 default
                assertEquals(0L, c.getLong(3))     // v3 default
                assertTrue("rpm should be NULL for legacy rows", c.isNull(4))
                assertTrue("powerW should be NULL for legacy rows", c.isNull(5))
            }

            // v3→v4 dropped odometer + indicators — querying them must fail now.
            assertColumnAbsent(db, "telemetry", "odometer")
            assertColumnAbsent(db, "telemetry", "indicators")

            // Fault row untouched (no migration ever altered fault_events).
            db.query("SELECT COUNT(*) FROM fault_events").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1L, c.getLong(0))
            }
        }
    }

    // ── v5 → v6 focused test (newest migration) ─────────────────────────────

    @Test
    fun migration_5_6_addsNullableRpmAndPowerColumns() {
        val name = "migration-5-6.db"
        val helper = openWithDdl(name, V5_DDL)
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO telemetry (id, tripId, timestamp, speed, battery, voltage, " +
                    "current, temperature, mode, batteryTemperature, controllerTemperature) " +
                    "VALUES (1, 1, 1500, 452, 75, 7250, 800, 38, 2, 26, 31)"
            )

            TelemetryDatabase.MIGRATION_5_6.migrate(db)

            // Both columns present, both nullable, both NULL on the legacy row.
            db.query("SELECT rpm, powerW FROM telemetry WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
                assertTrue(c.isNull(1))
            }

            // Writing new rows with rpm/powerW set must round-trip exactly.
            db.execSQL(
                "INSERT INTO telemetry (id, tripId, timestamp, speed, battery, voltage, " +
                    "current, temperature, mode, batteryTemperature, controllerTemperature, " +
                    "rpm, powerW) " +
                    "VALUES (2, 1, 2000, 500, 74, 7200, 850, 40, 2, 27, 32, 5000, 612.0)"
            )
            db.query("SELECT rpm, powerW FROM telemetry WHERE id = 2").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(5000L, c.getLong(0))
                assertEquals(612.0, c.getDouble(1), 0.001)
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun openWithDdl(name: String, ddl: List<String>): SupportSQLiteOpenHelper {
        context.deleteDatabase(name)
        createdDbNames += name
        val callback = object : SupportSQLiteOpenHelper.Callback(SCHEMA_VERSION_PLACEHOLDER) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                ddl.forEach { db.execSQL(it) }
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // No-op — we invoke each Migration.migrate(db) by hand from the test.
            }
        }
        return factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(callback)
                .build()
        )
    }

    private fun assertColumnAbsent(db: SupportSQLiteDatabase, table: String, column: String) {
        db.query("PRAGMA table_info($table)").use { c ->
            while (c.moveToNext()) {
                assertFalse(
                    "Column $column should have been dropped from $table",
                    c.getString(1) == column
                )
            }
        }
    }

    companion object {
        // The version arg on the Callback isn't used to drive our tests (we
        // call migrate() by hand) — pinned to a constant placeholder. Setting
        // it to the real source version isn't required.
        private const val SCHEMA_VERSION_PLACEHOLDER = 1

        // ── V1 schema — original tables before any migration. ────────────────
        // trips/telemetry/fault_events at v1: telemetry carries odometer +
        // indicators (dropped in v3→v4), trips has no energy or aggregate
        // columns, fault_events is unchanged through all migrations.
        private val V1_DDL = listOf(
            """
            CREATE TABLE trips (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startTime INTEGER NOT NULL,
                endTime INTEGER,
                distanceMeters INTEGER NOT NULL DEFAULT 0,
                maxSpeedKmh10 INTEGER NOT NULL DEFAULT 0,
                avgSpeedKmh10 INTEGER NOT NULL DEFAULT 0,
                startBattery INTEGER NOT NULL DEFAULT 0,
                endBattery INTEGER,
                sampleCount INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            """
            CREATE TABLE telemetry (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                tripId INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                speed INTEGER NOT NULL,
                battery INTEGER NOT NULL,
                voltage INTEGER NOT NULL,
                current INTEGER NOT NULL,
                temperature INTEGER NOT NULL,
                mode INTEGER NOT NULL,
                odometer INTEGER NOT NULL DEFAULT 0,
                indicators INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(tripId) REFERENCES trips(id) ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE INDEX index_telemetry_tripId_timestamp ON telemetry(tripId, timestamp)",
            "CREATE INDEX index_telemetry_timestamp ON telemetry(timestamp)",
            """
            CREATE TABLE fault_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                tripId INTEGER,
                timestamp INTEGER NOT NULL,
                type TEXT NOT NULL,
                severity INTEGER NOT NULL,
                message TEXT NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX index_fault_events_timestamp ON fault_events(timestamp)"
        )

        // ── V5 schema — telemetry at v5, post 4→5 (pre 5→6). ─────────────────
        // Adds v3's batteryTemperature/controllerTemperature, drops v4's
        // odometer/indicators, and trips has v5's aggregates.
        private val V5_DDL = listOf(
            """
            CREATE TABLE trips (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startTime INTEGER NOT NULL,
                endTime INTEGER,
                distanceMeters INTEGER NOT NULL DEFAULT 0,
                maxSpeedKmh10 INTEGER NOT NULL DEFAULT 0,
                avgSpeedKmh10 INTEGER NOT NULL DEFAULT 0,
                startBattery INTEGER NOT NULL DEFAULT 0,
                endBattery INTEGER,
                sampleCount INTEGER NOT NULL DEFAULT 0,
                energyUsedWh REAL NOT NULL DEFAULT 0,
                energyRegenWh REAL NOT NULL DEFAULT 0,
                avgPowerW100 INTEGER NOT NULL DEFAULT 0,
                maxPowerW100 INTEGER NOT NULL DEFAULT 0,
                peakMotorTempC INTEGER NOT NULL DEFAULT 0,
                peakBatteryTempC INTEGER NOT NULL DEFAULT 0,
                peakControllerTempC INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            """
            CREATE TABLE telemetry (
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
            """.trimIndent(),
            "CREATE INDEX index_telemetry_tripId_timestamp ON telemetry(tripId, timestamp)",
            "CREATE INDEX index_telemetry_timestamp ON telemetry(timestamp)",
            """
            CREATE TABLE fault_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                tripId INTEGER,
                timestamp INTEGER NOT NULL,
                type TEXT NOT NULL,
                severity INTEGER NOT NULL,
                message TEXT NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX index_fault_events_timestamp ON fault_events(timestamp)"
        )
    }
}
