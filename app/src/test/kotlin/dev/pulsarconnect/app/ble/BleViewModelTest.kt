package dev.pulsarconnect.app.ble

import android.app.Application
import dev.pulsarconnect.core.ble.AdapterState
import dev.pulsarconnect.core.ble.BikeConnection
import dev.pulsarconnect.core.ble.BikeLinkController
import dev.pulsarconnect.core.ble.BikeLinkPreferences
import dev.pulsarconnect.core.ble.BikeLinkSettings
import dev.pulsarconnect.core.ble.BikeScanner
import dev.pulsarconnect.core.ble.BikeTarget
import dev.pulsarconnect.core.ble.BlePermission
import dev.pulsarconnect.core.ble.BleScanSource
import dev.pulsarconnect.core.ble.BleScanner
import dev.pulsarconnect.core.ble.BleService
import dev.pulsarconnect.core.ble.BleSession
import dev.pulsarconnect.core.ble.BleSessionEvent
import dev.pulsarconnect.core.ble.BleTransport
import dev.pulsarconnect.core.ble.ConnectionState
import dev.pulsarconnect.core.ble.DiscoveredDevice
import dev.pulsarconnect.core.ble.InMemoryBikeDeviceStore
import dev.pulsarconnect.core.ble.InMemoryBikeLinkPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BleViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val vin = "MD2ABCDEFGH123456"
    private val target = BikeTarget("AA:BB:CC:DD:EE:FF", "Pulsar", vin)
    private val devices = InMemoryBikeDeviceStore()
    private val transport = FakeTransport()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun scanner(vararg devices: DiscoveredDevice): BikeScanner =
        BikeScanner(BleScanSource { kotlinx.coroutines.flow.flowOf(*devices) })

    private fun viewModel(
        connection: BikeConnection,
        controller: BikeLinkController,
        vararg scanDevices: DiscoveredDevice,
        runtime: BikeLinkRuntime = FakeRuntime(),
    ): BleViewModel {
        return BleViewModel(
            application = RuntimeEnvironment.getApplication(),
            devices = devices,
            scanner = scanner(*scanDevices),
            transport = transport,
            controller = controller,
            runtime = runtime,
        )
    }

    @Test
    fun `auto connect runs only when the vin opted in and a bike is selected`() = runTest(dispatcher) {
        devices.select(target)
        val connection = BikeConnection(transport, backgroundScope, clock = { 1_000L }, jitter = { it })
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = InMemoryBikeLinkPreferences(
                BikeLinkSettings(mirroring = false, keepInBackground = false, startOnBoot = false),
            ),
            statuses = flowOf(),
            scope = backgroundScope,
        )
        val viewModel = viewModel(connection, controller)

        viewModel.onSession(vin)
        advanceUntilIdle()
        assertEquals(0, transport.connectCalls)
        assertTrue(viewModel.state.value.connection is ConnectionState.Idle)

        devices.setAutoConnect(vin, true)
        viewModel.onSession(vin)
        viewModel.onForeground()
        advanceUntilIdle()
        runCurrent()

        assertEquals(1, transport.connectCalls)
        assertTrue(viewModel.state.value.connection is ConnectionState.Connected)
        viewModel.onBackground()
        viewModel.cancel()
    }

    @Test
    fun `selecting a device persists it and connects`() = runTest(dispatcher) {
        val connection = BikeConnection(transport, backgroundScope, clock = { 1_000L }, jitter = { it })
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = InMemoryBikeLinkPreferences(),
            statuses = flowOf(),
            scope = backgroundScope,
        )
        val viewModel = viewModel(connection, controller)
        viewModel.onSession(vin)
        advanceUntilIdle()

        viewModel.select(DiscoveredDevice(target.id, target.name, -42, bonded = false))
        advanceUntilIdle()
        runCurrent()

        assertEquals(target, devices.selected(vin))
        assertEquals(1, transport.connectCalls)
        assertTrue(viewModel.state.value.connection is ConnectionState.Connected)
        viewModel.onBackground()
        viewModel.cancel()
    }

    @Test
    fun `background stops scanning and disconnects`() = runTest(dispatcher) {
        devices.select(target)
        val connection = BikeConnection(transport, backgroundScope, clock = { 1_000L }, jitter = { it })
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = InMemoryBikeLinkPreferences(
                BikeLinkSettings(mirroring = false, keepInBackground = false, startOnBoot = false),
            ),
            statuses = flowOf(),
            scope = backgroundScope,
        )
        val viewModel = viewModel(connection, controller)
        viewModel.onSession(vin)
        viewModel.select(DiscoveredDevice(target.id, target.name, -42, bonded = false))
        advanceUntilIdle()
        runCurrent()
        assertTrue(viewModel.state.value.connection is ConnectionState.Connected)

        viewModel.onBackground()
        advanceUntilIdle()
        runCurrent()

        assertEquals(ConnectionState.Idle, viewModel.state.value.connection)
        assertFalse(viewModel.state.value.scanning)
        assertTrue(transport.sessions.single().closed)
        viewModel.cancel()
    }

    @Test
    fun `scan publishes deduplicated nearby devices`() = runTest(dispatcher) {
        val connection = BikeConnection(transport, backgroundScope, clock = { 1_000L }, jitter = { it })
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = InMemoryBikeLinkPreferences(
                BikeLinkSettings(mirroring = false, keepInBackground = false, startOnBoot = false),
            ),
            statuses = flowOf(),
            scope = backgroundScope,
        )
        val viewModel = viewModel(connection, controller,
            DiscoveredDevice("AA", "Pulsar", -70, false),
            DiscoveredDevice("AA", "Pulsar", -40, false),
            DiscoveredDevice("BB", null, -55, false),
        )
        viewModel.onSession(vin)
        advanceUntilIdle()
        runCurrent()

        viewModel.scan()
        advanceUntilIdle()
        runCurrent()

        assertEquals(listOf("AA", "BB"), viewModel.state.value.devices.map { it.id })
        assertEquals(-40, viewModel.state.value.devices.first().rssi)
        assertFalse(viewModel.state.value.scanning)
        viewModel.cancel()
    }

    @Test
    fun `switching vin closes previous bike and clears scan results`() = runTest(dispatcher) {
        devices.select(target)
        val connection = BikeConnection(transport, backgroundScope, jitter = { it })
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = InMemoryBikeLinkPreferences(
                BikeLinkSettings(mirroring = false, keepInBackground = false, startOnBoot = false),
            ),
            statuses = flowOf(),
            scope = backgroundScope,
        )
        val viewModel = viewModel(connection, controller,
            DiscoveredDevice("AA", "Pulsar", -70, false),
        )
        viewModel.onSession(vin)
        viewModel.connect()
        viewModel.scan()
        advanceUntilIdle()
        runCurrent()
        assertEquals(1, viewModel.state.value.devices.size)

        viewModel.onSession("MD2ABCDEFGH654321")
        advanceUntilIdle()
        runCurrent()

        assertEquals(ConnectionState.Idle, viewModel.state.value.connection)
        assertTrue(viewModel.state.value.devices.isEmpty())
        assertFalse(viewModel.state.value.scanning)
        viewModel.onBackground()
        viewModel.cancel()
    }

    @Test
    fun `disabling auto connect preserves session but prevents reconnect`() = runTest(dispatcher) {
        devices.select(target)
        devices.setAutoConnect(vin, true)
        val connection = BikeConnection(transport, backgroundScope, jitter = { it })
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = InMemoryBikeLinkPreferences(
                BikeLinkSettings(mirroring = false, keepInBackground = false, startOnBoot = false),
            ),
            statuses = flowOf(),
            scope = backgroundScope,
        )
        val viewModel = viewModel(connection, controller)
        viewModel.onSession(vin)
        advanceUntilIdle()
        runCurrent()

        viewModel.setAutoConnect(false)
        assertFalse(transport.sessions.single().closed)
        transport.sessions.single().disconnect()
        advanceUntilIdle()
        runCurrent()

        assertEquals(1, transport.connectCalls)
        assertTrue(viewModel.state.value.connection is ConnectionState.Failed)
        viewModel.onBackground()
        viewModel.cancel()
    }

    @Test
    fun `foreground retries auto connect after permissions become available`() = runTest(dispatcher) {
        devices.select(target)
        devices.setAutoConnect(vin, true)
        transport.missing = setOf(BlePermission.CONNECT)
        val connection = BikeConnection(transport, backgroundScope, jitter = { it })
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = InMemoryBikeLinkPreferences(
                BikeLinkSettings(mirroring = false, keepInBackground = false, startOnBoot = false),
            ),
            statuses = flowOf(),
            scope = backgroundScope,
        )
        val viewModel = viewModel(connection, controller)
        viewModel.onSession(vin)
        advanceUntilIdle()
        runCurrent()
        assertTrue(viewModel.state.value.connection is ConnectionState.MissingPermissions)

        transport.missing = emptySet()
        viewModel.onForeground()
        advanceUntilIdle()
        runCurrent()

        assertEquals(1, transport.connectCalls)
        assertTrue(viewModel.state.value.connection is ConnectionState.Connected)
        viewModel.onBackground()
        viewModel.cancel()
    }

    @Test
    fun `permission result resumes a link that was waiting for access`() = runTest(dispatcher) {
        devices.select(target)
        devices.setAutoConnect(vin, true)
        transport.missing = setOf(BlePermission.CONNECT)
        val connection = BikeConnection(transport, backgroundScope, jitter = { it })
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = InMemoryBikeLinkPreferences(
                BikeLinkSettings(mirroring = false, keepInBackground = false, startOnBoot = false),
            ),
            statuses = flowOf(),
            scope = backgroundScope,
        )
        val viewModel = viewModel(connection, controller)
        viewModel.onSession(vin)
        advanceUntilIdle()
        runCurrent()
        assertTrue(viewModel.state.value.connection is ConnectionState.MissingPermissions)

        transport.missing = emptySet()
        viewModel.onPermissionsChanged()
        advanceUntilIdle()
        runCurrent()

        assertEquals(1, transport.connectCalls)
        assertTrue(viewModel.state.value.connection is ConnectionState.Connected)
        viewModel.onBackground()
        viewModel.cancel()
    }

    @Test
    fun `signed out view model cannot start a scan`() = runTest(dispatcher) {
        val connection = BikeConnection(transport, backgroundScope)
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = InMemoryBikeLinkPreferences(
                BikeLinkSettings(mirroring = false, keepInBackground = false, startOnBoot = false),
            ),
            statuses = flowOf(),
            scope = backgroundScope,
        )
        val viewModel = viewModel(connection, controller, runtime = FakeRuntime())
        viewModel.onSession(vin)
        viewModel.signOut()
        viewModel.cancel()

        assertEquals(null, viewModel.state.value.vin)
    }

    private class FakeTransport : BleTransport {
        override val adapterState = MutableStateFlow(AdapterState.ON)
        var connectCalls = 0
            private set
        val sessions = mutableListOf<FakeSession>()

        var missing: Set<BlePermission> = emptySet()
        override fun missingPermissions(): Set<BlePermission> = missing

        override suspend fun connect(target: BikeTarget): BleSession =
            FakeSession().also {
                connectCalls++
                sessions += it
            }
    }

    private class FakeSession : BleSession {
        private val mutableEvents = MutableSharedFlow<BleSessionEvent>()
        override val events: Flow<BleSessionEvent> = mutableEvents
        var closed = false
            private set

        suspend fun disconnect() {
            mutableEvents.emit(BleSessionEvent.Disconnected(0))
        }

        override suspend fun services(): List<BleService> = emptyList()

        override suspend fun setNotifications(service: UUID, characteristic: UUID, enabled: Boolean) = Unit

        override suspend fun read(service: UUID, characteristic: UUID): ByteArray = ByteArray(0)

        override suspend fun requestMtu(mtu: Int): Int = mtu

        override suspend fun write(
            service: UUID,
            characteristic: UUID,
            bytes: ByteArray,
            withResponse: Boolean,
        ) = Unit

        override fun close() {
            closed = true
        }
    }

    private class FakeRuntime : BikeLinkRuntime {
        var enabled = false
            private set

        override fun setEnabled(enabled: Boolean) {
            this.enabled = enabled
        }
    }
}
