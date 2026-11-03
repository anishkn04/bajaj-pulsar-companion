package dev.pulsarconnect.app.ble

import dev.pulsarconnect.core.ble.AdapterState
import dev.pulsarconnect.core.ble.BikeConnection
import dev.pulsarconnect.core.ble.BikeLinkController
import dev.pulsarconnect.core.ble.BikeLinkPreferences
import dev.pulsarconnect.core.ble.BikeLinkSettings
import dev.pulsarconnect.core.ble.BikeScanner
import dev.pulsarconnect.core.ble.BikeTarget
import dev.pulsarconnect.core.ble.BleCharacteristic
import dev.pulsarconnect.core.ble.BleException
import dev.pulsarconnect.core.ble.BleFailure
import dev.pulsarconnect.core.ble.BlePermission
import dev.pulsarconnect.core.ble.BleScanSource
import dev.pulsarconnect.core.ble.BleService
import dev.pulsarconnect.core.ble.BleSession
import dev.pulsarconnect.core.ble.BleSessionEvent
import dev.pulsarconnect.core.ble.BleTransport
import dev.pulsarconnect.core.ble.ConnectionState
import dev.pulsarconnect.core.ble.InMemoryBikeDeviceStore
import dev.pulsarconnect.core.ble.InMemoryBikeLinkPreferences
import dev.pulsarconnect.core.ble.PhoneStatus
import dev.pulsarconnect.core.ble.PhoneStatusProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BleViewModelPhoneStatusTest {

    private val dispatcher = StandardTestDispatcher()
    private val vin = "MD2ABCDEFGH123456"
    private val target = BikeTarget("AA:BB:CC:DD:EE:FF", "Pulsar", vin)
    private val devices = InMemoryBikeDeviceStore()
    private val transport = SupportedTransport()
    private val statuses = MutableStateFlow(PhoneStatus(50, 2))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        source: Flow<PhoneStatus> = statuses,
        connection: BikeConnection,
        controller: BikeLinkController,
    ): BleViewModel {
        val scope = CoroutineScope(dispatcher)
        return BleViewModel(
            application = RuntimeEnvironment.getApplication(),
            devices = devices,
            scanner = BikeScanner(BleScanSource { flowOf() }),
            transport = transport,
            controller = controller,
            runtime = FakeRuntime(),
        )
    }

    private fun TestScope.controller(
        preferences: BikeLinkPreferences = InMemoryBikeLinkPreferences(),
        connection: BikeConnection,
    ): BikeLinkController {
        return BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = preferences,
            statuses = statuses,
            scope = this,
        )
    }

    private fun TestScope.connectAndMirror(
        preferences: BikeLinkPreferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(mirroring = true, keepInBackground = false, startOnBoot = false),
        ),
    ): Triple<BikeLinkController, BikeConnection, BleViewModel> {
        devices.select(target)
        val connection = BikeConnection(transport, this, jitter = { it })
        val controller = controller(preferences, connection)
        val viewModel = viewModel(statuses, connection, controller)
        viewModel.onSession(vin)
        viewModel.connect()
        advanceUntilIdle()
        runCurrent()
        assertTrue(viewModel.state.value.connection is ConnectionState.Connected)
        return Triple(controller, connection, viewModel)
    }

    private fun heartbeat(bytes: ByteArray): Int = bytes[53].toInt() and 0xff

    @Test
    fun `mirroring is on by default and sends once per second until disabled`() = runTest(dispatcher) {
        devices.select(target)
        val preferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(mirroring = true, keepInBackground = false, startOnBoot = false),
        )
        val connection = BikeConnection(transport, CoroutineScope(dispatcher), jitter = { it })
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = preferences,
            statuses = statuses,
            scope = CoroutineScope(dispatcher),
        )
        val viewModel = viewModel(statuses, connection, controller)
        viewModel.onSession(vin)
        runCurrent()

        assertTrue(viewModel.state.value.phoneStatusEnabled)
        assertFalse(viewModel.state.value.phoneStatusAvailable)
        assertTrue(transport.sessions.isEmpty())

        viewModel.setMirroring(false)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(viewModel.state.value.phoneStatusEnabled)
        assertFalse(viewModel.state.value.phoneStatusSending)
        assertNull(viewModel.state.value.phoneStatusError)
        assertTrue(transport.sessions.isEmpty())

        viewModel.signOut()
        advanceUntilIdle()
        runCurrent()
    }

    @Test
    fun `sign out stops mirroring and clears stale state`() = runTest(dispatcher) {
        devices.select(target)
        val preferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(mirroring = true, keepInBackground = false, startOnBoot = false),
        )
        val connection = BikeConnection(transport, CoroutineScope(dispatcher), jitter = { it })
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = preferences,
            statuses = statuses,
            scope = CoroutineScope(dispatcher),
        )
        val viewModel = viewModel(statuses, connection, controller)
        viewModel.onSession(vin)
        runCurrent()

        assertTrue(viewModel.state.value.phoneStatusEnabled)
        assertFalse(viewModel.state.value.phoneStatusSending)

        viewModel.signOut()
        advanceUntilIdle()
        runCurrent()
        advanceUntilIdle()
        runCurrent()

        assertNull(viewModel.state.value.vin)
        assertFalse(viewModel.state.value.phoneStatusEnabled)
        assertFalse(viewModel.state.value.phoneStatusSending)
        assertFalse(preferences.settings.value.mirroring)
        assertNull(preferences.settings.value.armedVin)
        assertTrue(transport.sessions.isEmpty())
    }

    @Test
    fun `switching vin stops mirroring before the new bike is used`() = runTest(dispatcher) {
        devices.select(target)
        val preferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(mirroring = true, keepInBackground = false, startOnBoot = false),
        )
        val connection = BikeConnection(transport, CoroutineScope(dispatcher), jitter = { it })
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = preferences,
            statuses = statuses,
            scope = CoroutineScope(dispatcher),
        )
        val viewModel = viewModel(statuses, connection, controller)
        viewModel.onSession(vin)
        runCurrent()

        assertTrue(viewModel.state.value.phoneStatusEnabled)
        assertFalse(viewModel.state.value.phoneStatusSending)

        viewModel.onSession("MD2ABCDEFGH654321")
        advanceUntilIdle()
        runCurrent()
        advanceUntilIdle()
        runCurrent()

        assertFalse(viewModel.state.value.phoneStatusEnabled)
        assertFalse(viewModel.state.value.phoneStatusSending)
        assertFalse(preferences.settings.value.mirroring)
        assertEquals("MD2ABCDEFGH654321", preferences.settings.value.armedVin)
        assertTrue(transport.sessions.isEmpty())
    }

    @Test
    fun `disconnect stops mirroring without an error`() = runTest(dispatcher) {
        devices.select(target)
        val preferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(mirroring = true, keepInBackground = false, startOnBoot = false),
        )
        val connection = BikeConnection(transport, CoroutineScope(dispatcher), jitter = { it })
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = preferences,
            statuses = statuses,
            scope = CoroutineScope(dispatcher),
        )
        val viewModel = viewModel(statuses, connection, controller)
        viewModel.onSession(vin)
        runCurrent()

        assertTrue(viewModel.state.value.phoneStatusEnabled)
        assertFalse(viewModel.state.value.phoneStatusSending)

        assertTrue(transport.sessions.isEmpty())
    }

    @Test
    fun `write failure stops mirroring and shows a generic error`() = runTest(dispatcher) {
        val failure = BleException(BleFailure.GattError("writeCharacteristic", 133))
        transport.writeFailure = failure
        devices.select(target)
        val preferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(mirroring = true, keepInBackground = false, startOnBoot = false),
        )
        val connection = BikeConnection(transport, CoroutineScope(dispatcher), jitter = { it })
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = preferences,
            statuses = statuses,
            scope = CoroutineScope(dispatcher),
        )
        val viewModel = viewModel(statuses, connection, controller)
        viewModel.onSession(vin)
        runCurrent()

        assertFalse(viewModel.state.value.phoneStatusSending)
        assertNull(viewModel.state.value.phoneStatusError)
    }

    @Test
    fun `status source stays cold until the rider opts in`() = runTest(dispatcher) {
        var collections = 0
        val source = PhoneStatusSource {
            flow {
                collections++
                emit(PhoneStatus(50, 2))
            }
        }
        devices.select(target)
        val preferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(mirroring = false, keepInBackground = false, startOnBoot = false),
        )
        val connection = BikeConnection(transport, CoroutineScope(dispatcher), jitter = { it })
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = preferences,
            statuses = source.statuses(),
            scope = CoroutineScope(dispatcher),
        )
        val viewModel = viewModel(source.statuses(), connection, controller)
        viewModel.onSession(vin)
        runCurrent()

        assertEquals(0, collections)

        viewModel.setMirroring(true)
        runCurrent()

        assertEquals(0, collections)
        assertTrue(transport.sessions.isEmpty())
    }

    private class FakeRuntime : BikeLinkRuntime {
        var enabled = false
            private set

        override fun setEnabled(enabled: Boolean) {
            this.enabled = enabled
        }
    }

    class SupportedTransport : BleTransport {
        override val adapterState = MutableStateFlow(AdapterState.ON)
        var missing: Set<BlePermission> = emptySet()
        var writeFailure: Throwable? = null
        val sessions = mutableListOf<SupportedSession>()

        override fun missingPermissions(): Set<BlePermission> = missing

        override suspend fun connect(
            target: dev.pulsarconnect.core.ble.BikeTarget,
        ): dev.pulsarconnect.core.ble.BleSession = error("not used in these tests")

        class SupportedSession : BleSession {
            private val mutableEvents = MutableSharedFlow<BleSessionEvent>()
            override val events: Flow<BleSessionEvent> = mutableEvents
            var closed = false
                private set
            val writes = mutableListOf<ByteArray>()

            suspend fun disconnect() {
                mutableEvents.emit(BleSessionEvent.Disconnected(0))
            }

            override suspend fun services(): List<dev.pulsarconnect.core.ble.BleService> = emptyList()
            override suspend fun setNotifications(service: UUID, characteristic: UUID, enabled: Boolean) = Unit
            override suspend fun read(service: UUID, characteristic: UUID): ByteArray = ByteArray(0)
            override suspend fun requestMtu(mtu: Int): Int = mtu
            override suspend fun write(service: UUID, characteristic: UUID, bytes: ByteArray, withResponse: Boolean) = Unit
            override fun close() {}
        }
    }
}
