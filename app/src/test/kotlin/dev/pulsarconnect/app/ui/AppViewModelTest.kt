package dev.pulsarconnect.app.ui

import dev.pulsarconnect.core.data.InMemoryBikeRepository
import dev.pulsarconnect.core.data.InMemoryReminderRepository
import dev.pulsarconnect.core.data.InMemoryTripRepository
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
            clock = { now }, zone = ZoneOffset.UTC, ioDispatcher = dispatcher, fetchVehicles = fetch,
        )
    }

    private fun signedIn(vm: AppViewModel) = vm.state.value as UiState.SignedIn
    private suspend fun cacheBike() = bikes.upsertBike(BikeProfile(vin, "Cached Pulsar", null, null, null))

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
}
