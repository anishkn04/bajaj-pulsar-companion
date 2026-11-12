package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.BikeProfile
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.model.TelemetryPoint
import dev.pulsarconnect.core.model.Trip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

class Database private constructor(
    private val dataSource: SQLiteDataSource,
    private val keepAlive: java.sql.Connection? = null,
) : AutoCloseable {
    override fun close() {
        keepAlive?.close()
    }

    val connection: java.sql.Connection
        get() = dataSource.connection

    companion object {
        const val DB_NAME = "pulsar_connect.db"
        const val VERSION = 1

        fun create(path: String): Database {
            val config = SQLiteConfig().apply {
                setJournalMode(SQLiteConfig.JournalMode.WAL)
                setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
                busyTimeout = 5000
                enforceForeignKeys(true)
            }
            // A named shared-memory database needs an anchor connection to outlive operation connections.
            val url = if (path == ":memory:") {
                "jdbc:sqlite:file:${java.util.UUID.randomUUID()}?mode=memory&cache=shared"
            } else {
                "jdbc:sqlite:$path"
            }
            val ds = SQLiteDataSource(config).apply { this.url = url }
            val db = Database(ds, if (path == ":memory:") ds.connection else null)
            try {
                db.initSchema()
                return db
            } catch (failure: Exception) {
                db.close()
                throw failure
            }
        }
    }

    private fun initSchema() {
        dataSource.connection.use { conn ->
            conn.setAutoCommit(false)
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS bike_profile (
                    vin TEXT PRIMARY KEY,
                    model TEXT,
                    model_image_url TEXT,
                    nickname TEXT,
                    purchase_date_millis INTEGER,
                    last_synced_at_millis INTEGER
                )
            """.trimIndent())
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS trip (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    vin TEXT NOT NULL,
                    start_time INTEGER NOT NULL,
                    end_time INTEGER,
                    start_odometer REAL NOT NULL,
                    end_odometer REAL,
                    distance_km REAL,
                    avg_speed_kmph REAL,
                    max_speed_kmph REAL,
                    fuel_used_liters REAL,
                    start_lat REAL,
                    start_lon REAL,
                    end_lat REAL,
                    end_lon REAL,
                    synced INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (vin) REFERENCES bike_profile(vin)
                )
            """.trimIndent())
            conn.createStatement().executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_trip_vin_time ON trip(vin, start_time DESC)
            """.trimIndent())
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS telemetry_point (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    trip_id INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    speed_kmph REAL,
                    rpm INTEGER,
                    fuel_level_percent INTEGER,
                    engine_temp_c INTEGER,
                    lat REAL,
                    lon REAL,
                    synced INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (trip_id) REFERENCES trip(id) ON DELETE CASCADE
                )
            """.trimIndent())
            conn.createStatement().executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_telemetry_trip_time ON telemetry_point(trip_id, timestamp)
            """.trimIndent())
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS service_reminder (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    vin TEXT NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT,
                    due_odometer_km REAL,
                    due_date_millis INTEGER,
                    repeat_every_km REAL,
                    repeat_every_days INTEGER,
                    completed INTEGER NOT NULL DEFAULT 0,
                    completed_at_millis INTEGER,
                    created_at_millis INTEGER NOT NULL,
                    FOREIGN KEY (vin) REFERENCES bike_profile(vin)
                )
            """.trimIndent())
            // Existing files predate the repeat columns, so add them in place.
            ensureColumn(conn, "service_reminder", "repeat_every_km", "REAL")
            ensureColumn(conn, "service_reminder", "repeat_every_days", "INTEGER")
            conn.createStatement().executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_reminder_vin_due ON service_reminder(vin, due_odometer_km, due_date_millis)
            """.trimIndent())
            conn.commit()
        }
    }

    private fun ensureColumn(conn: java.sql.Connection, table: String, column: String, type: String) {
        val exists = conn.createStatement().executeQuery("PRAGMA table_info($table)").use { rs ->
            var found = false
            while (rs.next()) if (rs.getString("name") == column) found = true
            found
        }
        if (!exists) {
            conn.createStatement().executeUpdate("ALTER TABLE $table ADD COLUMN $column $type")
        }
    }

    // --- BikeProfile ---
    suspend fun upsertBike(profile: BikeProfile): Long = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.setAutoCommit(false)
            val stmt = conn.prepareStatement("""
                INSERT INTO bike_profile (vin, model, model_image_url, nickname, purchase_date_millis, last_synced_at_millis)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(vin) DO UPDATE SET
                    model = excluded.model,
                    model_image_url = excluded.model_image_url,
                    nickname = excluded.nickname,
                    purchase_date_millis = excluded.purchase_date_millis,
                    last_synced_at_millis = excluded.last_synced_at_millis
            """.trimIndent())
            stmt.setString(1, profile.vin)
            stmt.setString(2, profile.model)
            stmt.setString(3, profile.modelImageUrl)
            stmt.setString(4, profile.nickname)
            stmt.setObject(5, profile.purchaseDateMillis)
            stmt.setObject(6, profile.lastSyncedAtMillis)
            stmt.executeUpdate()
            conn.commit()
            1L
        }
    }

    suspend fun getBike(vin: String): BikeProfile? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val stmt = conn.prepareStatement("SELECT * FROM bike_profile WHERE vin = ?")
            stmt.setString(1, vin)
            stmt.executeQuery().use { rs ->
                if (rs.next()) mapBike(rs) else null
            }
        }
    }

    private fun mapBike(rs: java.sql.ResultSet): BikeProfile = BikeProfile(
        vin = rs.getString("vin"),
        model = rs.getString("model"),
        modelImageUrl = rs.getString("model_image_url"),
        nickname = rs.getString("nickname"),
        purchaseDateMillis = rs.getNullableLong("purchase_date_millis"),
        lastSyncedAtMillis = rs.getNullableLong("last_synced_at_millis"),
    )

    // --- Trip ---
    suspend fun insertTrip(trip: Trip): Long = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.setAutoCommit(false)
            val stmt = conn.prepareStatement("""
                INSERT INTO trip (vin, start_time, end_time, start_odometer, end_odometer, distance_km, avg_speed_kmph,
                                  max_speed_kmph, fuel_used_liters, start_lat, start_lon, end_lat, end_lon, synced)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(), java.sql.Statement.RETURN_GENERATED_KEYS)
            stmt.setString(1, trip.vin)
            stmt.setLong(2, trip.startTime)
            stmt.setObject(3, trip.endTime)
            stmt.setDouble(4, trip.startOdometer)
            stmt.setObject(5, trip.endOdometer)
            stmt.setObject(6, trip.distanceKm)
            stmt.setObject(7, trip.avgSpeedKmph)
            stmt.setObject(8, trip.maxSpeedKmph)
            stmt.setObject(9, trip.fuelUsedLiters)
            stmt.setObject(10, trip.startLat)
            stmt.setObject(11, trip.startLon)
            stmt.setObject(12, trip.endLat)
            stmt.setObject(13, trip.endLon)
            stmt.setInt(14, if (trip.synced) 1 else 0)
            stmt.executeUpdate()
            val keys = stmt.generatedKeys
            val id = if (keys.next()) keys.getLong(1) else -1
            conn.commit()
            id
        }
    }

    suspend fun updateTrip(trip: Trip) = updateTrip(trip, requireActive = false)

    internal suspend fun finishTrip(trip: Trip) = updateTrip(trip, requireActive = true)

    suspend fun deleteTrip(id: Long) = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.setAutoCommit(false)
            conn.prepareStatement("DELETE FROM telemetry_point WHERE trip_id = ?").use { stmt ->
                stmt.setLong(1, id)
                stmt.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM trip WHERE id = ?").use { stmt ->
                stmt.setLong(1, id)
                stmt.executeUpdate()
            }
            conn.commit()
        }
    }

    /** Re-inserts a trip with its original ID so a delete can be undone. */
    suspend fun restoreTrip(trip: Trip) = withContext(Dispatchers.IO) {
        val id = requireNotNull(trip.id) { "Trip must have an ID" }
        dataSource.connection.use { conn ->
            conn.setAutoCommit(false)
            val stmt = conn.prepareStatement("""
                INSERT INTO trip (id, vin, start_time, end_time, start_odometer, end_odometer, distance_km, avg_speed_kmph,
                                  max_speed_kmph, fuel_used_liters, start_lat, start_lon, end_lat, end_lon, synced)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent())
            stmt.setLong(1, id)
            stmt.setString(2, trip.vin)
            stmt.setLong(3, trip.startTime)
            stmt.setObject(4, trip.endTime)
            stmt.setDouble(5, trip.startOdometer)
            stmt.setObject(6, trip.endOdometer)
            stmt.setObject(7, trip.distanceKm)
            stmt.setObject(8, trip.avgSpeedKmph)
            stmt.setObject(9, trip.maxSpeedKmph)
            stmt.setObject(10, trip.fuelUsedLiters)
            stmt.setObject(11, trip.startLat)
            stmt.setObject(12, trip.startLon)
            stmt.setObject(13, trip.endLat)
            stmt.setObject(14, trip.endLon)
            stmt.setInt(15, if (trip.synced) 1 else 0)
            stmt.executeUpdate()
            conn.commit()
        }
    }

    private suspend fun updateTrip(trip: Trip, requireActive: Boolean) = withContext(Dispatchers.IO) {
        require(trip.id != null) { "Trip must have an ID" }
        dataSource.connection.use { conn ->
            conn.setAutoCommit(false)
            val stmt = conn.prepareStatement("""
                UPDATE trip SET start_odometer = ?, end_time = ?, end_odometer = ?, distance_km = ?, avg_speed_kmph = ?,
                                max_speed_kmph = ?, fuel_used_liters = ?, end_lat = ?, end_lon = ?, synced = ?
                WHERE id = ? ${if (requireActive) "AND end_time IS NULL" else ""}
            """.trimIndent())
            stmt.setDouble(1, trip.startOdometer)
            stmt.setObject(2, trip.endTime)
            stmt.setObject(3, trip.endOdometer)
            stmt.setObject(4, trip.distanceKm)
            stmt.setObject(5, trip.avgSpeedKmph)
            stmt.setObject(6, trip.maxSpeedKmph)
            stmt.setObject(7, trip.fuelUsedLiters)
            stmt.setObject(8, trip.endLat)
            stmt.setObject(9, trip.endLon)
            stmt.setInt(10, if (trip.synced) 1 else 0)
            stmt.setLong(11, trip.id!!)
            val updated = stmt.executeUpdate()
            require(!requireActive || updated == 1) { "Trip must exist and be active to end it" }
            conn.commit()
        }
    }

    suspend fun getTrip(id: Long): Trip? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val stmt = conn.prepareStatement("SELECT * FROM trip WHERE id = ?")
            stmt.setLong(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) mapTrip(rs) else null
            }
        }
    }

    suspend fun getActiveTrip(vin: String): Trip? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM trip WHERE vin = ? AND end_time IS NULL LIMIT 1").use { stmt ->
                stmt.setString(1, vin)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) mapTrip(rs) else null
                }
            }
        }
    }

    suspend fun getTripsForVin(vin: String, limit: Int = 100, offset: Int = 0): List<Trip> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val stmt = conn.prepareStatement("SELECT * FROM trip WHERE vin = ? ORDER BY start_time DESC, id DESC LIMIT ? OFFSET ?")
            stmt.setString(1, vin)
            stmt.setInt(2, limit)
            stmt.setInt(3, offset)
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<Trip>()
                while (rs.next()) list.add(mapTrip(rs))
                list
            }
        }
    }

    private fun mapTrip(rs: java.sql.ResultSet): Trip = Trip(
        id = rs.getLong("id"),
        vin = rs.getString("vin"),
        startTime = rs.getLong("start_time"),
        endTime = rs.getNullableLong("end_time"),
        startOdometer = rs.getDouble("start_odometer"),
        endOdometer = rs.getNullableDouble("end_odometer"),
        distanceKm = rs.getNullableDouble("distance_km"),
        avgSpeedKmph = rs.getNullableDouble("avg_speed_kmph"),
        maxSpeedKmph = rs.getNullableDouble("max_speed_kmph"),
        fuelUsedLiters = rs.getNullableDouble("fuel_used_liters"),
        startLat = rs.getNullableDouble("start_lat"),
        startLon = rs.getNullableDouble("start_lon"),
        endLat = rs.getNullableDouble("end_lat"),
        endLon = rs.getNullableDouble("end_lon"),
        synced = rs.getInt("synced") == 1,
    )

    // --- TelemetryPoint ---
    suspend fun insertTelemetry(point: TelemetryPoint): Long = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.setAutoCommit(false)
            val stmt = conn.prepareStatement("""
                INSERT INTO telemetry_point (trip_id, timestamp, speed_kmph, rpm, fuel_level_percent,
                                             engine_temp_c, lat, lon, synced)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(), java.sql.Statement.RETURN_GENERATED_KEYS)
            stmt.setLong(1, point.tripId)
            stmt.setLong(2, point.timestamp)
            stmt.setObject(3, point.speedKmph)
            stmt.setObject(4, point.rpm)
            stmt.setObject(5, point.fuelLevelPercent)
            stmt.setObject(6, point.engineTempC)
            stmt.setObject(7, point.lat)
            stmt.setObject(8, point.lon)
            stmt.setInt(9, if (point.synced) 1 else 0)
            stmt.executeUpdate()
            val keys = stmt.generatedKeys
            val id = if (keys.next()) keys.getLong(1) else -1
            conn.commit()
            id
        }
    }

    suspend fun getTelemetryForTrip(tripId: Long): List<TelemetryPoint> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val stmt = conn.prepareStatement("SELECT * FROM telemetry_point WHERE trip_id = ? ORDER BY timestamp")
            stmt.setLong(1, tripId)
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<TelemetryPoint>()
                while (rs.next()) list.add(mapTelemetry(rs))
                list
            }
        }
    }

    private fun mapTelemetry(rs: java.sql.ResultSet): TelemetryPoint = TelemetryPoint(
        id = rs.getLong("id"),
        tripId = rs.getLong("trip_id"),
        timestamp = rs.getLong("timestamp"),
        speedKmph = rs.getNullableDouble("speed_kmph"),
        rpm = rs.getNullableInt("rpm"),
        fuelLevelPercent = rs.getNullableInt("fuel_level_percent"),
        engineTempC = rs.getNullableInt("engine_temp_c"),
        lat = rs.getNullableDouble("lat"),
        lon = rs.getNullableDouble("lon"),
        synced = rs.getInt("synced") == 1,
    )

    // --- ServiceReminder ---
    suspend fun insertReminder(reminder: ServiceReminder): Long = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.setAutoCommit(false)
            val id = insertReminderRow(conn, reminder)
            conn.commit()
            id
        }
    }

    /** Re-inserts a reminder with its original ID so a restore can preserve references. */
    suspend fun insertReminderWithId(reminder: ServiceReminder): ServiceReminder = withContext(Dispatchers.IO) {
        val id = requireNotNull(reminder.id) { "Reminder must have an ID" }
        dataSource.connection.use { conn ->
            conn.setAutoCommit(false)
            insertReminderRow(conn, reminder, id)
            conn.commit()
            reminder
        }
    }

    /**
     * Marks a reminder complete and, for a recurring reminder, inserts exactly one next occurrence
     * in the same transaction. Completing an already completed reminder is a no-op, so retries
     * cannot create a loop of occurrences.
     */
    suspend fun completeReminder(id: Long, atMillis: Long): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.setAutoCommit(false)
            try {
                val stored = getReminderRow(conn, id)
                if (stored != null && !stored.completed) {
                    val stmt = conn.prepareStatement(
                        "UPDATE service_reminder SET completed = 1, completed_at_millis = ? WHERE id = ?",
                    )
                    stmt.setLong(1, atMillis)
                    stmt.setLong(2, id)
                    stmt.executeUpdate()
                    stored.nextOccurrence(atMillis)?.let { insertReminderRow(conn, it) }
                }
                conn.commit()
            } catch (failure: Exception) {
                conn.rollback()
                throw failure
            }
        }
    }

    private fun getReminderRow(conn: java.sql.Connection, id: Long): ServiceReminder? {
        val stmt = conn.prepareStatement("SELECT * FROM service_reminder WHERE id = ?")
        stmt.setLong(1, id)
        return stmt.executeQuery().use { rs -> if (rs.next()) mapReminder(rs) else null }
    }

    private fun insertReminderRow(
        conn: java.sql.Connection,
        reminder: ServiceReminder,
        id: Long? = null,
    ): Long {
        val columns = "vin, title, description, due_odometer_km, due_date_millis, repeat_every_km, " +
            "repeat_every_days, completed, completed_at_millis, created_at_millis"
        val placeholders = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?"
        val sql = if (id == null) {
            "INSERT INTO service_reminder ($columns) VALUES ($placeholders)"
        } else {
            "INSERT INTO service_reminder (id, $columns) VALUES (?, $placeholders)"
        }
        val stmt = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)
        var index = 1
        if (id != null) stmt.setLong(index++, id)
        stmt.setString(index++, reminder.vin)
        stmt.setString(index++, reminder.title)
        stmt.setString(index++, reminder.description)
        stmt.setObject(index++, reminder.dueOdometerKm)
        stmt.setObject(index++, reminder.dueDateMillis)
        stmt.setObject(index++, reminder.repeatEveryKm)
        stmt.setObject(index++, reminder.repeatEveryDays)
        stmt.setInt(index++, if (reminder.completed) 1 else 0)
        stmt.setObject(index++, reminder.completedAtMillis)
        stmt.setLong(index, reminder.createdAtMillis)
        stmt.executeUpdate()
        if (id != null) return id
        val keys = stmt.generatedKeys
        return if (keys.next()) keys.getLong(1) else -1
    }

    suspend fun getRemindersForVin(vin: String): List<ServiceReminder> = withContext(Dispatchers.IO) {        dataSource.connection.use { conn ->
            val stmt = conn.prepareStatement("SELECT * FROM service_reminder WHERE vin = ? ORDER BY due_date_millis, due_odometer_km")
            stmt.setString(1, vin)
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<ServiceReminder>()
                while (rs.next()) list.add(mapReminder(rs))
                list
            }
        }
    }

    suspend fun getReminder(id: Long): ServiceReminder? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val stmt = conn.prepareStatement("SELECT * FROM service_reminder WHERE id = ?")
            stmt.setLong(1, id)
            stmt.executeQuery().use { rs -> if (rs.next()) mapReminder(rs) else null }
        }
    }

    suspend fun updateReminder(reminder: ServiceReminder): ServiceReminder = withContext(Dispatchers.IO) {
        val id = requireNotNull(reminder.id) { "Reminder must have an ID" }
        dataSource.connection.use { conn ->
            conn.setAutoCommit(false)
            val stmt = conn.prepareStatement("""
                UPDATE service_reminder SET title = ?, description = ?, due_odometer_km = ?, due_date_millis = ?,
                                            repeat_every_km = ?, repeat_every_days = ?,
                                            completed = ?, completed_at_millis = ?
                WHERE id = ?
            """.trimIndent())
            stmt.setString(1, reminder.title)
            stmt.setString(2, reminder.description)
            stmt.setObject(3, reminder.dueOdometerKm)
            stmt.setObject(4, reminder.dueDateMillis)
            stmt.setObject(5, reminder.repeatEveryKm)
            stmt.setObject(6, reminder.repeatEveryDays)
            stmt.setInt(7, if (reminder.completed) 1 else 0)
            stmt.setObject(8, reminder.completedAtMillis)
            stmt.setLong(9, id)
            require(stmt.executeUpdate() > 0) { "Reminder must exist before it can be edited" }
            conn.commit()
            reminder
        }
    }

    suspend fun deleteReminder(id: Long) = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.setAutoCommit(false)
            val stmt = conn.prepareStatement("DELETE FROM service_reminder WHERE id = ?")
            stmt.setLong(1, id)
            require(stmt.executeUpdate() > 0) { "Reminder must exist before it can be deleted" }
            conn.commit()
        }
    }

    private fun mapReminder(rs: java.sql.ResultSet): ServiceReminder = ServiceReminder(
        id = rs.getLong("id"),
        vin = rs.getString("vin"),
        title = rs.getString("title"),
        description = rs.getString("description"),
        dueOdometerKm = rs.getNullableDouble("due_odometer_km"),
        dueDateMillis = rs.getNullableLong("due_date_millis"),
        repeatEveryKm = rs.getNullableDouble("repeat_every_km"),
        repeatEveryDays = rs.getNullableLong("repeat_every_days"),
        completed = rs.getInt("completed") == 1,
        completedAtMillis = rs.getNullableLong("completed_at_millis"),
        createdAtMillis = rs.getLong("created_at_millis"),
    )
}

private fun java.sql.ResultSet.getNullableLong(column: String): Long? =
    getLong(column).let { if (wasNull()) null else it }

private fun java.sql.ResultSet.getNullableDouble(column: String): Double? =
    getDouble(column).let { if (wasNull()) null else it }

private fun java.sql.ResultSet.getNullableInt(column: String): Int? =
    getInt(column).let { if (wasNull()) null else it }
