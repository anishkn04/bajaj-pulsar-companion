package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.BikeProfile
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.model.TelemetryPoint
import dev.pulsarconnect.core.model.Trip
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DatabaseTest {

    private lateinit var db: Database
    private lateinit var tempFile: java.io.File

    @BeforeEach
    fun setUp() = runBlocking {
        tempFile = java.io.File.createTempFile("test_", ".db")
        db = Database.create(tempFile.absolutePath)
        // Trip and reminder fixtures must reference persisted bikes.
        for (vin in listOf("MD2ABCDEFGH123456", "VIN")) {
            db.upsertBike(BikeProfile(vin, null, null, null, null))
        }
    }

    @AfterEach
    fun tearDown() {
        db.close()
        tempFile.delete()
    }

    @Test
    fun `bike profile crud`() = runTest {
        val profile = BikeProfile(
            vin = "MD2ABCDEFGH123456",
            model = "PULSAR 150 N DISC ES",
            nickname = "Test Bike",
            purchaseDateMillis = System.currentTimeMillis(),
            modelImageUrl = null,
            lastSyncedAtMillis = null,
        )
        db.upsertBike(profile)

        val fetched = db.getBike(profile.vin)
        assertNotNull(fetched)
        assertEquals("Test Bike", fetched?.displayName)
        assertEquals("PULSAR 150 N DISC ES", fetched?.model)
        assertEquals(profile.purchaseDateMillis, fetched?.purchaseDateMillis)
    }

    @Test
    fun `trip insert and fetch`() = runTest {
        val now = System.currentTimeMillis()
        val trip = Trip(
            vin = "MD2ABCDEFGH123456",
            startTime = now,
            endTime = null,
            startOdometer = 1000.0,
            endOdometer = null,
            distanceKm = null,
            avgSpeedKmph = null,
            maxSpeedKmph = null,
            fuelUsedLiters = null,
            startLat = null,
            startLon = null,
            endLat = null,
            endLon = null,
            synced = false,
        )
        val id = db.insertTrip(trip)

        assertTrue(id > 0)

        val fetched = db.getTrip(id)
        assertNotNull(fetched)
        assertEquals(id, fetched?.id)
        assertEquals(1000.0, fetched?.startOdometer)
        assertEquals("MD2ABCDEFGH123456", fetched?.vin)
    }

    @Test
    fun `trip update with end data`() = runTest {
        val now = System.currentTimeMillis()
        val trip = Trip(
            vin = "MD2ABCDEFGH123456",
            startTime = now,
            endTime = null,
            startOdometer = 1000.0,
            endOdometer = null,
            distanceKm = null,
            avgSpeedKmph = null,
            maxSpeedKmph = null,
            fuelUsedLiters = null,
            startLat = null,
            startLon = null,
            endLat = null,
            endLon = null,
            synced = false,
        )
        val id = db.insertTrip(trip)

        val ended = Trip(
            id = id,
            vin = "MD2ABCDEFGH123456",
            startTime = now,
            endTime = now + 3_600_000,
            startOdometer = 1000.0,
            endOdometer = 1025.5,
            distanceKm = 25.5,
            avgSpeedKmph = 25.5,
            maxSpeedKmph = 60.0,
            fuelUsedLiters = 1.2,
            startLat = null,
            startLon = null,
            endLat = null,
            endLon = null,
            synced = true,
        )
        db.updateTrip(ended)

        val fetched = db.getTrip(id)
        assertEquals(1025.5, fetched?.endOdometer)
        assertEquals(25.5, fetched?.distanceKm)
        assertTrue(fetched?.synced == true)
    }

    @Test
    fun `telemetry points for trip`() = runTest {
        val now = System.currentTimeMillis()
        val trip = Trip(
            vin = "VIN",
            startTime = now,
            endTime = null,
            startOdometer = 0.0,
            endOdometer = null,
            distanceKm = null,
            avgSpeedKmph = null,
            maxSpeedKmph = null,
            fuelUsedLiters = null,
            startLat = null,
            startLon = null,
            endLat = null,
            endLon = null,
            synced = false,
        )
        val tripId = db.insertTrip(trip)

        val p1 = TelemetryPoint(
            tripId = tripId,
            timestamp = now,
            speedKmph = 30.0,
            rpm = 2500,
            fuelLevelPercent = 90,
            engineTempC = 90,
            lat = null,
            lon = null,
            synced = false,
        )
        val p2 = TelemetryPoint(
            tripId = tripId,
            timestamp = now + 1000,
            speedKmph = 40.0,
            rpm = 3000,
            fuelLevelPercent = 89,
            engineTempC = 92,
            lat = null,
            lon = null,
            synced = false,
        )
        db.insertTelemetry(p1)
        db.insertTelemetry(p2)

        val points = db.getTelemetryForTrip(tripId)
        assertEquals(2, points.size)
        assertEquals(30.0, points[0].speedKmph)
        assertEquals(40.0, points[1].speedKmph)
    }

    @Test
    fun `service reminder crud`() = runTest {
        val now = System.currentTimeMillis()
        val reminder = ServiceReminder(
            vin = "MD2ABCDEFGH123456",
            title = "First Service",
            description = null,
            dueOdometerKm = 1000.0,
            dueDateMillis = now + 86_400_000,
            completed = false,
            completedAtMillis = null,
            createdAtMillis = now,
        )
        val id = db.insertReminder(reminder)

        val all = db.getRemindersForVin("MD2ABCDEFGH123456")
        assertEquals(1, all.size)
        assertEquals(id, all.first().id)
        assertEquals("First Service", all.first().title)
        assertEquals(1000.0, all.first().dueOdometerKm)

        // Update completion via direct SQL (since we don't have a helper yet)
        db.connection.use { conn ->
            conn.setAutoCommit(false)
            val stmt = conn.prepareStatement("UPDATE service_reminder SET completed = 1, completed_at_millis = ? WHERE id = ?")
            stmt.setLong(1, now + 1000)
            stmt.setLong(2, id)
            stmt.executeUpdate()
            conn.commit()
        }

        val updated = db.getRemindersForVin("MD2ABCDEFGH123456").first()
        assertTrue(updated.completed)
        assertEquals(now + 1000, updated.completedAtMillis)
    }

    @Test
    fun `multiple bikes and trips isolation`() = runTest {
        val bike1 = BikeProfile(
            vin = "VIN1",
            model = "Bike 1",
            nickname = null,
            modelImageUrl = null,
            purchaseDateMillis = null,
            lastSyncedAtMillis = null,
        )
        val bike2 = BikeProfile(
            vin = "VIN2",
            model = "Bike 2",
            nickname = null,
            modelImageUrl = null,
            purchaseDateMillis = null,
            lastSyncedAtMillis = null,
        )
        db.upsertBike(bike1)
        db.upsertBike(bike2)

        val t1 = Trip(
            vin = "VIN1",
            startTime = System.currentTimeMillis(),
            endTime = null,
            startOdometer = 100.0,
            endOdometer = null,
            distanceKm = null,
            avgSpeedKmph = null,
            maxSpeedKmph = null,
            fuelUsedLiters = null,
            startLat = null,
            startLon = null,
            endLat = null,
            endLon = null,
            synced = false,
        )
        val t2 = Trip(
            vin = "VIN2",
            startTime = System.currentTimeMillis(),
            endTime = null,
            startOdometer = 200.0,
            endOdometer = null,
            distanceKm = null,
            avgSpeedKmph = null,
            maxSpeedKmph = null,
            fuelUsedLiters = null,
            startLat = null,
            startLon = null,
            endLat = null,
            endLon = null,
            synced = false,
        )
        db.insertTrip(t1)
        db.insertTrip(t2)

        val trips1 = db.getTripsForVin("VIN1")
        val trips2 = db.getTripsForVin("VIN2")

        assertEquals(1, trips1.size)
        assertEquals("VIN1", trips1.first().vin)
        assertEquals(1, trips2.size)
        assertEquals("VIN2", trips2.first().vin)
    }
}
