package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.BikeProfile
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.model.TelemetryPoint
import dev.pulsarconnect.core.model.Trip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TripRepository {
    suspend fun startTrip(vin: String, odometerKm: Double, lat: Double?, lon: Double?): Trip
    suspend fun endTrip(trip: Trip, odometerKm: Double, lat: Double?, lon: Double?): Trip
    suspend fun getTrip(id: Long): Trip?
    suspend fun getTrips(vin: String, limit: Int, offset: Int): List<Trip>
    suspend fun addTelemetry(tripId: Long, point: TelemetryPoint)
    suspend fun getTelemetry(tripId: Long): List<TelemetryPoint>
}

interface ReminderRepository {
    suspend fun addReminder(reminder: ServiceReminder): ServiceReminder
    suspend fun getReminders(vin: String): List<ServiceReminder>
    suspend fun completeReminder(id: Long, atMillis: Long)
    suspend fun getDueReminders(vin: String, currentOdometer: Double, currentTimeMillis: Long): List<ServiceReminder>
}

interface BikeRepository {
    suspend fun upsertBike(profile: BikeProfile): BikeProfile
    suspend fun getBike(vin: String): BikeProfile?
    suspend fun updateLastSynced(vin: String, atMillis: Long)
}

class SqliteTripRepository(private val db: Database) : TripRepository {
    override suspend fun startTrip(vin: String, odometerKm: Double, lat: Double?, lon: Double?): Trip {
        val now = System.currentTimeMillis()
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
        val now = System.currentTimeMillis()
        val distance = odometerKm - trip.startOdometer
        val durationHours = (now - trip.startTime) / 3_600_000.0
        val avgSpeed = if (durationHours > 0) distance / durationHours else 0.0
        val ended = trip.copy(
            endTime = now,
            endOdometer = odometerKm,
            distanceKm = distance,
            avgSpeedKmph = avgSpeed,
            endLat = lat,
            endLon = lon,
        )
        db.updateTrip(ended)
        return ended
    }

    override suspend fun getTrip(id: Long): Trip? = db.getTrip(id)

    override suspend fun getTrips(vin: String, limit: Int, offset: Int): List<Trip> = db.getTripsForVin(vin, limit, offset)

    override suspend fun addTelemetry(tripId: Long, point: TelemetryPoint) {
        db.insertTelemetry(point.copy(tripId = tripId))
    }

    override suspend fun getTelemetry(tripId: Long): List<TelemetryPoint> = db.getTelemetryForTrip(tripId)
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
        return all.filter { r ->
            !r.completed &&
            (r.dueDateMillis == null || r.dueDateMillis!! <= currentTimeMillis) &&
            (r.dueOdometerKm == null || r.dueOdometerKm!! <= currentOdometer)
        }
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

// --- In-memory implementations for tests ---
class InMemoryTripRepository : TripRepository {
    private val trips = mutableMapOf<Long, Trip>()
    private val telemetry = mutableMapOf<Long, MutableList<TelemetryPoint>>()
    private val mutex = Mutex()
    private var nextId = 1L

    override suspend fun startTrip(vin: String, odometerKm: Double, lat: Double?, lon: Double?): Trip = mutex.withLock {
        val now = System.currentTimeMillis()
        val trip = Trip(
            id = nextId,
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
        nextId++
        trips[trip.id!!] = trip
        telemetry[trip.id!!] = mutableListOf()
        trip
    }

    override suspend fun endTrip(trip: Trip, odometerKm: Double, lat: Double?, lon: Double?): Trip = mutex.withLock {
        val now = System.currentTimeMillis()
        val distance = odometerKm - trip.startOdometer
        val durationHours = (now - trip.startTime) / 3_600_000.0
        val avgSpeed = if (durationHours > 0) distance / durationHours else 0.0
        val ended = trip.copy(
            id = trip.id,
            endTime = now,
            endOdometer = odometerKm,
            distanceKm = distance,
            avgSpeedKmph = avgSpeed,
            endLat = lat,
            endLon = lon,
        )
        trips[ended.id!!] = ended
        ended
    }

    override suspend fun getTrip(id: Long): Trip? = trips[id]

    override suspend fun getTrips(vin: String, limit: Int, offset: Int): List<Trip> = trips.values
        .filter { it.vin == vin }
        .sortedByDescending { it.startTime }
        .drop(offset)
        .take(limit)
        .toList()

    override suspend fun addTelemetry(tripId: Long, point: TelemetryPoint): Unit = mutex.withLock {
        telemetry[tripId]?.add(point)
    }

    override suspend fun getTelemetry(tripId: Long): List<TelemetryPoint> = telemetry[tripId]?.toList() ?: emptyList()
}

class InMemoryReminderRepository : ReminderRepository {
    private val reminders = mutableMapOf<Long, ServiceReminder>()
    private val mutex = Mutex()
    private var nextId = 1L

    override suspend fun addReminder(reminder: ServiceReminder): ServiceReminder = mutex.withLock {
        val withId = reminder.copy(id = nextId)
        nextId++
        reminders[withId.id!!] = withId
        withId
    }

    override suspend fun getReminders(vin: String): List<ServiceReminder> = reminders.values
        .filter { it.vin == vin }
        .sortedBy { it.dueDateMillis ?: Long.MAX_VALUE }
        .toList()

    override suspend fun completeReminder(id: Long, atMillis: Long): Unit = mutex.withLock {
        reminders[id]?.let { reminders[id] = it.copy(completed = true, completedAtMillis = atMillis) }
    }

    override suspend fun getDueReminders(vin: String, currentOdometer: Double, currentTimeMillis: Long): List<ServiceReminder> = reminders.values
        .filter { it.vin == vin && !it.completed }
        .filter { it.dueDateMillis == null || it.dueDateMillis!! <= currentTimeMillis }
        .filter { it.dueOdometerKm == null || it.dueOdometerKm!! <= currentOdometer }
        .toList()
}

class InMemoryBikeRepository : BikeRepository {
    private val bikes = mutableMapOf<String, BikeProfile>()
    private val mutex = Mutex()

    override suspend fun upsertBike(profile: BikeProfile): BikeProfile = mutex.withLock {
        bikes[profile.vin] = profile
        profile
    }

    override suspend fun getBike(vin: String): BikeProfile? = bikes[vin]

    override suspend fun updateLastSynced(vin: String, atMillis: Long): Unit = mutex.withLock {
        bikes[vin]?.let { bikes[vin] = it.copy(lastSyncedAtMillis = atMillis) }
    }
}