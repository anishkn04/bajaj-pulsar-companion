package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.BikeProfile
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.model.TelemetryPoint
import dev.pulsarconnect.core.model.Trip

class SqliteTripRepository(
    private val db: Database,
    private val clock: () -> Long = System::currentTimeMillis,
) : TripRepository {
    override suspend fun startTrip(vin: String, odometerKm: Double, lat: Double?, lon: Double?): Trip {
        require(odometerKm.isFinite() && odometerKm >= 0) { "Odometer must be finite and nonnegative" }
        val now = clock()
        val trip = Trip(
            vin = vin,
            startTime = now,
            endTime = null,
            startOdometer = odometerKm,
            endOdometer = null,
            distanceKm = null,
            avgSpeedKmph = null,
            maxSpeedKmph = null,
            fuelUsedLiters = null,
            startLat = lat,
            startLon = lon,
            endLat = null,
            endLon = null,
            synced = false,
        )
        val id = db.insertTrip(trip)
        return trip.copy(id = id)
    }

    override suspend fun endTrip(trip: Trip, odometerKm: Double, lat: Double?, lon: Double?): Trip {
        val id = requireNotNull(trip.id) { "Trip must have an ID" }
        val stored = requireNotNull(db.getTrip(id)) { "Trip must exist before it can be ended" }
        require(stored.endTime == null) { "Trip has already ended" }
        require(odometerKm.isFinite() && odometerKm >= 0) { "Odometer must be finite and nonnegative" }
        require(odometerKm >= stored.startOdometer) { "End odometer must not precede start odometer" }
        val now = clock()
        require(now >= stored.startTime) { "End time must not precede start time" }
        val distance = odometerKm - stored.startOdometer
        val durationHours = (now - stored.startTime) / 3_600_000.0
        val avgSpeed = if (durationHours > 0) distance / durationHours else 0.0
        val ended = stored.copy(
            endTime = now,
            endOdometer = odometerKm,
            distanceKm = distance,
            avgSpeedKmph = avgSpeed,
            endLat = lat,
            endLon = lon,
        )
        db.finishTrip(ended)
        return ended
    }

    override suspend fun getTrip(id: Long): Trip? = db.getTrip(id)

    override suspend fun getActiveTrip(vin: String): Trip? = db.getActiveTrip(vin)

    override suspend fun getTrips(vin: String, limit: Int, offset: Int): List<Trip> = db.getTripsForVin(vin, limit, offset)

    override suspend fun addTelemetry(tripId: Long, point: TelemetryPoint) {
        db.insertTelemetry(point.copy(tripId = tripId))
    }

    override suspend fun getTelemetry(tripId: Long): List<TelemetryPoint> = db.getTelemetryForTrip(tripId)

    override suspend fun updateTripOdometer(
        tripId: Long,
        startOdometerKm: Double,
        endOdometerKm: Double,
    ): Trip {
        val stored = requireNotNull(db.getTrip(tripId)) { "Trip must exist before it can be edited" }
        val updated = stored.correctedOdometer(startOdometerKm, endOdometerKm)
        db.updateTrip(updated)
        return updated
    }

    override suspend fun deleteTrip(tripId: Long) {
        requireNotNull(db.getTrip(tripId)) { "Trip must exist before it can be deleted" }
        db.deleteTrip(tripId)
    }

    override suspend fun restoreTrip(trip: Trip): Trip {
        val id = requireNotNull(trip.id) { "Trip must have an ID" }
        requireValidOdometer(trip.startOdometer)
        trip.endOdometer?.let { end ->
            require(end >= trip.startOdometer) { "End odometer must not precede start odometer" }
        }
        require(db.getTrip(id) == null) { "Trip ID is already in use" }
        requireNotNull(db.getBike(trip.vin)) { "Bike must exist before a trip can be restored" }
        db.restoreTrip(trip)
        return trip
    }
}

class SqliteReminderRepository(private val db: Database) : ReminderRepository {
    override suspend fun addReminder(reminder: ServiceReminder): ServiceReminder {
        val id = db.insertReminder(reminder)
        return reminder.copy(id = id)
    }

    override suspend fun getReminders(vin: String): List<ServiceReminder> = db.getRemindersForVin(vin)

    override suspend fun completeReminder(id: Long, atMillis: Long): Unit {
        db.connection.use { conn ->
            conn.setAutoCommit(false)
            val stmt = conn.prepareStatement("UPDATE service_reminder SET completed = 1, completed_at_millis = ? WHERE id = ?")
            stmt.setLong(1, atMillis)
            stmt.setLong(2, id)
            stmt.executeUpdate()
            conn.commit()
        }
    }

    override suspend fun getDueReminders(vin: String, currentOdometer: Double, currentTimeMillis: Long): List<ServiceReminder> {
        val all = db.getRemindersForVin(vin)
        return all.filter { it.isDue(currentOdometer, currentTimeMillis) }
    }

    override suspend fun updateReminder(reminder: ServiceReminder): ServiceReminder {
        requireValidReminder(reminder)
        val id = requireNotNull(reminder.id) { "Reminder must have an ID" }
        requireNotNull(db.getReminder(id)) { "Reminder must exist before it can be edited" }
        return db.updateReminder(reminder)
    }

    override suspend fun deleteReminder(id: Long) {
        requireNotNull(db.getReminder(id)) { "Reminder must exist before it can be deleted" }
        db.deleteReminder(id)
    }

    override suspend fun reopenReminder(id: Long): ServiceReminder {
        val stored = requireNotNull(db.getReminder(id)) { "Reminder must exist before it can be reopened" }
        return db.updateReminder(stored.copy(completed = false, completedAtMillis = null))
    }
}

class SqliteBikeRepository(private val db: Database) : BikeRepository {
    override suspend fun upsertBike(profile: BikeProfile): BikeProfile {
        db.upsertBike(profile)
        return profile
    }

    override suspend fun getBike(vin: String): BikeProfile? = db.getBike(vin)

    override suspend fun updateLastSynced(vin: String, atMillis: Long): Unit {
        db.connection.use { conn ->
            conn.setAutoCommit(false)
            val stmt = conn.prepareStatement("UPDATE bike_profile SET last_synced_at_millis = ? WHERE vin = ?")
            stmt.setLong(1, atMillis)
            stmt.setString(2, vin)
            stmt.executeUpdate()
            conn.commit()
        }
    }
}
