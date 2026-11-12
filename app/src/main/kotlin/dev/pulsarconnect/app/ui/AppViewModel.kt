package dev.pulsarconnect.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.pulsarconnect.app.AppContainer
import dev.pulsarconnect.app.notifications.NoOpReminderNotifications
import dev.pulsarconnect.app.notifications.ReminderNotificationActions
import dev.pulsarconnect.core.data.BikeRepository
import dev.pulsarconnect.core.data.LocalBackupCodec
import dev.pulsarconnect.core.data.LocalBackupService
import dev.pulsarconnect.core.data.ReminderRepository
import dev.pulsarconnect.core.data.RestoreMode
import dev.pulsarconnect.core.data.TripRepository
import dev.pulsarconnect.core.data.requireValidReminder
import dev.pulsarconnect.core.data.snoozed
import dev.pulsarconnect.core.model.BikeProfile
import dev.pulsarconnect.core.model.Credentials
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.model.Trip
import dev.pulsarconnect.core.model.Vin
import dev.pulsarconnect.core.network.BajajApi
import dev.pulsarconnect.core.network.BajajSessionManager
import dev.pulsarconnect.core.network.Bike
import dev.pulsarconnect.core.network.SignInResult
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(
    private val sessionManager: BajajSessionManager,
    private val api: BajajApi,
    private val bikes: BikeRepository,
    private val trips: TripRepository,
    private val reminders: ReminderRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val notificationActions: ReminderNotificationActions = NoOpReminderNotifications,
    private val backupService: LocalBackupService = LocalBackupService(trips, reminders, bikes),
    private val reminderChanges: Flow<Unit> = emptyFlow(),
    private val fetchVehicles: suspend () -> List<Bike> = {
        sessionManager.withSession { stored ->
            api.vehicles(requireNotNull(stored.session.userToken) { "Missing vehicle token" })
        }
    },
    private val onSignedOut: () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state = _state.asStateFlow()
    private var generation = 0
    private var signedInJob: Job? = null
    private var signedInScope: CoroutineScope? = null

    init {
        viewModelScope.launch {
            try {
                val stored = withContext(ioDispatcher) { sessionManager.restore() }
                if (stored == null) _state.value = UiState.SignedOut()
                else enterSession(stored.credentials.vin.value)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.value = UiState.SignedOut(error = "Could not read the saved session. Try signing in again.")
            }
        }
    }

    init {
        // A reminder answered from the notification shade bypasses this view model; reload so
        // the list and due states reflect the action as soon as the app is visible again.
        viewModelScope.launch {
            reminderChanges.collect {
                val current = _state.value as? UiState.SignedIn ?: return@collect
                if (current.busy || current.localLoading || current.localLoadFailed) return@collect
                try {
                    reloadLocal(
                        current.vin, generation,
                        maxOf(PAGE_SIZE, current.trips.size),
                    )
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // Best effort; the next explicit load shows the change.
                }
            }
        }
    }

    fun signIn(vinInput: String, accessCode: String) {
        val current = _state.value as? UiState.SignedOut ?: return
        if (current.busy) return
        val credentials = try {
            Credentials(Vin.of(vinInput), accessCode.trim())
        } catch (error: IllegalArgumentException) {
            _state.value = UiState.SignedOut(error = error.message ?: "Check your VIN and access code")
            return
        }
        _state.value = UiState.SignedOut(busy = true)
        viewModelScope.launch {
            try {
                when (val result = sessionManager.signIn(credentials)) {
                    is SignInResult.Success -> enterSession(credentials.vin.value)
                    SignInResult.VehicleNotRegistered -> _state.value = UiState.SignedOut(
                        error = "This VIN is not registered. Create the account in the official " +
                            "Bajaj Ride Connect app first, then log in here.",
                    )
                    is SignInResult.Rejected -> _state.value = UiState.SignedOut(error = result.message)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.value = UiState.SignedOut(error = if (error is IOException) {
                    "Could not reach Bajaj. Check your connection and try again."
                } else "Could not sign in. Check your details and try again.")
            }
        }
    }

    private fun enterSession(vin: String) {
        signedInJob?.cancel()
        val version = ++generation
        val job = SupervisorJob(viewModelScope.coroutineContext[Job])
        signedInJob = job
        val scope = CoroutineScope(viewModelScope.coroutineContext + job)
        signedInScope = scope
        // A restored session opens the local UI immediately; network refresh never gates it.
        _state.value = UiState.SignedIn(
            vin = vin, bikeName = null,
            reminderNotificationsEnabled = notificationActions.enabled,
        )
        refreshReminderNotifications()
        scope.launch {
            try {
                reloadLocal(vin, version, PAGE_SIZE)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                updateSignedIn(vin, version) {
                    it.copy(localLoading = false, localLoadFailed = true, error = "Could not load local records. Your saved records have not been deleted.")
                }
            }
            refreshBike(vin, version)
        }
    }

    private suspend fun ensureBike(vin: String): BikeProfile = bikes.getBike(vin)
        ?: bikes.upsertBike(BikeProfile(vin, null, null, null, null))

    private suspend fun reloadLocal(vin: String, version: Int, count: Int) {
        val bike = ensureBike(vin)
        val active = trips.getActiveTrip(vin)
        val page = trips.getTrips(vin, count + 1, 0)
        val savedReminders = reminders.getReminders(vin)
        val month = monthRange(clock(), zone)
        val totalDistance = trips.totalDistanceKm(vin)
        val tripsThisMonth = trips.completedTripsBetween(vin, month.first, month.second)
        val visible = page.take(count)
        updateSignedIn(vin, version) {
            it.copy(
                bikeName = bike.displayName,
                trips = visible,
                activeTrip = active,
                reminders = savedReminders,
                localLoading = false,
                localLoadFailed = false,
                hasMoreTrips = page.size > count,
                lastOdometer = active?.startOdometer ?: visible.firstOrNull()?.let { trip -> trip.endOdometer ?: trip.startOdometer },
                totalDistanceKm = totalDistance,
                tripsThisMonth = tripsThisMonth,
            )
        }
    }

    private suspend fun refreshBike(vin: String, version: Int) {
        try {
            val match = fetchVehicles().firstOrNull { it.chassisNumber.equals(vin, ignoreCase = true) } ?: return
            if (generation != version) return
            val cached = ensureBike(vin)
            val updated = cached.copy(
                model = match.model?.takeIf(String::isNotBlank) ?: cached.model,
                nickname = match.nickname?.takeIf(String::isNotBlank) ?: cached.nickname,
                modelImageUrl = match.modelImage ?: cached.modelImageUrl,
                lastSyncedAtMillis = clock(),
            )
            bikes.upsertBike(updated)
            updateSignedIn(vin, version) { it.copy(bikeName = updated.displayName) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Network/profile refresh is best effort. Local records and the cached name stay usable.
        }
    }

    fun startTrip(odometerInput: String) = localAction { current ->
        require(current.activeTrip == null) { "A trip is already in progress." }
        ensureBike(current.vin)
        trips.startTrip(current.vin, parseOdometer(odometerInput), null, null)
    }

    fun endTrip(odometerInput: String) = localAction { current ->
        val active = requireNotNull(current.activeTrip) { "No trip is in progress." }
        trips.endTrip(active, parseOdometer(odometerInput), null, null)
    }

    fun updateTripOdometer(tripId: Long, startInput: String, endInput: String) = localAction { current ->
        require(current.trips.any { it.id == tripId }) { "That trip is no longer available." }
        val start = parseOdometer(startInput)
        val end = parseOdometer(endInput)
        require(end >= start) { "End odometer must not be lower than the start odometer." }
        trips.updateTripOdometer(tripId, start, end)
    }

    fun deleteTrip(tripId: Long) = localAction { current ->
        val trip = (current.trips + listOfNotNull(current.activeTrip)).firstOrNull { it.id == tripId }
            ?: throw IllegalArgumentException("That trip is no longer available.")
        trips.deleteTrip(tripId)
        // Kept only for Undo; the snackbar clears it after dismissal or restore.
        updateSignedIn(current.vin, generation) { it.copy(lastDeletedTrip = trip) }
    }

    /** Restores the most recently deleted trip after the rider taps Undo. */
    fun undoDeleteTrip() = localAction { current ->
        val trip = requireNotNull(current.lastDeletedTrip) { "There is no deleted trip to restore." }
        trips.restoreTrip(trip)
        updateSignedIn(current.vin, generation) { it.copy(lastDeletedTrip = null) }
    }

    /** Drops the Undo snapshot once the snackbar is dismissed. The deletion stays committed. */
    fun dismissDeletedTrip() {
        val current = _state.value as? UiState.SignedIn ?: return
        if (current.lastDeletedTrip != null) _state.value = current.copy(lastDeletedTrip = null)
    }

    fun addReminder(
        title: String,
        odometerInput: String,
        dateInput: String,
        repeatKmInput: String = "",
        repeatDaysInput: String = "",
    ) = localAction { current ->
        val cleanTitle = parseReminderTitle(title)
        val odometer = parseOptionalOdometer(odometerInput)
        val date = parseOptionalDate(dateInput)
        val reminder = ServiceReminder(
            vin = current.vin, title = cleanTitle, description = null,
            dueOdometerKm = odometer, dueDateMillis = date,
            repeatEveryKm = parseOptionalRepeatDistance(repeatKmInput),
            repeatEveryDays = parseOptionalRepeatDays(repeatDaysInput),
            createdAtMillis = clock(),
        ).also(::requireValidReminder)
        ensureBike(current.vin)
        reminders.addReminder(reminder)
    }

    fun updateReminder(
        id: Long,
        titleInput: String,
        descriptionInput: String,
        odometerInput: String,
        dateInput: String,
        repeatKmInput: String = "",
        repeatDaysInput: String = "",
    ) = localAction { current ->
        val existing = requireNotNull(current.reminders.firstOrNull { it.id == id }) {
            "That reminder is no longer available."
        }
        val updated = existing.copy(
            title = parseReminderTitle(titleInput),
            description = descriptionInput.trim().takeIf(String::isNotEmpty),
            dueOdometerKm = parseOptionalOdometer(odometerInput),
            dueDateMillis = parseOptionalDate(dateInput),
            repeatEveryKm = parseOptionalRepeatDistance(repeatKmInput),
            repeatEveryDays = parseOptionalRepeatDays(repeatDaysInput),
        ).also(::requireValidReminder)
        reminders.updateReminder(updated)
        if (existing.dueOdometerKm != updated.dueOdometerKm ||
            existing.dueDateMillis != updated.dueDateMillis
        ) {
            notificationAction("Reminder saved, but background alerts could not be updated. Reopen reminder settings to try again.") {
                notificationActions.onReminderRearmed(id)
            }
        }
    }

    fun deleteReminder(id: Long) = localAction { current ->
        require(current.reminders.any { it.id == id }) { "That reminder is no longer available." }
        reminders.deleteReminder(id)
        notificationAction("Reminder deleted, but its alert could not be cleared. Check Android notification settings.") {
            notificationActions.onReminderDeleted(id)
        }
    }

    /** Moves a pending reminder's date or odometer threshold forward without completing it. */
    fun snoozeReminder(id: Long, days: Long, kilometres: Double) = localAction { current ->
        val reminder = requireNotNull(current.reminders.firstOrNull { it.id == id && !it.completed }) {
            "This reminder is not pending for your bike."
        }
        reminders.updateReminder(reminder.snoozed(days, kilometres, zone))
        notificationAction("Reminder snoozed, but background alerts could not be updated. Reopen reminder settings to try again.") {
            notificationActions.onReminderRearmed(id)
        }
    }

    fun reopenReminder(id: Long) = localAction { current ->
        require(current.reminders.any { it.id == id && it.completed }) {
            "This reminder is not completed."
        }
        reminders.reopenReminder(id)
        notificationAction("Reminder reopened, but its alert could not be re-armed. Reopen reminder settings to try again.") {
            notificationActions.onReminderReopened(id)
        }
    }

    fun completeReminder(id: Long) = localAction { current ->
        require(current.reminders.any { it.id == id && !it.completed }) { "This reminder is not pending for your bike." }
        reminders.completeReminder(id, clock())
        notificationAction("Reminder completed, but its alert could not be cleared. Check Android notification settings.") {
            notificationActions.onReminderCompleted(id)
        }
    }

    /**
     * Writes a versioned JSON export through [write], which the settings screen backs with the
     * Storage Access Framework. No credentials, tokens, or Bluetooth addresses are included.
     */
    fun exportBackup(redactVin: Boolean, write: suspend (String) -> Unit) = localAction { current ->
        val backup = backupService.export(current.vin, redactVin, clock())
        try {
            write(LocalBackupCodec.encode(backup))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "Could not write the export file. Choose another location and try again.",
                error,
            )
        }
        val message = "Exported ${backup.trips.size} trips, ${backup.telemetry.size} ride samples, " +
            "and ${backup.reminders.size} reminders" +
            if (redactVin) " with the VIN redacted." else "."
        updateSignedIn(current.vin, generation) { it.copy(notice = message) }
    }

    /** Validates and restores a rider-picked backup. Merge and replace are always explicit. */
    fun restoreBackup(encoded: String, mode: RestoreMode) = localAction { current ->
        val backup = LocalBackupCodec.decode(encoded)
        val summary = backupService.restore(backup, current.vin, mode)
        for (id in summary.restoredReminderIds) {
            notificationAction("Records restored, but a reminder alert could not be re-armed. Reopen reminder settings to try again.") {
                notificationActions.onReminderRearmed(id)
            }
        }
        val prefix = if (mode == RestoreMode.REPLACE) "Replaced local records with" else "Merged"
        val message = if (summary.trips + summary.telemetryPoints + summary.reminders == 0) {
            "No new records restored. Existing records were kept."
        } else {
            "$prefix ${summary.trips} trips, ${summary.telemetryPoints} ride samples, " +
                "and ${summary.reminders} reminders."
        }
        updateSignedIn(current.vin, generation) { it.copy(notice = message) }
    }

    /** Surfaces a settings-screen file error through the normal snackbar path. */
    fun reportError(message: String) {
        val current = _state.value as? UiState.SignedIn ?: return
        _state.value = current.copy(error = message)
    }

    fun clearNotice() {
        val current = _state.value as? UiState.SignedIn ?: return
        if (current.notice != null) _state.value = current.copy(notice = null)
    }

    private fun localAction(action: suspend (UiState.SignedIn) -> Unit) {
        val current = _state.value as? UiState.SignedIn ?: return
        if (current.busy || current.localLoading || current.localLoadFailed) return
        val scope = signedInScope ?: return
        val version = generation
        _state.value = current.copy(busy = true, error = null)
        scope.launch {
            var saved = false
            try {
                action(current)
                saved = true
                notificationAction("Records saved, but background alerts could not be updated. Reopen reminder settings to try again.") {
                    notificationActions.refresh()
                }
                reloadLocal(current.vin, version, maxOf(PAGE_SIZE, current.trips.size))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                updateSignedIn(current.vin, version) {
                    it.copy(
                        localLoadFailed = saved,
                        error = when {
                            saved -> "Saved successfully, but could not reload records. Retry loading before making more changes."
                            error is IllegalArgumentException -> error.message
                            else -> "Could not update local records. Please try again."
                        },
                    )
                }
            } finally {
                updateSignedIn(current.vin, version) { it.copy(busy = false) }
            }
        }
    }

    fun loadMoreTrips() {
        val current = _state.value as? UiState.SignedIn ?: return
        if (current.busy || current.localLoading || !current.hasMoreTrips) return
        val version = generation
        val scope = signedInScope ?: return
        _state.value = current.copy(busy = true, error = null)
        scope.launch {
            try {
                val page = trips.getTrips(current.vin, PAGE_SIZE + 1, current.trips.size)
                updateSignedIn(current.vin, version) {
                    it.copy(trips = (it.trips + page.take(PAGE_SIZE)).distinctBy(Trip::id), hasMoreTrips = page.size > PAGE_SIZE)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                updateSignedIn(current.vin, version) { it.copy(error = "Could not load more trips. Please try again.") }
            } finally {
                updateSignedIn(current.vin, version) { it.copy(busy = false) }
            }
        }
    }

    fun retryLocalRecords() {
        val current = _state.value as? UiState.SignedIn ?: return
        if (current.busy || current.localLoading) return
        val version = generation
        val scope = signedInScope ?: return
        _state.value = current.copy(localLoading = true, error = null)
        scope.launch {
            try {
                reloadLocal(current.vin, version, maxOf(PAGE_SIZE, current.trips.size))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                updateSignedIn(current.vin, version) {
                    it.copy(localLoadFailed = true, error = "Could not load local records. Please retry loading.")
                }
            } finally {
                updateSignedIn(current.vin, version) { it.copy(localLoading = false) }
            }
        }
    }

    fun setReminderNotificationsEnabled(enabled: Boolean) {
        if (_state.value !is UiState.SignedIn) return
        notificationAction("Could not change background alerts. Please try again.") {
            notificationActions.setEnabled(enabled)
        }
        val current = _state.value as? UiState.SignedIn ?: return
        _state.value = current.copy(reminderNotificationsEnabled = notificationActions.enabled)
    }

    fun refreshReminderNotifications() {
        if (_state.value !is UiState.SignedIn) return
        notificationAction("Background alerts could not be updated. Reopen reminder settings to try again.") {
            notificationActions.refresh()
        }
        val current = _state.value as? UiState.SignedIn ?: return
        _state.value = current.copy(reminderNotificationsEnabled = notificationActions.enabled)
    }

    private fun notificationAction(message: String, action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            val current = _state.value as? UiState.SignedIn ?: return
            _state.value = current.copy(error = message)
        }
    }

    fun clearError() {
        val current = _state.value as? UiState.SignedIn ?: return
        _state.value = current.copy(error = null)
    }

    fun signOut() {
        if (_state.value !is UiState.SignedIn) return
        try {
            onSignedOut()
        } catch (_: Exception) {
            // Stopping the BLE connection is best effort; signing out must still proceed.
        }
        val alertCleanupFailed = try {
            notificationActions.onSignedOut()
            false
        } catch (_: Exception) {
            true
        }
        val version = ++generation
        signedInJob?.cancel()
        signedInScope = null
        _state.value = UiState.SignedOut(busy = true)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { sessionManager.signOut() }
                if (generation == version) _state.value = UiState.SignedOut(
                    error = if (alertCleanupFailed) "Signed out, but alert cleanup failed. Disable notifications in Android settings." else null,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == version) _state.value = UiState.SignedOut(error = "Could not clear saved credentials. Please restart and try signing out again.")
            }
        }
    }

    private fun updateSignedIn(vin: String, version: Int, transform: (UiState.SignedIn) -> UiState.SignedIn) {
        _state.update { current ->
            if (generation == version && current is UiState.SignedIn && current.vin == vin) transform(current)
            else current
        }
    }

    private fun parseOdometer(input: String): Double {
        val value = input.trim().toDoubleOrNull()
        require(value != null && value.isFinite() && value >= 0) { "Enter a finite, nonnegative odometer reading in km." }
        return value
    }

    private fun parseReminderTitle(input: String): String =
        input.trim().also { require(it.isNotEmpty()) { "Enter a reminder title." } }

    private fun parseOptionalOdometer(input: String): Double? =
        input.takeIf(String::isNotBlank)?.let(::parseOdometer)

    private fun parseOptionalDate(input: String): Long? =
        input.trim().takeIf(String::isNotEmpty)?.let {
            try {
                LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli()
            } catch (_: java.time.DateTimeException) {
                throw IllegalArgumentException("Enter a valid date as YYYY-MM-DD.")
            }
        }

    private fun parseOptionalRepeatDistance(input: String): Double? =
        input.trim().takeIf(String::isNotEmpty)?.let {
            val value = it.toDoubleOrNull()
            require(value != null && value.isFinite() && value > 0) {
                "Enter a repeat distance greater than 0 km."
            }
            value
        }

    private fun parseOptionalRepeatDays(input: String): Long? =
        input.trim().takeIf(String::isNotEmpty)?.let {
            val value = it.toLongOrNull()
            require(value != null && value > 0) { "Enter a repeat interval of at least one day." }
            value
        }

    companion object {
        private const val PAGE_SIZE = 20
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(
                container.sessionManager, container.api,
                container.database.bikes, container.database.trips, container.database.reminders,
                notificationActions = container.reminderNotifications,
                reminderChanges = container.reminderActions.changes,
                onSignedOut = container.bikeConnection::stop,
            ) as T
        }
    }
}

sealed interface UiState {
    data object Loading : UiState
    data class SignedOut(val error: String? = null, val busy: Boolean = false) : UiState
    data class SignedIn(
        val vin: String,
        val bikeName: String?,
        val trips: List<Trip> = emptyList(),
        val activeTrip: Trip? = null,
        val reminders: List<ServiceReminder> = emptyList(),
        val reminderNotificationsEnabled: Boolean = false,
        val localLoading: Boolean = true,
        val localLoadFailed: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val hasMoreTrips: Boolean = false,
        val lastOdometer: Double? = null,
        /** Local aggregate for the home card; never derived from the paginated [trips] page. */
        val totalDistanceKm: Double = 0.0,
        /** Completed trips that started in the current calendar month. */
        val tripsThisMonth: Int = 0,
        /** Snapshot kept for the Undo snackbar after a delete. Never persisted. */
        val lastDeletedTrip: Trip? = null,
        /** One-shot success message for backups; shown as a snackbar. */
        val notice: String? = null,
    ) : UiState
}
