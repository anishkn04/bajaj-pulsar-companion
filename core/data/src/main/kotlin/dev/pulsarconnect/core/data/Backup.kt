package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.BikeProfile
import dev.pulsarconnect.core.model.OdometerReading
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.model.TelemetryPoint
import dev.pulsarconnect.core.model.Trip
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Placeholder written instead of a VIN when the rider redacts identifying data. */
const val REDACTED_VIN = "REDACTED-VIN"

const val BACKUP_SCHEMA_VERSION = 3

/** Oldest backup this app can still import. v1 predates recurring reminder fields. */
const val MIN_BACKUP_SCHEMA_VERSION = 1

enum class RestoreMode { MERGE, REPLACE }

/**
 * Versioned, local-only export of one bike's records. It deliberately has no fields for
 * credentials, tokens, notification content, or Bluetooth addresses.
 */
@Serializable
data class LocalBackup(
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val exportedAtMillis: Long,
    val redacted: Boolean = false,
    val bike: BikeProfile,
    val trips: List<Trip>,
    val telemetry: List<TelemetryPoint>,
    val reminders: List<ServiceReminder>,
    /** Hand-entered odometer reading; absent from schema 1 and 2 backups. */
    val odometer: OdometerReading? = null,
)

data class RestoreSummary(
    val trips: Int,
    val telemetryPoints: Int,
    val reminders: Int,
    /** IDs of reminders that were newly written, so their alerts can be re-armed. */
    val restoredReminderIds: List<Long> = emptyList(),
)

object LocalBackupCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        // A newer schema can add fields; the version check below still rejects it with a clear message.
        ignoreUnknownKeys = true
    }

    fun encode(backup: LocalBackup): String = json.encodeToString(backup)

    /**
     * Parses and version-checks a backup. Throws [IllegalArgumentException] with a rider-readable
     * message for malformed JSON, an unsupported schema version, or broken references.
     */
    fun decode(encoded: String): LocalBackup {
        val backup = try {
            json.decodeFromString<LocalBackup>(encoded)
        } catch (error: IllegalArgumentException) {
            // SerializationException extends IllegalArgumentException; both mean a bad file.
            throw IllegalArgumentException("That file is not a valid Pulsar Connect backup.", error)
        }
        when {
            backup.schemaVersion in MIN_BACKUP_SCHEMA_VERSION..BACKUP_SCHEMA_VERSION -> Unit
            backup.schemaVersion > BACKUP_SCHEMA_VERSION ->
                throw IllegalArgumentException("This backup was made by a newer app version. Update Pulsar Connect before restoring it.")
            else ->
                throw IllegalArgumentException("This backup uses an unsupported schema version (${backup.schemaVersion}).")
        }
        val tripIds = backup.trips.map { trip ->
            requireNotNull(trip.id) { "This backup contains a trip without an ID." }
        }.toSet()
        require(backup.reminders.all { it.id != null }) {
            "This backup contains a reminder without an ID."
        }
        require(backup.telemetry.all { it.tripId in tripIds }) {
            "This backup contains ride samples that do not belong to one of its trips."
        }
        return backup
    }
}

/** Reads and writes backups using the same repositories as the rest of the app. */
class LocalBackupService(
    private val trips: TripRepository,
    private val reminders: ReminderRepository,
    private val bikes: BikeRepository,
    private val odometers: OdometerRepository,
) {
    suspend fun export(vin: String, redactVin: Boolean, exportedAtMillis: Long): LocalBackup {
        val bike = bikes.getBike(vin) ?: BikeProfile(vin, null, null, null, null)
        val savedTrips = trips.getTrips(vin, EXPORT_LIMIT, 0)
        val savedTelemetry = savedTrips.flatMap { trip ->
            trip.id?.let { trips.getTelemetry(it) }.orEmpty()
        }
        val savedReminders = reminders.getReminders(vin)
        val savedOdometer = odometers.getLatest(vin)
        fun <T> List<T>.redact(transform: (T) -> T): List<T> =
            if (redactVin) map(transform) else this
        return LocalBackup(
            exportedAtMillis = exportedAtMillis,
            redacted = redactVin,
            bike = if (redactVin) bike.copy(vin = REDACTED_VIN) else bike,
            trips = savedTrips.redact { it.copy(vin = REDACTED_VIN) },
            telemetry = savedTelemetry,
            reminders = savedReminders.redact { it.copy(vin = REDACTED_VIN) },
            odometer = savedOdometer?.let {
                if (redactVin) it.copy(vin = REDACTED_VIN) else it
            },
        )
    }

    /**
     * Restores one bike's records. [RestoreMode.REPLACE] clears that bike's current records first;
     * [RestoreMode.MERGE] keeps them and skips backup rows whose ID is already present.
     *
     * Rows belonging to a different VIN are never touched. If a backup row's ID collides with a
     * record saved for another bike the restore is rejected before anything is written.
     */
    suspend fun restore(backup: LocalBackup, vin: String, mode: RestoreMode): RestoreSummary {
        val rebound = validateFor(backup, vin)
        preflight(rebound, vin)

        if (mode == RestoreMode.REPLACE) {
            trips.getTrips(vin, REPLACE_LIMIT, 0).forEach { trip ->
                trip.id?.let { trips.deleteTrip(it) }
            }
            reminders.getReminders(vin).forEach { reminder ->
                reminder.id?.let { reminders.deleteReminder(it) }
            }
        }

        // Merge keeps newer local profile details; replace trusts the backup.
        if (mode == RestoreMode.REPLACE || bikes.getBike(vin) == null) {
            bikes.upsertBike(rebound.bike)
        }

        var importedTrips = 0
        var importedTelemetry = 0
        for (trip in rebound.trips) {
            val tripId = requireNotNull(trip.id)
            if (trips.getTrip(tripId) != null) continue
            trips.restoreTrip(trip)
            importedTrips++
            for (point in rebound.telemetry.filter { it.tripId == tripId }) {
                trips.addTelemetry(tripId, point.copy(id = null, tripId = tripId))
                importedTelemetry++
            }
        }

        val restoredReminderIds = mutableListOf<Long>()
        for (reminder in rebound.reminders) {
            val reminderId = requireNotNull(reminder.id)
            if (reminders.getReminder(reminderId) != null) continue
            reminders.restoreReminder(reminder)
            restoredReminderIds.add(reminderId)
        }
        // Restoring a lower reading would make reminders look overdue again, so keep the
        // higher local value when the phone already has one.
        rebound.odometer?.let { reading ->
            val existing = odometers.getLatest(vin)
            if (existing == null || reading.odometerKm >= existing.odometerKm) {
                odometers.setReading(vin, reading.odometerKm, reading.recordedAtMillis)
            }
        }
        return RestoreSummary(
            trips = importedTrips,
            telemetryPoints = importedTelemetry,
            reminders = restoredReminderIds.size,
            restoredReminderIds = restoredReminderIds,
        )
    }

    /** Ensures a backup only contains this bike (or redacted records) before writing anything. */
    private fun validateFor(backup: LocalBackup, vin: String): LocalBackup {
        val allowed = setOf(vin, REDACTED_VIN)
        require(backup.bike.vin in allowed) {
            "This backup belongs to a different bike. Sign in with that bike to restore it."
        }
        require(backup.trips.all { it.vin in allowed }) {
            "This backup contains trips for a different bike."
        }
        require(backup.reminders.all { it.vin in allowed }) {
            "This backup contains reminders for a different bike."
        }
        require(backup.odometer == null || backup.odometer.vin in allowed) {
            "This backup contains an odometer reading for a different bike."
        }
        return backup.copy(
            bike = backup.bike.copy(vin = vin),
            trips = backup.trips.map { it.copy(vin = vin) },
            reminders = backup.reminders.map { it.copy(vin = vin) },
            odometer = backup.odometer?.copy(vin = vin),
        )
    }

    /**
     * Confirms that no backup row would overwrite a record for another bike. Same-VIN rows are
     * fine: merge skips them and replace deletes them first.
     */
    private suspend fun preflight(backup: LocalBackup, vin: String) {
        val conflict = "This backup conflicts with records saved for another bike. Sign in with that bike to restore it."
        for (trip in backup.trips) {
            val existing = trips.getTrip(requireNotNull(trip.id))
            if (existing != null && existing.vin != vin) throw IllegalArgumentException(conflict)
        }
        for (reminder in backup.reminders) {
            val existing = reminders.getReminder(requireNotNull(reminder.id))
            if (existing != null && existing.vin != vin) throw IllegalArgumentException(conflict)
        }
    }

    private companion object {
        const val EXPORT_LIMIT = 100_000
        const val REPLACE_LIMIT = 100_000
    }
}
