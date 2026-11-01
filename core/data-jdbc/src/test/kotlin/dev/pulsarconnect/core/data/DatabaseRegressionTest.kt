package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.BikeProfile
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.model.TelemetryPoint
import dev.pulsarconnect.core.model.Trip
import java.sql.SQLException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DatabaseRegressionTest {
    private val file = java.io.File.createTempFile("regression_", ".db")
    private val db = Database.create(file.absolutePath)

    @AfterEach
    fun tearDown() {
        db.close()
        file.delete()
    }

    private fun bike() = BikeProfile("VIN", null, null, null, null)
    private fun trip() = Trip(
        vin = "VIN", startTime = 0, endTime = null,
        startOdometer = 0.0, endOdometer = null, distanceKm = null,
        avgSpeedKmph = null, maxSpeedKmph = null, fuelUsedLiters = null,
        startLat = null, startLon = null, endLat = null, endLon = null,
    )

    @Test
    fun `memory database survives operation connections and instances are isolated`() = runTest {
        Database.create(":memory:").use { first ->
            Database.create(":memory:").use { second ->
                first.upsertBike(bike())
                assertEquals(bike(), first.getBike("VIN"))
                assertNull(second.getBike("VIN"))
                first.connection.use { conn ->
                    conn.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA foreign_keys").use { result ->
                            assertTrue(result.next())
                            assertEquals(1, result.getInt(1))
                        }
                    }
                }
                assertEquals(bike(), first.getBike("VIN"))
            }
        }
    }

    @Test
    fun `bike nullable and empty string fields and zero timestamps roundtrip distinctly`() = runTest {
        db.upsertBike(bike())
        assertEquals(bike(), db.getBike("VIN"))
        val zero = bike().copy(model = "", modelImageUrl = "", nickname = "", purchaseDateMillis = 0, lastSyncedAtMillis = 0)
        db.upsertBike(zero)
        assertEquals(zero, db.getBike("VIN"))
        db.upsertBike(bike())
        assertEquals(bike(), db.getBike("VIN"))
    }

    @Test
    fun `trip nulls zeros and coordinates roundtrip distinctly on insert and update`() = runTest {
        db.upsertBike(bike())
        val active = trip()
        val id = db.insertTrip(active)
        assertEquals(active.copy(id = id), db.getTrip(id))
        val zero = active.copy(
            id = id, endTime = 0, endOdometer = 0.0, distanceKm = 0.0, avgSpeedKmph = 0.0,
            maxSpeedKmph = 0.0, fuelUsedLiters = 0.0, startLat = 0.0, startLon = -1.0,
            endLat = 0.0, endLon = -2.0,
        )
        val zeroId = db.insertTrip(zero)
        assertEquals(zero.copy(id = zeroId), db.getTrip(zeroId))
        db.updateTrip(zero.copy(startLat = null, startLon = null))
        assertEquals(zero.copy(startLat = null, startLon = null), db.getTrip(id))
        db.updateTrip(active.copy(id = id))
        assertEquals(active.copy(id = id), db.getTrip(id))
    }

    @Test
    fun `telemetry nulls zeros and negative temperatures roundtrip distinctly`() = runTest {
        db.upsertBike(bike())
        val tripId = db.insertTrip(trip())
        val absent = TelemetryPoint(
            tripId = tripId, timestamp = 0, speedKmph = null, rpm = null,
            fuelLevelPercent = null, engineTempC = null, lat = null, lon = null,
        )
        val id = db.insertTelemetry(absent)
        val zero = absent.copy(timestamp = 1, speedKmph = 0.0, rpm = 0, fuelLevelPercent = 0, engineTempC = 0, lat = 0.0, lon = 0.0)
        val zeroId = db.insertTelemetry(zero)
        val cold = zero.copy(timestamp = 2, engineTempC = -10, lat = -12.0, lon = -77.0)
        val coldId = db.insertTelemetry(cold)
        assertEquals(listOf(absent.copy(id = id), zero.copy(id = zeroId), cold.copy(id = coldId)), db.getTelemetryForTrip(tripId))
    }

    @Test
    fun `reminder nulls and zero thresholds and completion roundtrip distinctly`() = runTest {
        db.upsertBike(bike())
        val absent = ServiceReminder(vin = "VIN", title = "Service", description = null, dueOdometerKm = null, dueDateMillis = null, createdAtMillis = 0)
        val id = db.insertReminder(absent)
        val zero = absent.copy(description = "", dueOdometerKm = 0.0, dueDateMillis = 0, completedAtMillis = 0)
        val zeroId = db.insertReminder(zero)
        assertEquals(setOf(absent.copy(id = id), zero.copy(id = zeroId)), db.getRemindersForVin("VIN").toSet())
        db.updateReminder(absent.copy(id = id, completed = true, completedAtMillis = 0))
        assertEquals(0L, db.getRemindersForVin("VIN").first { it.id == id }.completedAtMillis)
        db.updateReminder(absent.copy(id = id))
        assertEquals(absent.copy(id = id), db.getRemindersForVin("VIN").first { it.id == id })
    }

    @Test
    fun `foreign keys reject orphan trips reminders and telemetry and cascade trip deletion`() = runTest {
        assertThrows(SQLException::class.java) { runBlocking { db.insertTrip(trip()) } }
        assertThrows(SQLException::class.java) {
            runBlocking { db.insertReminder(ServiceReminder(vin = "VIN", title = "Service", description = null, dueOdometerKm = null, dueDateMillis = null, createdAtMillis = 0)) }
        }
        val point = TelemetryPoint(tripId = 999, timestamp = 0, speedKmph = null, rpm = null, fuelLevelPercent = null, engineTempC = null, lat = null, lon = null)
        assertThrows(SQLException::class.java) { runBlocking { db.insertTelemetry(point) } }
        db.upsertBike(bike())
        val id = db.insertTrip(trip())
        db.insertTelemetry(point.copy(tripId = id))
        db.connection.use { conn ->
            conn.prepareStatement("DELETE FROM trip WHERE id = ?").use { statement ->
                statement.setLong(1, id)
                assertEquals(1, statement.executeUpdate())
            }
        }
        assertTrue(db.getTelemetryForTrip(id).isEmpty())
    }
}
