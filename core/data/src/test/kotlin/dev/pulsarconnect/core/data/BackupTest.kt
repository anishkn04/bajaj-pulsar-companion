package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.BikeProfile
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.model.TelemetryPoint
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupTest {
    private val vin = "DEV-BIKE-A"
    private val otherVin = "DEV-BIKE-B"
    private val bike = BikeProfile(vin, "Synthetic model", null, "Test bike", null)

    private fun newStore() = Store()

    private class Store {
        val bikes = InMemoryBikeRepository()
        val trips = InMemoryTripRepository()
        val reminders = InMemoryReminderRepository()
        val service = LocalBackupService(trips, reminders, bikes)
    }

    private suspend fun Store.seed(vin: String = "DEV-BIKE-A"): LocalBackup {
        bikes.upsertBike(BikeProfile(vin, "Synthetic model", null, "Test bike", null))
        val trip = trips.startTrip(vin, 100.0, null, null)
        trips.endTrip(trip, 150.0, null, null)
        trips.addTelemetry(trip.id!!, point(trip.id!!))
        reminders.addReminder(reminder(vin))
        return service.export(vin, redactVin = false, exportedAtMillis = 42)
    }

    private fun reminder(vin: String) = ServiceReminder(
        vin = vin, title = "Oil change", description = null,
        dueOdometerKm = 1_500.0, dueDateMillis = 20_000,
        createdAtMillis = 10,
    )

    private fun point(tripId: Long) = TelemetryPoint(
        tripId = tripId, timestamp = 5, speedKmph = 40.0, rpm = 2_000,
        fuelLevelPercent = 50, engineTempC = 80, lat = null, lon = null, synced = false,
    )

    @Test
    fun `export round-trips through the codec`() = runTest {
        val store = newStore()
        val exported = store.seed()

        val encoded = LocalBackupCodec.encode(exported)
        val decoded = LocalBackupCodec.decode(encoded)

        assertEquals(exported, decoded)
        assertEquals(1, decoded.trips.size)
        assertEquals(1, decoded.telemetry.size)
        assertEquals(1, decoded.reminders.size)
    }

    @Test
    fun `redacted export replaces every VIN and excludes secret fields`() = runTest {
        val store = newStore()
        store.seed()

        val redacted = store.service.export(vin, redactVin = true, exportedAtMillis = 42)
        val encoded = LocalBackupCodec.encode(redacted).lowercase()

        assertEquals(REDACTED_VIN, redacted.bike.vin)
        assertTrue(redacted.trips.all { it.vin == REDACTED_VIN })
        assertTrue(redacted.reminders.all { it.vin == REDACTED_VIN })
        assertFalse(encoded.contains(vin.lowercase()))
        for (secret in listOf(
            "accesscode", "access_token", "usertoken", "user_token", "refreshtoken",
            "password", "authorization", "bluetooth", "macaddress", "deviceaddress",
        )) {
            assertFalse(encoded.contains(secret), "Export must not contain $secret")
        }
    }

    @Test
    fun `malformed and wrong-version files are rejected with a clear message`() = runTest {
        val store = newStore()
        val encoded = LocalBackupCodec.encode(store.seed())

        val malformed = assertThrows(IllegalArgumentException::class.java) {
            LocalBackupCodec.decode("{ not json")
        }
        assertTrue(malformed.message!!.contains("not a valid"))

        val newer = assertThrows(IllegalArgumentException::class.java) {
            LocalBackupCodec.decode(
                encoded.replace("\"schemaVersion\": 2", "\"schemaVersion\": 3, \"futureField\": true"),
            )
        }
        assertTrue(newer.message!!.contains("newer app version"))

        val older = assertThrows(IllegalArgumentException::class.java) {
            LocalBackupCodec.decode(encoded.replace("\"schemaVersion\": 2", "\"schemaVersion\": 0"))
        }
        assertTrue(older.message!!.contains("unsupported schema version"))
    }

    @Test
    fun `a version 1 backup without repeat fields still decodes`() = runTest {
        val store = newStore()
        val encoded = LocalBackupCodec.encode(store.seed())
            .replace("\"schemaVersion\": 2", "\"schemaVersion\": 1")

        val decoded = LocalBackupCodec.decode(encoded)

        assertEquals(1, decoded.schemaVersion)
        assertEquals(1, decoded.reminders.size)
        assertNull(decoded.reminders.single().repeatEveryKm)
        assertNull(decoded.reminders.single().repeatEveryDays)
    }

    @Test
    fun `restore writes trips telemetry and reminders with their original ids`() = runTest {
        val source = newStore()
        val backup = source.seed()
        val target = newStore()

        val summary = target.service.restore(backup, vin, RestoreMode.REPLACE)

        assertEquals(1, summary.trips)
        assertEquals(1, summary.telemetryPoints)
        assertEquals(1, summary.reminders)
        val restoredTrip = backup.trips.single()
        assertEquals(restoredTrip, target.trips.getTrip(restoredTrip.id!!))
        assertEquals(1, target.trips.getTelemetry(restoredTrip.id!!).size)
        assertEquals(restoredTrip.id, target.trips.getTelemetry(restoredTrip.id!!).single().tripId)
        assertEquals(backup.reminders.single(), target.reminders.getReminders(vin).single())
        assertEquals(backup.reminders.single().id, summary.restoredReminderIds.single())
    }

    @Test
    fun `merge keeps current rows while replace rewrites them from the backup`() = runTest {
        val source = newStore()
        val backup = source.seed()

        val mergeTarget = newStore()
        mergeTarget.bikes.upsertBike(bike.copy(nickname = "Local name"))
        val localTrip = mergeTarget.trips.startTrip(vin, 1.0, null, null)
        mergeTarget.trips.endTrip(localTrip, 2.0, null, null)
        mergeTarget.reminders.addReminder(reminder(vin).copy(title = "Local reminder"))

        val merged = mergeTarget.service.restore(backup, vin, RestoreMode.MERGE)

        assertEquals(0, merged.trips)
        assertEquals(0, merged.reminders)
        assertEquals(localTrip.id, mergeTarget.trips.getTrips(vin, 10, 0).single().id)
        assertEquals("Local reminder", mergeTarget.reminders.getReminders(vin).single().title)
        assertEquals("Local name", mergeTarget.bikes.getBike(vin)!!.nickname)

        val replaceTarget = newStore()
        replaceTarget.bikes.upsertBike(bike)
        val replacedTrip = replaceTarget.trips.startTrip(vin, 1.0, null, null)
        replaceTarget.trips.endTrip(replacedTrip, 2.0, null, null)
        replaceTarget.reminders.addReminder(reminder(vin).copy(title = "Local reminder"))

        val replaced = replaceTarget.service.restore(backup, vin, RestoreMode.REPLACE)

        assertEquals(1, replaced.trips)
        assertEquals(1, replaced.reminders)
        assertEquals(backup.trips.single(), replaceTarget.trips.getTrips(vin, 10, 0).single())
        assertEquals(backup.reminders.single(), replaceTarget.reminders.getReminders(vin).single())
    }

    private suspend fun failure(block: suspend () -> Unit): IllegalArgumentException {
        try {
            block()
        } catch (error: IllegalArgumentException) {
            return error
        }
        throw AssertionError("Expected IllegalArgumentException")
    }

    @Test
    fun `backup for another bike is rejected but a redacted backup binds to the signed-in bike`() = runTest {
        val source = newStore()
        source.bikes.upsertBike(BikeProfile(otherVin, null, null, null, null))
        source.trips.startTrip(otherVin, 100.0, null, null)
        source.reminders.addReminder(reminder(otherVin))
        val otherBackup = source.service.export(otherVin, redactVin = false, exportedAtMillis = 42)
        val target = newStore()

        val rejected = failure { target.service.restore(otherBackup, vin, RestoreMode.REPLACE) }
        assertTrue(rejected.message!!.contains("different bike"))
        assertTrue(target.trips.getTrips(vin, 10, 0).isEmpty())

        val redacted = source.service.export(otherVin, redactVin = true, exportedAtMillis = 42)
        target.service.restore(redacted, vin, RestoreMode.REPLACE)
        assertEquals(1, target.trips.getTrips(vin, 10, 0).size)
        assertEquals(1, target.reminders.getReminders(vin).size)
        assertEquals(vin, target.bikes.getBike(vin)?.vin)
    }

    @Test
    fun `a backup id belonging to another bike is rejected before anything is written`() = runTest {
        val source = newStore()
        source.bikes.upsertBike(BikeProfile(otherVin, null, null, null, null))
        source.trips.startTrip(otherVin, 0.0, null, null)
        val redacted = source.service.export(otherVin, redactVin = true, exportedAtMillis = 42)

        val target = newStore()
        target.bikes.upsertBike(bike)
        target.bikes.upsertBike(BikeProfile(otherVin, null, null, null, null))
        val otherTrip = target.trips.startTrip(otherVin, 0.0, null, null) // same id as the backup
        target.trips.startTrip(vin, 1.0, null, null)

        val rejected = failure { target.service.restore(redacted, vin, RestoreMode.REPLACE) }
        assertTrue(rejected.message!!.contains("another bike"))
        assertEquals(otherTrip, target.trips.getTrip(otherTrip.id!!))
        assertEquals(1, target.trips.getTrips(vin, 10, 0).size)
    }

    @Test
    fun `decode rejects a backup whose telemetry points at an unknown trip`() = runTest {
        val store = newStore()
        val encoded = LocalBackupCodec.encode(store.seed())
            .replace("\"tripId\": 1", "\"tripId\": 99")

        val rejected = assertThrows(IllegalArgumentException::class.java) {
            LocalBackupCodec.decode(encoded)
        }
        assertTrue(rejected.message!!.contains("ride samples"))
        assertNull(store.trips.getTrip(99))
    }
}
