package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.BikeProfile
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.model.TelemetryPoint
import dev.pulsarconnect.core.model.Trip
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TripRepository {
    /** Requires a finite, nonnegative odometer reading. */
    suspend fun startTrip(vin: String, odometerKm: Double, lat: Double?, lon: Double?): Trip
    /**
     * Ends the stored active trip identified by [trip]. Other caller-supplied fields are ignored.
     * Throws [IllegalArgumentException] for a missing/ended trip, invalid odometer, or backwards clock.
     */
    suspend fun endTrip(trip: Trip, odometerKm: Double, lat: Double?, lon: Double?): Trip
    suspend fun getTrip(id: Long): Trip?
    /** Finds an unended trip for this VIN independently of history ordering and pagination. */
    suspend fun getActiveTrip(vin: String): Trip?
    /** Newest start time first, then highest ID first for equal start times. */
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

// --- In-memory implementations for tests ---
class InMemoryTripRepository(
    private val clock: () -> Long = System::currentTimeMillis,
) : TripRepository {
    private val trips = mutableMapOf<Long, Trip>()
    private val telemetry = mutableMapOf<Long, MutableList<TelemetryPoint>>()
    private val mutex = Mutex()
    private var nextId = 1L

    override suspend fun startTrip(vin: String, odometerKm: Double, lat: Double?, lon: Double?): Trip = mutex.withLock {
        require(odometerKm.isFinite() && odometerKm >= 0) { "Odometer must be finite and nonnegative" }
        val now = clock()
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
        val stored = requireNotNull(trips[trip.id]) { "Trip must exist before it can be ended" }
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
        trips[ended.id!!] = ended
        ended
    }

    override suspend fun getTrip(id: Long): Trip? = trips[id]

    override suspend fun getActiveTrip(vin: String): Trip? = mutex.withLock {
        trips.values.firstOrNull { it.vin == vin && it.endTime == null }
    }

    override suspend fun getTrips(vin: String, limit: Int, offset: Int): List<Trip> = trips.values
        .filter { it.vin == vin }
        .sortedWith(compareByDescending<Trip> { it.startTime }.thenByDescending { it.id })
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
        .filter { it.vin == vin && it.isDue(currentOdometer, currentTimeMillis) }
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
