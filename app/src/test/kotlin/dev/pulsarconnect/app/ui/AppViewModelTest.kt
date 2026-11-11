package dev.pulsarconnect.app.ui

import dev.pulsarconnect.app.notifications.ReminderNotificationActions
import dev.pulsarconnect.core.data.InMemoryBikeRepository
import dev.pulsarconnect.core.data.InMemoryReminderRepository
import dev.pulsarconnect.core.data.InMemoryTripRepository
import dev.pulsarconnect.core.data.REDACTED_VIN
import dev.pulsarconnect.core.data.RestoreMode
import dev.pulsarconnect.core.data.TripRepository
import dev.pulsarconnect.core.model.BikeProfile
import dev.pulsarconnect.core.model.Credentials
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.model.Session
import dev.pulsarconnect.core.model.StoredSession
import dev.pulsarconnect.core.model.Vin
import dev.pulsarconnect.core.network.BajajApi
import dev.pulsarconnect.core.network.BajajSessionManager
import dev.pulsarconnect.core.network.Bike
import dev.pulsarconnect.core.session.SecretStore
import dev.pulsarconnect.core.session.SessionRepository
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val vin = "MD2ABCDEFGH123456"
    private var now = Instant.parse("2026-11-01T10:00:00Z").toEpochMilli()
    private val bikes = InMemoryBikeRepository()
    private val trips = InMemoryTripRepository { now }
    private val reminders = InMemoryReminderRepository()
    private val secrets = object : SecretStore {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) { values[key] = value }
        override fun delete(key: String) { values.remove(key) }
    }
    private val sessionRepository = SessionRepository(secrets)
    private val notifications = RecordingNotifications()

    private class RecordingNotifications : ReminderNotificationActions {
        override var enabled = false
            private set
        val events = mutableListOf<String>()
        var failScheduling = false
        override fun setEnabled(enabled: Boolean) {
            if (failScheduling) throw IllegalStateException("scheduler unavailable")
            this.enabled = enabled
            events.add("enabled:$enabled")
        }
        override fun refresh() {
            if (failScheduling) throw IllegalStateException("scheduler unavailable")
            events.add("refresh")
        }
        override fun onSignedOut() {
            events.add("signOut")
            if (failScheduling) throw IllegalStateException("scheduler unavailable")
        }
        override fun onReminderCompleted(id: Long) {
            events.add("completed:$id")
            if (failScheduling) throw IllegalStateException("scheduler unavailable")
        }
        override fun onReminderRearmed(id: Long) {
            events.add("rearmed:$id")
            if (failScheduling) throw IllegalStateException("scheduler unavailable")
        }
        override fun onReminderReopened(id: Long) {
            events.add("reopened:$id")
            if (failScheduling) throw IllegalStateException("scheduler unavailable")
        }
        override fun onReminderDeleted(id: Long) {
            events.add("deleted:$id")
            if (failScheduling) throw IllegalStateException("scheduler unavailable")
        }
    }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        sessionRepository.save(StoredSession(
            Credentials(Vin.of(vin), "synthetic-code"),
            Session("synthetic-app-token", "synthetic-user-token", Instant.ofEpochMilli(now)),
        ))
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun viewModel(
        tripRepository: TripRepository = trips,
        fetch: suspend () -> List<Bike> = { throw IOException("offline") },
    ): AppViewModel {
        val api = BajajApi("https://example.invalid")
        return AppViewModel(
            BajajSessionManager(api, sessionRepository), api, bikes, tripRepository, reminders,
            clock = { now }, zone = ZoneOffset.UTC, ioDispatcher = dispatcher,
                        notificationActions = notifications, fetchVehicles = fetch,
        )
    }

    private fun signedIn(vm: AppViewModel) = vm.state.value as UiState.SignedIn
    private suspend fun cacheBike() = bikes.upsertBike(BikeProfile(vin, "Cached Pulsar", null, null, null))

    @Test fun `restoring session resumes opted in notifications and actions refresh recorded odometer`() = runTest(dispatcher) {
        notifications.setEnabled(true)
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(signedIn(vm).reminderNotificationsEnabled)
        assertTrue(notifications.events.contains("refresh"))
        notifications.events.clear()
        vm.startTrip("100")
        advanceUntilIdle()
        assertEquals(listOf("refresh"), notifications.events)
        notifications.events.clear()
        now += 1000
        vm.endTrip("110")
        advanceUntilIdle()
        assertEquals(listOf("refresh"), notifications.events)
    }

    @Test fun `opt in preference changes and resume rechecks are wired without modifying records`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.setReminderNotificationsEnabled(true)
        assertTrue(notifications.enabled)
        assertTrue(signedIn(vm).reminderNotificationsEnabled)
        vm.setReminderNotificationsEnabled(false)
        assertFalse(notifications.enabled)
        assertFalse(signedIn(vm).reminderNotificationsEnabled)
        notifications.events.clear()
        vm.refreshReminderNotifications()
        assertEquals(listOf("refresh"), notifications.events)
        assertTrue(trips.getTrips(vin, 100, 0).isEmpty())
        assertTrue(reminders.getReminders(vin).isEmpty())
    }

    @Test fun `adding and completing reminders schedules checks and cancels delivered alert`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        notifications.events.clear()
        vm.addReminder("Oil", "100", "")
        advanceUntilIdle()
        assertEquals(listOf("refresh"), notifications.events)
        val id = signedIn(vm).reminders.single().id!!
        notifications.events.clear()
        vm.completeReminder(id)
        advanceUntilIdle()
        assertEquals(listOf("completed:$id", "refresh"), notifications.events)
        notifications.events.clear()
        vm.completeReminder(id)
        advanceUntilIdle()
        assertTrue(notifications.events.isEmpty()) // Rejected mutation must not emit notification side effects.
    }

    @Test fun `scheduler failure never turns saved records into failed writes or blocks sign out`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        notifications.failScheduling = true
        vm.addReminder("Oil", "100", "")
        advanceUntilIdle()
        assertEquals(1, signedIn(vm).reminders.size)
        assertFalse(signedIn(vm).localLoadFailed)
        assertNotNull(signedIn(vm).error)
        assertFalse(signedIn(vm).busy)
        vm.signOut()
        assertTrue(notifications.events.contains("signOut"))
        advanceUntilIdle()
        assertTrue(vm.state.value is UiState.SignedOut)
        assertNull(sessionRepository.load())
        notifications.events.clear()
        vm.setReminderNotificationsEnabled(true)
        vm.refreshReminderNotifications()
        assertTrue(notifications.events.isEmpty())
    }

    @Test fun `offline launch restores bike active trip and reminders`() = runTest(dispatcher) {
        cacheBike()
        val active = trips.startTrip(vin, 1200.0, null, null)
        reminders.addReminder(ServiceReminder(vin = vin, title = "Oil", description = null,
            dueOdometerKm = 1500.0, dueDateMillis = null, createdAtMillis = now))
        val vm = viewModel()
        advanceUntilIdle()
        val state = signedIn(vm)
        assertEquals("Cached Pulsar", state.bikeName)
        assertEquals(active.id, state.activeTrip?.id)
        assertEquals(1200.0, state.lastOdometer)
        assertEquals(1, state.reminders.size)
        assertFalse(state.localLoading)
    }

    @Test fun `manual trip survives new view model and computes distance`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.startTrip("1000.0")
        vm.startTrip("1000.0") // Rapid repeated taps must not create a second trip.
        advanceUntilIdle()
        assertNotNull(signedIn(vm).activeTrip)
        assertEquals(1, trips.getTrips(vin, 100, 0).size)
        val restored = viewModel()
        advanceUntilIdle()
        now += 3_600_000
        restored.endTrip("1040")
        advanceUntilIdle()
        assertNull(signedIn(restored).activeTrip)
        assertEquals(40.0, signedIn(restored).trips.single().distanceKm)
        assertEquals(3600L, signedIn(restored).trips.single().durationSeconds)
        assertEquals(1040.0, signedIn(restored).lastOdometer)
    }

    @Test fun `invalid odometers leave stored trips unchanged`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        for (input in listOf("", "NaN", "Infinity", "-1", "oops")) {
            vm.startTrip(input)
            advanceUntilIdle()
            assertNotNull(signedIn(vm).error)
            assertTrue(trips.getTrips(vin, 100, 0).isEmpty())
        }
        vm.startTrip("100")
        advanceUntilIdle()
        vm.endTrip("99")
        advanceUntilIdle()
        assertNotNull(signedIn(vm).activeTrip)
        assertNotNull(signedIn(vm).error)
    }

    @Test fun `trip pages append without duplicates and stay scoped to signed in bike`() = runTest(dispatcher) {
        cacheBike()
        repeat(25) {
            val trip = trips.startTrip(vin, it.toDouble(), null, null)
            now += 1000
            trips.endTrip(trip, it + 1.0, null, null)
        }
        trips.startTrip("MD2ABCDEFGH654321", 999.0, null, null)
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(20, signedIn(vm).trips.size)
        assertTrue(signedIn(vm).hasMoreTrips)
        vm.loadMoreTrips()
        advanceUntilIdle()
        assertEquals(25, signedIn(vm).trips.size)
        assertEquals(25, signedIn(vm).trips.map { it.id }.toSet().size)
        assertFalse(signedIn(vm).hasMoreTrips)
        assertTrue(signedIn(vm).trips.all { it.vin == vin })
    }

    @Test fun `reminders require title and threshold validate dates and can be completed`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        for ((title, km, date) in listOf(Triple("", "100", ""), Triple("Oil", "", ""),
            Triple("Oil", "NaN", ""), Triple("Oil", "", "2026-02-30"))) {
            vm.addReminder(title, km, date)
            advanceUntilIdle()
            assertNotNull(signedIn(vm).error)
            assertTrue(reminders.getReminders(vin).isEmpty())
        }
        vm.addReminder(" Oil change ", "1500", "2026-11-05")
        advanceUntilIdle()
        val reminder = signedIn(vm).reminders.single()
        assertEquals("Oil change", reminder.title)
        assertEquals(Instant.parse("2026-11-05T00:00:00Z").toEpochMilli(), reminder.dueDateMillis)
        vm.completeReminder(reminder.id!!)
        advanceUntilIdle()
        assertTrue(signedIn(vm).reminders.single().completed)
    }

    @Test fun `editing a reminder updates rider fields and preserves completion`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.addReminder("Oil", "1500", "2026-11-05")
        advanceUntilIdle()
        val saved = signedIn(vm).reminders.single()
        vm.completeReminder(saved.id!!)
        advanceUntilIdle()
        notifications.events.clear()

        vm.updateReminder(saved.id!!, " Chain ", " Clean and lube ", "2000", "2026-12-01")
        advanceUntilIdle()

        val updated = signedIn(vm).reminders.single()
        assertEquals("Chain", updated.title)
        assertEquals("Clean and lube", updated.description)
        assertEquals(2_000.0, updated.dueOdometerKm!!, 0.0)
        assertEquals(Instant.parse("2026-12-01T00:00:00Z").toEpochMilli(), updated.dueDateMillis)
        assertTrue(updated.completed)
        assertTrue(notifications.events.contains("rearmed:${saved.id}"))
        assertTrue(notifications.events.contains("refresh"))

        vm.updateReminder(saved.id!!, " ", "", "", "")
        advanceUntilIdle()
        assertNotNull(signedIn(vm).error)
        assertEquals(updated, signedIn(vm).reminders.single())
    }

    @Test fun `snoozing shifts due thresholds without completing the reminder`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.addReminder("Oil", "1500", "2026-11-05")
        advanceUntilIdle()
        val saved = signedIn(vm).reminders.single()
        notifications.events.clear()

        vm.snoozeReminder(saved.id!!, days = 1, kilometres = 500.0)
        advanceUntilIdle()

        val snoozed = signedIn(vm).reminders.single()
        assertEquals(2_000.0, snoozed.dueOdometerKm!!, 0.0)
        assertEquals(Instant.parse("2026-11-06T00:00:00Z").toEpochMilli(), snoozed.dueDateMillis)
        assertFalse(snoozed.completed)
        assertTrue(notifications.events.contains("rearmed:${saved.id}"))
        assertEquals(snoozed, reminders.getReminders(vin).single())
    }

    @Test fun `deleting a reminder removes only that reminder and clears its alert`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.addReminder("Oil", "1500", "")
        advanceUntilIdle()
        vm.addReminder("Chain", "2500", "")
        advanceUntilIdle()
        val oil = signedIn(vm).reminders.first { it.title == "Oil" }
        notifications.events.clear()

        vm.deleteReminder(oil.id!!)
        advanceUntilIdle()

        assertEquals(listOf("Chain"), signedIn(vm).reminders.map { it.title })
        assertTrue(notifications.events.contains("deleted:${oil.id}"))
        assertTrue(notifications.events.contains("refresh"))
        vm.deleteReminder(oil.id!!)
        advanceUntilIdle()
        assertNotNull(signedIn(vm).error)
    }

    @Test fun `undoing completion makes a due reminder alertable again`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.addReminder("Oil", "", "2026-10-01")
        advanceUntilIdle()
        val saved = signedIn(vm).reminders.single()
        assertEquals(listOf(saved), reminders.getDueReminders(vin, 0.0, now))

        vm.completeReminder(saved.id!!)
        advanceUntilIdle()
        assertTrue(reminders.getDueReminders(vin, 0.0, now).isEmpty())
        notifications.events.clear()

        vm.reopenReminder(saved.id!!)
        advanceUntilIdle()

        val reopened = signedIn(vm).reminders.single()
        assertFalse(reopened.completed)
        assertNull(reopened.completedAtMillis)
        assertEquals(listOf(reopened), reminders.getDueReminders(vin, 0.0, now))
        assertTrue(notifications.events.contains("reopened:${saved.id}"))
        assertTrue(notifications.events.contains("refresh"))
    }

    @Test fun `refresh never displays another bikes profile and caches exact VIN match`() = runTest(dispatcher) {
        cacheBike()
        val wrong = viewModel { listOf(Bike(chassisNumber = "MD2ABCDEFGH654321", model = "Wrong bike")) }
        advanceUntilIdle()
        assertEquals("Cached Pulsar", signedIn(wrong).bikeName)
        val right = viewModel { listOf(Bike(chassisNumber = vin.lowercase(), model = "Updated Pulsar")) }
        advanceUntilIdle()
        assertEquals("Updated Pulsar", signedIn(right).bikeName)
        assertEquals("Updated Pulsar", bikes.getBike(vin)?.model)
    }

    @Test fun `sign out keeps local records and late refresh cannot restore signed in UI`() = runTest(dispatcher) {
        cacheBike()
        trips.startTrip(vin, 100.0, null, null)
        val response = CompletableDeferred<List<Bike>>()
        val vm = viewModel { response.await() }
        advanceUntilIdle()
        vm.signOut()
        response.complete(listOf(Bike(chassisNumber = vin, model = "Late response")))
        advanceUntilIdle()
        assertTrue(vm.state.value is UiState.SignedOut)
        assertNull(sessionRepository.load())
        assertEquals(1, trips.getTrips(vin, 100, 0).size)
    }

    @Test fun `saved mutation with failed reload cannot be repeated before read only retry`() = runTest(dispatcher) {
        var failReads = false
        val unreliable = object : TripRepository by trips {
            override suspend fun getTrips(vin: String, limit: Int, offset: Int) =
                if (failReads) throw IOException("temporary read failure") else trips.getTrips(vin, limit, offset)
        }
        val vm = viewModel(tripRepository = unreliable)
        advanceUntilIdle()
        failReads = true
        vm.addReminder("Oil", "1500", "")
        advanceUntilIdle()
        assertEquals(1, reminders.getReminders(vin).size)
        assertTrue(signedIn(vm).localLoadFailed)
        assertTrue(signedIn(vm).error!!.contains("saved", ignoreCase = true))
        vm.addReminder("Oil", "1500", "")
        advanceUntilIdle()
        assertEquals(1, reminders.getReminders(vin).size)
        failReads = false
        vm.retryLocalRecords()
        advanceUntilIdle()
        assertFalse(signedIn(vm).localLoadFailed)
        assertNull(signedIn(vm).error)
        assertEquals(1, signedIn(vm).reminders.size)
    }

    @Test fun `clock rollback cannot hide active trip behind paginated history`() = runTest(dispatcher) {
        repeat(25) {
            val trip = trips.startTrip(vin, 100.0, null, null)
            now += 1000
            trips.endTrip(trip, 101.0, null, null)
        }
        now -= 100_000
        val vm = viewModel()
        advanceUntilIdle()
        vm.startTrip("200")
        advanceUntilIdle()
        assertNotNull(signedIn(vm).activeTrip)
        assertEquals(200.0, signedIn(vm).lastOdometer)
        vm.endTrip("210")
        advanceUntilIdle()
        assertNull(signedIn(vm).activeTrip)
        assertEquals(26, trips.getTrips(vin, 100, 0).size)
    }

    @Test fun `local storage failure leaves usable signed in screen with error`() = runTest(dispatcher) {
        val failing = object : TripRepository by trips {
            override suspend fun getTrips(vin: String, limit: Int, offset: Int) = throw IOException("disk failed")
        }
        val vm = viewModel(tripRepository = failing)
        advanceUntilIdle()
        assertFalse(signedIn(vm).localLoading)
        assertNotNull(signedIn(vm).error)
        assertFalse(signedIn(vm).busy)
    }

    @Test fun `editing a saved trip corrects its odometer and rejects backwards readings`() = runTest(dispatcher) {
        val trip = trips.startTrip(vin, 100.0, null, null)
        now += 3_600_000
        trips.endTrip(trip, 150.0, null, null)
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(150.0, signedIn(vm).trips.single().endOdometer!!, 0.0)

        vm.updateTripOdometer(trip.id!!, "90", "140")
        advanceUntilIdle()
        val updated = signedIn(vm).trips.single()
        assertEquals(90.0, updated.startOdometer, 0.0)
        assertEquals(140.0, updated.endOdometer!!, 0.0)
        assertEquals(50.0, updated.distanceKm!!, 0.0)
        assertEquals(50.0, updated.avgSpeedKmph!!, 0.0)

        vm.updateTripOdometer(trip.id!!, "140", "90")
        advanceUntilIdle()
        assertNotNull(signedIn(vm).error)
        assertEquals(140.0, trips.getTrip(trip.id!!)!!.endOdometer!!, 0.0)
    }

    @Test fun `deleting a saved trip offers undo and restores the original record`() = runTest(dispatcher) {
        val trip = trips.startTrip(vin, 100.0, null, null)
        now += 3_600_000
        val ended = trips.endTrip(trip, 150.0, null, null)
        val vm = viewModel()
        advanceUntilIdle()

        vm.deleteTrip(trip.id!!)
        advanceUntilIdle()
        assertNull(trips.getTrip(trip.id!!))
        assertEquals(ended, signedIn(vm).lastDeletedTrip)
        assertTrue(signedIn(vm).trips.isEmpty())

        vm.undoDeleteTrip()
        advanceUntilIdle()
        assertNull(signedIn(vm).lastDeletedTrip)
        assertEquals(ended, trips.getTrip(trip.id!!))
        assertEquals(ended, signedIn(vm).trips.single())
    }

    @Test fun `dismissing undo keeps the trip deleted`() = runTest(dispatcher) {
        val trip = trips.startTrip(vin, 100.0, null, null)
        now += 3_600_000
        trips.endTrip(trip, 150.0, null, null)
        val vm = viewModel()
        advanceUntilIdle()

        vm.deleteTrip(trip.id!!)
        advanceUntilIdle()
        assertNotNull(signedIn(vm).lastDeletedTrip)

        vm.dismissDeletedTrip()
        assertNull(signedIn(vm).lastDeletedTrip)
        assertNull(trips.getTrip(trip.id!!))
    }

    @Test fun `discarding an active trip frees the active slot and undo brings it back`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.startTrip("100")
        advanceUntilIdle()
        val active = requireNotNull(signedIn(vm).activeTrip)

        vm.deleteTrip(active.id!!)
        advanceUntilIdle()
        assertNull(signedIn(vm).activeTrip)
        assertNull(trips.getActiveTrip(vin))
        assertEquals(active, signedIn(vm).lastDeletedTrip)

        vm.undoDeleteTrip()
        advanceUntilIdle()
        assertEquals(active, signedIn(vm).activeTrip)
        assertEquals(active, trips.getActiveTrip(vin))
    }

    @Test fun `redacted export hides the VIN and restore replaces local records`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.startTrip("100")
        advanceUntilIdle()
        now += 3_600_000
        vm.endTrip("150")
        advanceUntilIdle()
        vm.addReminder("Oil", "1500", "")
        advanceUntilIdle()
        val tripId = signedIn(vm).trips.single().id!!
        val reminderId = signedIn(vm).reminders.single().id!!

        var exported = ""
        vm.exportBackup(redactVin = true) { exported = it }
        advanceUntilIdle()
        assertTrue(exported.contains(REDACTED_VIN))
        assertFalse(exported.contains(vin))
        assertNotNull(signedIn(vm).notice)
        vm.clearNotice()
        assertNull(signedIn(vm).notice)

        trips.deleteTrip(tripId)
        reminders.deleteReminder(reminderId)
        notifications.events.clear()

        vm.restoreBackup(exported, RestoreMode.REPLACE)
        advanceUntilIdle()
        assertEquals(tripId, signedIn(vm).trips.single().id)
        assertEquals(reminderId, signedIn(vm).reminders.single().id)
        assertTrue(notifications.events.contains("rearmed:$reminderId"))
        assertNotNull(signedIn(vm).notice)
        assertFalse(signedIn(vm).busy)
    }

    @Test fun `malformed restore reports an error and leaves records unchanged`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.addReminder("Oil", "1500", "")
        advanceUntilIdle()
        val before = signedIn(vm).reminders

        vm.restoreBackup("{ not a backup", RestoreMode.REPLACE)
        advanceUntilIdle()

        assertNotNull(signedIn(vm).error)
        assertEquals(before, signedIn(vm).reminders)
        assertEquals(before, reminders.getReminders(vin))
        assertFalse(signedIn(vm).busy)
    }

    @Test fun `a failed export write reports a clear error and saves nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.exportBackup(redactVin = false) { throw IOException("disk full") }
        advanceUntilIdle()

        assertTrue(signedIn(vm).error!!.contains("Could not write the export file"))
        assertNull(signedIn(vm).notice)
        assertFalse(signedIn(vm).busy)
    }
}
