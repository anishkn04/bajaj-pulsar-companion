package dev.pulsarconnect.app.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import dev.pulsarconnect.core.model.BikeProfile
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.model.TelemetryPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AndroidDatabaseTest {
    private lateinit var context: Context
    private lateinit var db: AndroidDatabase
    private var now = 10_000L
    private val name = "synthetic-persistence-test.db"
    private val bike = BikeProfile("DEV-BIKE-A", "Synthetic model", "https://example.invalid/bike.png", "Test bike", 0, 0)
    private val other = bike.copy(vin = "DEV-BIKE-B", nickname = null)

    @Before fun setUp() = runBlocking<Unit> {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(name)
        db = AndroidDatabase(context, name) { now }
        db.bikes.upsertBike(bike)
        db.bikes.upsertBike(other)
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    private fun reopen() {
        db.close()
        db = AndroidDatabase(context, name) { now }
    }

    private fun point(timestamp: Long = 0, zero: Boolean = false) = TelemetryPoint(
        tripId = -99, timestamp = timestamp, speedKmph = if (zero) 0.0 else null,
        rpm = if (zero) 0 else null, fuelLevelPercent = if (zero) 0 else null,
        engineTempC = if (zero) 0 else null, lat = if (zero) 0.0 else null,
        lon = if (zero) 0.0 else null, synced = zero,
    )

    private fun reminder(vin: String = bike.vin, odometer: Double? = null, date: Long? = null) = ServiceReminder(
        vin = vin, title = "Synthetic service", description = "Development fixture only",
        dueOdometerKm = odometer, dueDateMillis = date, createdAtMillis = 0,
    )

    private suspend fun rejects(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected repository contract failure.
        }
    }

    @Test fun closeAndReopenPreservesAllEntitiesAndActiveTrip() = runBlocking {
        val trip = db.trips.startTrip(bike.vin, 123.5, 12.25, -45.5)
        val sample = point(17, true).copy(speedKmph = 32.5, rpm = 2500, fuelLevelPercent = 65, engineTempC = 80)
        db.trips.addTelemetry(trip.id!!, sample)
        val savedReminder = db.reminders.addReminder(reminder(odometer = 500.0, date = 50_000))
        db.bikes.updateLastSynced(bike.vin, 1234)
        reopen()
        assertEquals(bike.copy(lastSyncedAtMillis = 1234), db.bikes.getBike(bike.vin))
        assertEquals(trip, db.trips.getTrip(trip.id!!))
        assertEquals(listOf(trip), db.trips.getTrips(bike.vin, 10, 0))
        assertEquals(sample.copy(id = db.trips.getTelemetry(trip.id!!).single().id, tripId = trip.id!!), db.trips.getTelemetry(trip.id!!).single())
        assertEquals(listOf(savedReminder), db.reminders.getReminders(bike.vin))
        rejects { db.trips.startTrip(bike.vin, 124.0, null, null) }
        now += 3_600_000
        val ended = db.trips.endTrip(trip, 133.5, -1.5, 2.25)
        reopen()
        assertEquals(ended, db.trips.getTrip(trip.id!!))
        assertEquals(10.0, ended.distanceKm!!, 0.0)
        assertEquals(10.0, ended.avgSpeedKmph!!, 0.0)
        assertEquals(-1.5, ended.endLat!!, 0.0)
        assertEquals(2.25, ended.endLon!!, 0.0)
    }

    @Test fun tripAndTelemetryDistinguishNullFromZero() = runBlocking {
        now = 0
        val trip = db.trips.startTrip(bike.vin, 0.0, 0.0, null)
        db.trips.addTelemetry(trip.id!!, point())
        db.trips.addTelemetry(trip.id!!, point(zero = true))
        reopen()
        assertEquals(trip, db.trips.getTrip(trip.id!!))
        val points = db.trips.getTelemetry(trip.id!!)
        assertEquals(point().copy(id = points[0].id, tripId = trip.id!!), points[0])
        assertEquals(point(zero = true).copy(id = points[1].id, tripId = trip.id!!), points[1])
        val ended = db.trips.endTrip(trip, 0.0, 0.0, 0.0)
        reopen()
        assertEquals(ended, db.trips.getTrip(trip.id!!))
        assertEquals(0L, ended.endTime)
        assertEquals(0.0, ended.endOdometer!!, 0.0)
        assertEquals(0.0, ended.distanceKm!!, 0.0)
        assertEquals(0.0, ended.avgSpeedKmph!!, 0.0)
        assertNull(ended.maxSpeedKmph)
        assertNull(ended.fuelUsedLiters)
        assertFalse(ended.synced)
    }

    @Test fun multiBikeIsolationAndDeterministicPagination() = runBlocking {
        val first = db.trips.startTrip(bike.vin, 0.0, null, null)
        val separate = db.trips.startTrip(other.vin, 0.0, null, null)
        db.trips.endTrip(first, 0.0, null, null)
        val second = db.trips.startTrip(bike.vin, 0.0, null, null)
        db.trips.endTrip(second, 0.0, null, null)
        now++
        val third = db.trips.startTrip(bike.vin, 0.0, null, null)
        assertEquals(listOf(third, db.trips.getTrip(second.id!!)), db.trips.getTrips(bike.vin, 2, 0))
        assertEquals(listOf(db.trips.getTrip(first.id!!)), db.trips.getTrips(bike.vin, 2, 2))
        assertEquals(listOf(separate), db.trips.getTrips(other.vin, 10, 0))
        assertTrue(db.trips.getTrips(bike.vin, 0, 0).isEmpty())
        assertTrue(db.trips.getTrips(bike.vin, 5, 99).isEmpty())
        rejects { db.trips.getTrips(bike.vin, -1, 0) }
        rejects { db.trips.getTrips(bike.vin, 1, -1) }
    }

    @Test fun activeTripLookupIgnoresFutureHistoryAndIsVinScoped() = runBlocking {
        repeat(26) { index ->
            now = 1_000_000L + index * 1_000L
            val trip = db.trips.startTrip(bike.vin, 100.0, null, null)
            db.trips.endTrip(trip, 100.0, null, null)
        }
        assertNull(db.trips.getActiveTrip(bike.vin))

        now = 10_000L
        val active = db.trips.startTrip(bike.vin, 100.0, null, null)
        assertNull(db.trips.getActiveTrip(other.vin))
        val otherActive = db.trips.startTrip(other.vin, 0.0, null, null)
        val firstPage = db.trips.getTrips(bike.vin, 20, 0)
        assertEquals(20, firstPage.size)
        assertTrue(firstPage.all { it.endTime != null && it.startTime > active.startTime })
        assertEquals(listOf(active), db.trips.getTrips(bike.vin, 20, 26))

        reopen()
        assertEquals(active, db.trips.getActiveTrip(bike.vin))
        assertEquals(otherActive, db.trips.getActiveTrip(other.vin))
        assertNull(db.trips.getActiveTrip("DEV-MISSING"))

        db.trips.endTrip(active, 100.0, null, null)
        assertNull(db.trips.getActiveTrip(bike.vin))
        assertEquals(otherActive, db.trips.getActiveTrip(other.vin))
        db.trips.endTrip(otherActive, 0.0, null, null)
        assertNull(db.trips.getActiveTrip(other.vin))
    }

    @Test fun requiresParentsAndAuthoritativeTelemetryTripId() = runBlocking {
        rejects { db.trips.startTrip("DEV-MISSING", 0.0, null, null) }
        rejects { db.trips.addTelemetry(999, point()) }
        rejects { db.reminders.addReminder(reminder(vin = "DEV-MISSING")) }
        val trip = db.trips.startTrip(bike.vin, 0.0, null, null)
        val otherTrip = db.trips.startTrip(other.vin, 0.0, null, null)
        db.trips.addTelemetry(trip.id!!, point(30).copy(tripId = otherTrip.id!!))
        db.trips.addTelemetry(trip.id!!, point(10))
        assertEquals(listOf(10L, 30L), db.trips.getTelemetry(trip.id!!).map { it.timestamp })
        assertTrue(db.trips.getTelemetry(otherTrip.id!!).isEmpty())
        assertTrue(db.trips.getTelemetry(trip.id!!).all { it.tripId == trip.id })
    }

    @Test fun rejectsInvalidOdometersWithoutChangingStoredTrip() = runBlocking {
        val invalid = listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (value in invalid) rejects { db.trips.startTrip(bike.vin, value, null, null) }
        assertTrue(db.trips.getTrips(bike.vin, 10, 0).isEmpty())
        val trip = db.trips.startTrip(bike.vin, 100.0, null, null)
        for (value in invalid + 99.0) rejects { db.trips.endTrip(trip, value, null, null) }
        now--
        rejects { db.trips.endTrip(trip, 101.0, null, null) }
        assertEquals(trip, db.trips.getTrip(trip.id!!))
    }

    @Test fun endingUsesStoredTripAndRequiresExistingActiveId() = runBlocking {
        val trip = db.trips.startTrip(bike.vin, 100.0, 1.0, 2.0)
        rejects { db.trips.endTrip(trip.copy(id = null), 100.0, null, null) }
        rejects { db.trips.endTrip(trip.copy(id = 999), 100.0, null, null) }
        rejects { db.trips.endTrip(trip.copy(startOdometer = 0.0), 50.0, null, null) }
        now += 3_600_000
        val ended = db.trips.endTrip(trip.copy(vin = other.vin, startTime = 0, startOdometer = 0.0, synced = true), 110.0, null, null)
        assertEquals(trip.copy(endTime = now, endOdometer = 110.0, distanceKm = 10.0, avgSpeedKmph = 10.0), ended)
        rejects { db.trips.endTrip(trip, 120.0, null, null) }
    }

    @Test fun concurrentStartsAllowOnlyOneActiveTripPerVin() = runBlocking {
        val results = coroutineScope {
            (1..8).map { async { runCatching { db.trips.startTrip(bike.vin, 0.0, null, null) } } }.awaitAll()
        }
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is IllegalArgumentException })
        assertEquals(1, db.trips.getTrips(bike.vin, 10, 0).size)
    }

    @Test fun reminderWhicheverFirstCompletionAndBikeIsolationPersist() = runBlocking {
        val byDistance = db.reminders.addReminder(reminder(odometer = 100.0, date = 9999))
        val byTime = db.reminders.addReminder(reminder(odometer = 999.0, date = 100))
        db.reminders.addReminder(reminder(odometer = 101.0, date = 101))
        db.reminders.addReminder(reminder())
        db.reminders.addReminder(reminder(vin = other.vin, odometer = 0.0, date = 0))
        val alreadyDone = db.reminders.addReminder(reminder(odometer = 0.0).copy(completed = true, completedAtMillis = 0))
        assertEquals(setOf(byDistance, byTime), db.reminders.getDueReminders(bike.vin, 100.0, 100).toSet())
        db.reminders.completeReminder(byDistance.id!!, 0)
        reopen()
        assertEquals(listOf(byTime), db.reminders.getDueReminders(bike.vin, 100.0, 100))
        assertEquals(byDistance.copy(completed = true, completedAtMillis = 0), db.reminders.getReminders(bike.vin).single { it.id == byDistance.id })
        assertTrue(alreadyDone in db.reminders.getReminders(bike.vin))
        val zeros = db.reminders.getReminders(other.vin).single()
        assertEquals(0.0, zeros.dueOdometerKm!!, 0.0)
        assertEquals(0L, zeros.dueDateMillis)
    }

    @Test fun editingRemindersUpdatesRiderFieldsAndPersistsCompletion() = runBlocking {
        val saved = db.reminders.addReminder(reminder(odometer = 100.0, date = 5_000))
        db.reminders.completeReminder(saved.id!!, 6_000)

        val updated = db.reminders.updateReminder(db.reminders.getReminders(bike.vin).single().copy(
            title = "Chain",
            description = "Clean and lube",
            dueOdometerKm = 250.0,
            dueDateMillis = 9_000,
        ))

        assertEquals("Chain", updated.title)
        assertEquals("Clean and lube", updated.description)
        assertEquals(250.0, updated.dueOdometerKm!!, 0.0)
        assertEquals(9_000L, updated.dueDateMillis)
        assertTrue(updated.completed)
        assertEquals(6_000L, updated.completedAtMillis)

        reopen()
        assertEquals(updated, db.reminders.getReminders(bike.vin).single())
    }

    @Test fun reminderEditsRejectInvalidContentAndMissingRows() = runBlocking {
        val saved = db.reminders.addReminder(reminder(odometer = 100.0))
        for (invalid in listOf(
            saved.copy(title = " "),
            saved.copy(dueOdometerKm = null, dueDateMillis = null),
            saved.copy(dueOdometerKm = -1.0),
            saved.copy(dueOdometerKm = Double.NaN),
            saved.copy(id = 9_999),
        )) {
            rejects { db.reminders.updateReminder(invalid) }
        }
        assertEquals(saved, db.reminders.getReminders(bike.vin).single())
    }

    @Test fun deletingAndReopeningRemindersTouchesOnlyTheSelectedRow() = runBlocking {
        val first = db.reminders.addReminder(reminder(odometer = 100.0).copy(title = "First"))
        val second = db.reminders.addReminder(reminder(odometer = 200.0).copy(title = "Second"))
        db.reminders.addReminder(reminder(vin = other.vin, odometer = 0.0))

        db.reminders.deleteReminder(first.id!!)
        assertEquals(listOf(second), db.reminders.getReminders(bike.vin))
        assertEquals(1, db.reminders.getReminders(other.vin).size)

        db.reminders.completeReminder(second.id!!, 5_000)
        assertTrue(db.reminders.getDueReminders(bike.vin, 500.0, 10_000).isEmpty())
        val reopened = db.reminders.reopenReminder(second.id!!)
        assertFalse(reopened.completed)
        assertNull(reopened.completedAtMillis)
        assertEquals(listOf(reopened), db.reminders.getDueReminders(bike.vin, 500.0, 10_000))

        reopen()
        assertEquals(reopened, db.reminders.getReminders(bike.vin).single())
        rejects { db.reminders.deleteReminder(first.id!!) }
        rejects { db.reminders.reopenReminder(first.id!!) }
    }

    @Test fun restoringAReminderKeepsItsOriginalIdAndRejectsDuplicates() = runBlocking {
        val saved = db.reminders.addReminder(reminder(odometer = 100.0))
        db.reminders.deleteReminder(saved.id!!)
        assertNull(db.reminders.getReminder(saved.id!!))

        assertEquals(saved, db.reminders.restoreReminder(saved))
        assertEquals(saved, db.reminders.getReminder(saved.id!!))
        assertEquals(listOf(saved), db.reminders.getReminders(bike.vin))

        reopen()
        assertEquals(saved, db.reminders.getReminder(saved.id!!))
        rejects { db.reminders.restoreReminder(saved) }
    }

    @Test fun completingARecurringReminderAddsExactlyOneNextOccurrence() = runBlocking {
        val saved = db.reminders.addReminder(reminder(odometer = 100.0).copy(repeatEveryKm = 50.0))

        db.reminders.completeReminder(saved.id!!, 5_000)

        val all = db.reminders.getReminders(bike.vin)
        assertEquals(2, all.size)
        val next = all.single { !it.completed }
        assertEquals(150.0, next.dueOdometerKm!!, 0.0)
        assertEquals(50.0, next.repeatEveryKm!!, 0.0)
        assertNull(next.completedAtMillis)

        db.reminders.completeReminder(saved.id!!, 6_000)
        assertEquals(2, db.reminders.getReminders(bike.vin).size)

        reopen()
        assertEquals(2, db.reminders.getReminders(bike.vin).size)
        assertEquals(150.0, db.reminders.getReminders(bike.vin).single { !it.completed }.dueOdometerKm!!, 0.0)
    }

    @Test fun schemaOneRemindersGainRepeatColumnsWithoutLosingRows() = runBlocking {
        db.close()
        context.deleteDatabase(name)
        val legacy = object : android.database.sqlite.SQLiteOpenHelper(context, name, null, 1) {
            init {
                setWriteAheadLoggingEnabled(true)
            }

            override fun onCreate(legacyDb: SQLiteDatabase) {
                legacyDb.execSQL(
                    "CREATE TABLE bike_profile (vin TEXT PRIMARY KEY NOT NULL, model TEXT, " +
                        "model_image_url TEXT, nickname TEXT, purchase_date_millis INTEGER, last_synced_at_millis INTEGER)",
                )
                legacyDb.execSQL(
                    "CREATE TABLE service_reminder (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "vin TEXT NOT NULL REFERENCES bike_profile(vin), title TEXT NOT NULL, description TEXT, " +
                        "due_odometer_km REAL, due_date_millis INTEGER, completed INTEGER NOT NULL DEFAULT 0, " +
                        "completed_at_millis INTEGER, created_at_millis INTEGER NOT NULL)",
                )
                legacyDb.execSQL("INSERT INTO bike_profile (vin) VALUES (?)", arrayOf(bike.vin))
                legacyDb.execSQL(
                    "INSERT INTO service_reminder (vin, title, due_odometer_km, created_at_millis) VALUES (?, ?, ?, ?)",
                    arrayOf(bike.vin, "Legacy service", 100.0, 0),
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                error("Test helper only creates schema 1")
        }
        legacy.writableDatabase
        legacy.close()

        db = AndroidDatabase(context, name) { now }
        // Opening the helper upgrades the schema. Robolectric's API 26 connection pool then caches
        // the pre-upgrade schema, so a fresh connection makes the added columns visible.
        db.bikes.getBike("DEV-MISSING")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).close()

        val legacyReminder = db.reminders.getReminders(bike.vin).single()
        assertEquals("Legacy service", legacyReminder.title)
        assertEquals(100.0, legacyReminder.dueOdometerKm!!, 0.0)
        assertNull(legacyReminder.repeatEveryKm)
        assertNull(legacyReminder.repeatEveryDays)

        val recurring = db.reminders.addReminder(reminder(odometer = 200.0).copy(repeatEveryDays = 30))
        val stored = db.reminders.getReminders(bike.vin).single { it.id == recurring.id }
        assertEquals(30L, stored.repeatEveryDays)
        reopen()
        assertEquals(30L, db.reminders.getReminders(bike.vin).single { it.id == recurring.id }.repeatEveryDays)
    }

    @Test fun bikeUpsertPreservesChildrenAndNullableProfileFields() = runBlocking {
        val trip = db.trips.startTrip(bike.vin, 0.0, null, null)
        db.trips.addTelemetry(trip.id!!, point())
        val savedReminder = db.reminders.addReminder(reminder().copy(description = null))
        val updated = BikeProfile(bike.vin, null, null, null, null, null)
        assertEquals(updated, db.bikes.upsertBike(updated))
        reopen()
        assertEquals(updated, db.bikes.getBike(bike.vin))
        assertEquals(trip, db.trips.getTrip(trip.id!!))
        assertEquals(1, db.trips.getTelemetry(trip.id!!).size)
        assertEquals(listOf(savedReminder), db.reminders.getReminders(bike.vin))
        assertEquals(other, db.bikes.getBike(other.vin))
        assertNull(db.bikes.getBike("DEV-MISSING"))
    }

    @Test fun databaseUsesWalForeignKeysAndRequiredIndexes() = runBlocking {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
            sql.rawQuery("PRAGMA journal_mode", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("wal", it.getString(0))
            }
            sql.rawQuery("PRAGMA index_list('trip')", null).use { indexes ->
                val names = buildList { while (indexes.moveToNext()) add(indexes.getString(indexes.getColumnIndexOrThrow("name"))) }
                assertTrue(names.any { index ->
                    sql.rawQuery("PRAGMA index_info('$index')", null).use { columns ->
                        buildList { while (columns.moveToNext()) add(columns.getString(2)) } == listOf("vin", "start_time", "id")
                    }
                })
            }
            sql.rawQuery("PRAGMA index_xinfo('trip_vin_start_id')", null).use { columns ->
                val descending = buildList {
                    while (columns.moveToNext()) if (columns.getInt(5) == 1) add(columns.getInt(3))
                }
                assertEquals(listOf(0, 1, 1), descending)
            }
            sql.rawQuery("PRAGMA index_info('telemetry_trip_timestamp')", null).use { columns ->
                assertEquals(listOf("trip_id", "timestamp"), buildList {
                    while (columns.moveToNext()) add(columns.getString(2))
                })
            }
            sql.rawQuery("PRAGMA foreign_key_list('telemetry')", null).use { assertTrue(it.moveToFirst()) }
            sql.rawQuery("PRAGMA foreign_key_list('trip')", null).use { assertTrue(it.moveToFirst()) }
            sql.rawQuery("PRAGMA foreign_key_list('service_reminder')", null).use { assertTrue(it.moveToFirst()) }
        }
    }

    @Test fun storedTripMetricsAndSyncedFlagSurviveEndingAndReopen() = runBlocking {
        val trip = db.trips.startTrip(bike.vin, 0.0, null, null)
        db.close()
        // The public repository does not expose writes for imported/aggregated trip metrics.
        context.openOrCreateDatabase(name, 0, null).use {
            it.execSQL("UPDATE trip SET max_speed_kmph = 0, fuel_used_liters = 0, synced = 1 WHERE id = ?", arrayOf(trip.id))
        }
        db = AndroidDatabase(context, name) { now }
        val stored = trip.copy(maxSpeedKmph = 0.0, fuelUsedLiters = 0.0, synced = true)
        assertEquals(stored, db.trips.getTrip(trip.id!!))
        val ended = db.trips.endTrip(trip, 0.0, null, null)
        assertEquals(stored.copy(endTime = now, endOdometer = 0.0, distanceKm = 0.0, avgSpeedKmph = 0.0), ended)
        reopen()
        assertEquals(ended, db.trips.getTrip(trip.id!!))
    }

    @Test fun repositoryWorkRunsOffMainThread() = runBlocking {
        db.close()
        var clockCalled = false
        db = AndroidDatabase(context, name) {
            assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
            clockCalled = true
            now
        }
        db.trips.startTrip(bike.vin, 0.0, null, null)
        assertTrue(clockCalled)
    }

    @Test fun editingACompletedTripPersistsAndRejectsAnActiveTrip() = runBlocking {
        val trip = db.trips.startTrip(bike.vin, 100.0, null, null)
        now += 3_600_000
        val ended = db.trips.endTrip(trip, 150.0, null, null)

        val updated = db.trips.updateTripOdometer(trip.id!!, 90.0, 140.0)
        assertEquals(50.0, updated.distanceKm!!, 0.0)
        assertEquals(50.0, updated.avgSpeedKmph!!, 0.0)
        assertEquals(ended.startTime, updated.startTime)
        assertEquals(ended.endTime, updated.endTime)

        reopen()
        assertEquals(updated, db.trips.getTrip(trip.id!!))

        val active = db.trips.startTrip(other.vin, 0.0, null, null)
        rejects { db.trips.updateTripOdometer(active.id!!, 0.0, 5.0) }
        rejects { db.trips.updateTripOdometer(trip.id!!, 200.0, 100.0) }
        assertEquals(updated, db.trips.getTrip(trip.id!!))
    }

    @Test fun deletingATripRemovesItsTelemetryAndUndoRestoresTheTrip() = runBlocking {
        val trip = db.trips.startTrip(bike.vin, 100.0, null, null)
        now += 3_600_000
        val ended = db.trips.endTrip(trip, 110.0, null, null)
        db.trips.addTelemetry(trip.id!!, point(5))
        val otherTrip = db.trips.startTrip(other.vin, 0.0, null, null)

        db.trips.deleteTrip(trip.id!!)

        assertNull(db.trips.getTrip(trip.id!!))
        assertTrue(db.trips.getTelemetry(trip.id!!).isEmpty())
        assertEquals(otherTrip, db.trips.getTrip(otherTrip.id!!))

        val restored = db.trips.restoreTrip(ended)
        assertEquals(ended, restored)
        assertEquals(ended, db.trips.getTrip(trip.id!!))
        assertTrue(db.trips.getTelemetry(trip.id!!).isEmpty())
        rejects { db.trips.restoreTrip(ended) }
        rejects { db.trips.deleteTrip(9999) }
    }

    @Test fun discardingAnActiveTripFreesTheActiveSlot() = runBlocking {
        val active = db.trips.startTrip(bike.vin, 100.0, null, null)
        rejects { db.trips.startTrip(bike.vin, 101.0, null, null) }

        db.trips.deleteTrip(active.id!!)

        assertNull(db.trips.getActiveTrip(bike.vin))
        val replacement = db.trips.startTrip(bike.vin, 101.0, null, null)
        assertEquals(replacement, db.trips.getActiveTrip(bike.vin))
    }

    @Test fun homeAggregatesUseCompletedTripsForTheBikeOnly() = runBlocking {
        val first = db.trips.startTrip(bike.vin, 100.0, null, null)
        now += 1_000
        db.trips.endTrip(first, 140.0, null, null)
        val second = db.trips.startTrip(bike.vin, 140.0, null, null)
        now += 1_000
        db.trips.endTrip(second, 200.0, null, null)
        val foreign = db.trips.startTrip(other.vin, 0.0, null, null)
        now += 1_000
        db.trips.endTrip(foreign, 60.0, null, null)
        db.trips.startTrip(bike.vin, 500.0, null, null)

        assertEquals(100.0, db.trips.totalDistanceKm(bike.vin), 0.0)
        assertEquals(2, db.trips.completedTripsBetween(bike.vin, 0, Long.MAX_VALUE))
        assertEquals(0, db.trips.completedTripsBetween(bike.vin, now + 1, Long.MAX_VALUE))
    }
}
