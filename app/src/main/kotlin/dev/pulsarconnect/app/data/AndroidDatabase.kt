package dev.pulsarconnect.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.pulsarconnect.core.data.BikeRepository
import dev.pulsarconnect.core.data.OdometerRepository
import dev.pulsarconnect.core.data.ReminderRepository
import dev.pulsarconnect.core.data.TripRepository
import dev.pulsarconnect.core.data.correctedOdometer
import dev.pulsarconnect.core.data.isDue
import dev.pulsarconnect.core.data.nextOccurrence
import dev.pulsarconnect.core.data.requireValidOdometer
import dev.pulsarconnect.core.data.requireValidReminder
import dev.pulsarconnect.core.model.BikeProfile
import dev.pulsarconnect.core.model.OdometerReading
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.model.TelemetryPoint
import dev.pulsarconnect.core.model.Trip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidDatabase(
    context: Context,
    name: String = "pulsar_connect.db",
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val helper = Helper(context.applicationContext, name)
    private val mutex = Mutex()
    private var closed = false

    private suspend fun <T> access(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed) { "Database is closed" }
            block(helper.writableDatabase)
        }
    }

    val trips: TripRepository = object : TripRepository {
        override suspend fun startTrip(vin: String, odometerKm: Double, lat: Double?, lon: Double?): Trip = access { db ->
            validateOdometer(odometerKm)
            db.transaction {
                requireBike(vin)
                require(query("SELECT id FROM trip WHERE vin = ? AND end_time IS NULL", arrayOf(vin)) { it.long("id") }.isEmpty()) {
                    "Bike already has an active trip"
                }
                val trip = Trip(
                    vin = vin, startTime = clock(), endTime = null, startOdometer = odometerKm,
                    endOdometer = null, distanceKm = null, avgSpeedKmph = null, maxSpeedKmph = null,
                    fuelUsedLiters = null, startLat = lat, startLon = lon, endLat = null, endLon = null,
                )
                trip.copy(id = insertOrThrow("trip", null, trip.values()))
            }
        }

        override suspend fun endTrip(trip: Trip, odometerKm: Double, lat: Double?, lon: Double?): Trip = access { db ->
            db.transaction {
                val id = requireNotNull(trip.id) { "Trip must have an ID" }
                val stored = requireNotNull(findTrip(id)) { "Trip must exist before it can be ended" }
                require(stored.endTime == null) { "Trip has already ended" }
                validateOdometer(odometerKm)
                require(odometerKm >= stored.startOdometer) { "End odometer must not precede start odometer" }
                val now = clock()
                require(now >= stored.startTime) { "End time must not precede start time" }
                val distance = odometerKm - stored.startOdometer
                val durationHours = (now - stored.startTime) / 3_600_000.0
                val ended = stored.copy(
                    endTime = now, endOdometer = odometerKm, distanceKm = distance,
                    avgSpeedKmph = if (durationHours > 0) distance / durationHours else 0.0,
                    endLat = lat, endLon = lon,
                )
                update("trip", ended.values(), "id = ?", arrayOf(id.toString()))
                ended
            }
        }

        override suspend fun getTrip(id: Long): Trip? = access { it.findTrip(id) }

        override suspend fun getActiveTrip(vin: String): Trip? = access { db ->
            db.query("SELECT * FROM trip WHERE vin = ? AND end_time IS NULL", arrayOf(vin), Cursor::trip).singleOrNull()
        }

        override suspend fun getTrips(vin: String, limit: Int, offset: Int): List<Trip> = access { db ->
            require(limit >= 0 && offset >= 0) { "Limit and offset must be nonnegative" }
            db.query(
                "SELECT * FROM trip WHERE vin = ? ORDER BY start_time DESC, id DESC LIMIT ? OFFSET ?",
                arrayOf(vin, limit.toString(), offset.toString()), Cursor::trip,
            )
        }

        override suspend fun totalDistanceKm(vin: String): Double = access { db ->
            db.query(
                "SELECT COALESCE(SUM(distance_km), 0) AS total FROM trip WHERE vin = ?",
                arrayOf(vin),
            ) { it.getDouble(it.getColumnIndexOrThrow("total")) }.single()
        }

        override suspend fun completedTripsBetween(vin: String, fromMillis: Long, toMillis: Long): Int {
            require(fromMillis <= toMillis) { "Date range must not be reversed" }
            return access { db ->
                db.query(
                    "SELECT COUNT(*) AS total FROM trip WHERE vin = ? AND end_time IS NOT NULL " +
                        "AND start_time >= ? AND start_time < ?",
                    arrayOf(vin, fromMillis.toString(), toMillis.toString()),
                ) { it.getInt(it.getColumnIndexOrThrow("total")) }.single()
            }
        }

        override suspend fun addTelemetry(tripId: Long, point: TelemetryPoint): Unit = access { db ->
            db.transaction {
                requireNotNull(findTrip(tripId)) { "Telemetry requires an existing trip" }
                insertOrThrow("telemetry", null, values(
                    "trip_id" to tripId, "timestamp" to point.timestamp, "speed_kmph" to point.speedKmph,
                    "rpm" to point.rpm, "fuel_level_percent" to point.fuelLevelPercent,
                    "engine_temp_c" to point.engineTempC, "lat" to point.lat, "lon" to point.lon,
                    "synced" to point.synced,
                ))
                Unit
            }
        }

        override suspend fun getTelemetry(tripId: Long): List<TelemetryPoint> = access { db ->
            db.query("SELECT * FROM telemetry WHERE trip_id = ? ORDER BY timestamp ASC, id ASC", arrayOf(tripId.toString()), Cursor::telemetry)
        }

        override suspend fun updateTripOdometer(
            tripId: Long,
            startOdometerKm: Double,
            endOdometerKm: Double,
        ): Trip = access { db ->
            db.transaction {
                val stored = requireNotNull(findTrip(tripId)) { "Trip must exist before it can be edited" }
                val updated = stored.correctedOdometer(startOdometerKm, endOdometerKm)
                update("trip", updated.values(), "id = ?", arrayOf(tripId.toString()))
                updated
            }
        }

        override suspend fun deleteTrip(tripId: Long): Unit = access { db ->
            db.transaction {
                requireNotNull(findTrip(tripId)) { "Trip must exist before it can be deleted" }
                // Android's schema has no ON DELETE CASCADE, so clear children explicitly.
                delete("telemetry", "trip_id = ?", arrayOf(tripId.toString()))
                delete("trip", "id = ?", arrayOf(tripId.toString()))
                Unit
            }
        }

        override suspend fun restoreTrip(trip: Trip): Trip = access { db ->
            db.transaction {
                val id = requireNotNull(trip.id) { "Trip must have an ID" }
                requireValidOdometer(trip.startOdometer)
                trip.endOdometer?.let { end ->
                    require(end >= trip.startOdometer) { "End odometer must not precede start odometer" }
                }
                requireBike(trip.vin)
                require(findTrip(id) == null) { "Trip ID is already in use" }
                insertOrThrow("trip", null, trip.values().apply { put("id", id) })
                trip
            }
        }
    }

    val odometers: OdometerRepository = object : OdometerRepository {
        override suspend fun getLatest(vin: String): OdometerReading? = access { db ->
            db.query(
                "SELECT * FROM odometer_reading WHERE vin = ?",
                arrayOf(vin), Cursor::odometer,
            ).singleOrNull()
        }

        override suspend fun setReading(
            vin: String,
            odometerKm: Double,
            recordedAtMillis: Long,
        ): OdometerReading = access { db ->
            validateOdometer(odometerKm)
            db.transaction {
                requireBike(vin)
                db.query(
                    "SELECT * FROM odometer_reading WHERE vin = ?",
                    arrayOf(vin), Cursor::odometer,
                ).singleOrNull()?.let { stored ->
                    require(odometerKm >= stored.odometerKm) {
                        "New odometer reading must not be lower than the last recorded reading."
                    }
                }
                val fields = values(
                    "vin" to vin,
                    "odometer_km" to odometerKm,
                    "recorded_at_millis" to recordedAtMillis,
                )
                if (db.update("odometer_reading", fields, "vin = ?", arrayOf(vin)) == 0) {
                    db.insertOrThrow("odometer_reading", null, fields)
                }
                OdometerReading(vin, odometerKm, recordedAtMillis)
            }
        }
    }

    val reminders: ReminderRepository = object : ReminderRepository {
        override suspend fun addReminder(reminder: ServiceReminder): ServiceReminder = access { db ->
            db.transaction {
                requireBike(reminder.vin)
                val id = insertOrThrow("service_reminder", null, values(
                    "vin" to reminder.vin, "title" to reminder.title, "description" to reminder.description,
                    "due_odometer_km" to reminder.dueOdometerKm, "due_date_millis" to reminder.dueDateMillis,
                    "repeat_every_km" to reminder.repeatEveryKm, "repeat_every_days" to reminder.repeatEveryDays,
                    "completed" to reminder.completed, "completed_at_millis" to reminder.completedAtMillis,
                    "created_at_millis" to reminder.createdAtMillis,
                ))
                reminder.copy(id = id)
            }
        }

        override suspend fun getReminders(vin: String): List<ServiceReminder> = access { it.findReminders(vin) }

        override suspend fun completeReminder(id: Long, atMillis: Long): Unit = access { db ->
            db.transaction {
                val stored = findReminder(id) ?: return@transaction Unit
                if (stored.completed) return@transaction Unit
                update(
                    "service_reminder",
                    values("completed" to true, "completed_at_millis" to atMillis),
                    "id = ?",
                    arrayOf(id.toString()),
                )
                // Recurring reminders get exactly one next occurrence; repeats are idempotent above.
                stored.nextOccurrence(atMillis)?.let { insertOrThrow("service_reminder", null, it.values()) }
                Unit
            }
        }

        override suspend fun getDueReminders(vin: String, currentOdometer: Double, currentTimeMillis: Long): List<ServiceReminder> = access { db ->
            db.findReminders(vin).filter { it.isDue(currentOdometer, currentTimeMillis) }
        }

        override suspend fun updateReminder(reminder: ServiceReminder): ServiceReminder = access { db ->
            db.transaction {
                requireValidReminder(reminder)
                val id = requireNotNull(reminder.id) { "Reminder must have an ID" }
                requireNotNull(findReminder(id)) { "Reminder must exist before it can be edited" }
                update("service_reminder", reminder.values(), "id = ?", arrayOf(id.toString()))
                reminder
            }
        }

        override suspend fun deleteReminder(id: Long): Unit = access { db ->
            db.transaction {
                requireNotNull(findReminder(id)) { "Reminder must exist before it can be deleted" }
                delete("service_reminder", "id = ?", arrayOf(id.toString()))
                Unit
            }
        }

        override suspend fun reopenReminder(id: Long): ServiceReminder = access { db ->
            db.transaction {
                val stored = requireNotNull(findReminder(id)) { "Reminder must exist before it can be reopened" }
                val reopened = stored.copy(completed = false, completedAtMillis = null)
                update("service_reminder", reopened.values(), "id = ?", arrayOf(id.toString()))
                reopened
            }
        }

        override suspend fun restoreReminder(reminder: ServiceReminder): ServiceReminder = access { db ->
            db.transaction {
                val id = requireNotNull(reminder.id) { "Reminder must have an ID" }
                requireBike(reminder.vin)
                require(findReminder(id) == null) { "Reminder ID is already in use" }
                insertOrThrow("service_reminder", null, reminder.values().apply { put("id", id) })
                reminder
            }
        }

        override suspend fun getReminder(id: Long): ServiceReminder? = access { db -> db.findReminder(id) }
    }

    val bikes: BikeRepository = object : BikeRepository {
        override suspend fun upsertBike(profile: BikeProfile): BikeProfile = access { db ->
            db.transaction {
                val fields = values(
                    "vin" to profile.vin, "model" to profile.model, "model_image_url" to profile.modelImageUrl,
                    "nickname" to profile.nickname, "purchase_date_millis" to profile.purchaseDateMillis,
                    "last_synced_at_millis" to profile.lastSyncedAtMillis,
                )
                // UPDATE then INSERT works on API 26 and never deletes the parent row or its children.
                if (update("bike_profile", fields, "vin = ?", arrayOf(profile.vin)) == 0) {
                    insertOrThrow("bike_profile", null, fields)
                }
                profile
            }
        }

        override suspend fun getBike(vin: String): BikeProfile? = access { db ->
            db.query("SELECT * FROM bike_profile WHERE vin = ?", arrayOf(vin), Cursor::bike).singleOrNull()
        }

        override suspend fun updateLastSynced(vin: String, atMillis: Long): Unit = access { db ->
            db.update("bike_profile", values("last_synced_at_millis" to atMillis), "vin = ?", arrayOf(vin))
            Unit
        }
    }

    // The integration API is synchronous; dispatch even helper.close() off the caller's thread.
    override fun close() = runBlocking(Dispatchers.IO) {
        mutex.withLock {
            if (!closed) {
                helper.close()
                closed = true
            }
        }
    }

    private class Helper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 3) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE bike_profile (
                    vin TEXT PRIMARY KEY NOT NULL,
                    model TEXT, model_image_url TEXT, nickname TEXT,
                    purchase_date_millis INTEGER, last_synced_at_millis INTEGER
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE trip (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    vin TEXT NOT NULL REFERENCES bike_profile(vin),
                    start_time INTEGER NOT NULL, end_time INTEGER,
                    start_odometer REAL NOT NULL, end_odometer REAL, distance_km REAL,
                    avg_speed_kmph REAL, max_speed_kmph REAL, fuel_used_liters REAL,
                    start_lat REAL, start_lon REAL, end_lat REAL, end_lon REAL,
                    synced INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX trip_vin_start_id ON trip(vin, start_time DESC, id DESC)")
            db.execSQL("CREATE UNIQUE INDEX trip_one_active_per_vin ON trip(vin) WHERE end_time IS NULL")
            db.execSQL("""
                CREATE TABLE telemetry (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    trip_id INTEGER NOT NULL REFERENCES trip(id),
                    timestamp INTEGER NOT NULL, speed_kmph REAL, rpm INTEGER,
                    fuel_level_percent INTEGER, engine_temp_c INTEGER, lat REAL, lon REAL,
                    synced INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX telemetry_trip_timestamp ON telemetry(trip_id, timestamp)")
            db.execSQL("""
                CREATE TABLE service_reminder (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    vin TEXT NOT NULL REFERENCES bike_profile(vin),
                    title TEXT NOT NULL, description TEXT, due_odometer_km REAL, due_date_millis INTEGER,
                    repeat_every_km REAL, repeat_every_days INTEGER,
                    completed INTEGER NOT NULL DEFAULT 0, completed_at_millis INTEGER,
                    created_at_millis INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX reminder_vin ON service_reminder(vin)")
            db.execSQL("""
                CREATE TABLE odometer_reading (
                    vin TEXT PRIMARY KEY NOT NULL REFERENCES bike_profile(vin),
                    odometer_km REAL NOT NULL,
                    recorded_at_millis INTEGER NOT NULL
                )
            """.trimIndent())
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            var version = oldVersion
            if (version < 2) {
                // Existing v1 reminders keep their rows and simply have no repeat interval.
                db.execSQL("ALTER TABLE service_reminder ADD COLUMN repeat_every_km REAL")
                db.execSQL("ALTER TABLE service_reminder ADD COLUMN repeat_every_days INTEGER")
                version = 2
            }
            if (version < 3) {
                db.execSQL("""
                    CREATE TABLE odometer_reading (
                        vin TEXT PRIMARY KEY NOT NULL REFERENCES bike_profile(vin),
                        odometer_km REAL NOT NULL,
                        recorded_at_millis INTEGER NOT NULL
                    )
                """.trimIndent())
                version = 3
            }
            check(version == newVersion) { "No migration from schema $oldVersion to $newVersion" }
        }
    }
}

private fun validateOdometer(odometer: Double) {
    require(odometer.isFinite() && odometer >= 0) { "Odometer must be finite and nonnegative" }
}

private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        val result = block()
        setTransactionSuccessful()
        return result
    } finally {
        endTransaction()
    }
}

private fun <T> SQLiteDatabase.query(sql: String, args: Array<String>, map: (Cursor) -> T): List<T> =
    rawQuery(sql, args).use { cursor -> buildList { while (cursor.moveToNext()) add(map(cursor)) } }

private fun SQLiteDatabase.findTrip(id: Long): Trip? =
    query("SELECT * FROM trip WHERE id = ?", arrayOf(id.toString()), Cursor::trip).singleOrNull()

private fun SQLiteDatabase.requireBike(vin: String) {
    require(query("SELECT vin FROM bike_profile WHERE vin = ?", arrayOf(vin)) { it.string("vin") }.isNotEmpty()) {
        "Bike must exist before adding trips or reminders"
    }
}

private fun SQLiteDatabase.findReminders(vin: String): List<ServiceReminder> = query(
    "SELECT * FROM service_reminder WHERE vin = ? ORDER BY due_date_millis IS NULL, due_date_millis ASC, id ASC",
    arrayOf(vin), Cursor::reminder,
)

private fun SQLiteDatabase.findReminder(id: Long): ServiceReminder? = query(
    "SELECT * FROM service_reminder WHERE id = ?", arrayOf(id.toString()), Cursor::reminder,
).singleOrNull()

private fun ServiceReminder.values(): ContentValues = values(
    "vin" to vin, "title" to title, "description" to description,
    "due_odometer_km" to dueOdometerKm, "due_date_millis" to dueDateMillis,
    "repeat_every_km" to repeatEveryKm, "repeat_every_days" to repeatEveryDays,
    "completed" to completed, "completed_at_millis" to completedAtMillis,
    "created_at_millis" to createdAtMillis,
)

private fun values(vararg fields: Pair<String, Any?>): ContentValues = ContentValues(fields.size).apply {
    fields.forEach { (key, value) ->
        when (value) {
            null -> putNull(key)
            is String -> put(key, value)
            is Long -> put(key, value)
            is Int -> put(key, value)
            is Double -> put(key, value)
            is Boolean -> put(key, if (value) 1 else 0)
            else -> error("Unsupported column type for $key")
        }
    }
}

private fun Trip.values(): ContentValues = values(
    "vin" to vin, "start_time" to startTime, "end_time" to endTime,
    "start_odometer" to startOdometer, "end_odometer" to endOdometer, "distance_km" to distanceKm,
    "avg_speed_kmph" to avgSpeedKmph, "max_speed_kmph" to maxSpeedKmph, "fuel_used_liters" to fuelUsedLiters,
    "start_lat" to startLat, "start_lon" to startLon, "end_lat" to endLat, "end_lon" to endLon, "synced" to synced,
)

private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
private fun Cursor.nullableString(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
private fun Cursor.nullableLong(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
private fun Cursor.nullableDouble(name: String): Double? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getDouble(it) }
private fun Cursor.nullableInt(name: String): Int? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getInt(it) }

private fun Cursor.odometer(): OdometerReading = OdometerReading(
    vin = string("vin"),
    odometerKm = getDouble(getColumnIndexOrThrow("odometer_km")),
    recordedAtMillis = long("recorded_at_millis"),
)

private fun Cursor.trip(): Trip = Trip(
    id = long("id"), vin = string("vin"), startTime = long("start_time"), endTime = nullableLong("end_time"),
    startOdometer = getDouble(getColumnIndexOrThrow("start_odometer")), endOdometer = nullableDouble("end_odometer"),
    distanceKm = nullableDouble("distance_km"), avgSpeedKmph = nullableDouble("avg_speed_kmph"),
    maxSpeedKmph = nullableDouble("max_speed_kmph"), fuelUsedLiters = nullableDouble("fuel_used_liters"),
    startLat = nullableDouble("start_lat"), startLon = nullableDouble("start_lon"),
    endLat = nullableDouble("end_lat"), endLon = nullableDouble("end_lon"), synced = long("synced") != 0L,
)

private fun Cursor.telemetry(): TelemetryPoint = TelemetryPoint(
    id = long("id"), tripId = long("trip_id"), timestamp = long("timestamp"), speedKmph = nullableDouble("speed_kmph"),
    rpm = nullableInt("rpm"), fuelLevelPercent = nullableInt("fuel_level_percent"), engineTempC = nullableInt("engine_temp_c"),
    lat = nullableDouble("lat"), lon = nullableDouble("lon"), synced = long("synced") != 0L,
)

private fun Cursor.reminder(): ServiceReminder = ServiceReminder(
    id = long("id"), vin = string("vin"), title = string("title"), description = nullableString("description"),
    dueOdometerKm = nullableDouble("due_odometer_km"), dueDateMillis = nullableLong("due_date_millis"),
    repeatEveryKm = nullableDouble("repeat_every_km"), repeatEveryDays = nullableLong("repeat_every_days"),
    completed = long("completed") != 0L, completedAtMillis = nullableLong("completed_at_millis"), createdAtMillis = long("created_at_millis"),
)

private fun Cursor.bike(): BikeProfile = BikeProfile(
    vin = string("vin"), model = nullableString("model"), modelImageUrl = nullableString("model_image_url"),
    nickname = nullableString("nickname"), purchaseDateMillis = nullableLong("purchase_date_millis"),
    lastSyncedAtMillis = nullableLong("last_synced_at_millis"),
)
