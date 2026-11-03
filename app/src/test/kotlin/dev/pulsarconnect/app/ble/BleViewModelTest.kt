package dev.pulsarconnect.app.ble

import dev.pulsarconnect.core.ble.AdapterState
import dev.pulsarconnect.core.ble.BikeConnection
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
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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

    private fun scanner(vararg devices: DiscoveredDevice): BleScanner =
        BikeScanner(BleScanSource { flowOf(*devices) })

    @Test
    fun `auto connect runs only when the vin opted in and a bike is selected`() = runTest(dispatcher) {
        devices.select(target)
        val connection = BikeConnection(transport, this, clock = { 1_000L }, jitter = { it })
        val viewModel = BleViewModel(devices, scanner(), connection, transport)

        viewModel.onSession(vin)
        advanceUntilIdle()
        assertEquals(0, transport.connectCalls)
        assertTrue(viewModel.state.value.connection is ConnectionState.Idle)

        devices.setAutoConnect(vin, true)
        viewModel.onSession(vin)
        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals(1, transport.connectCalls)
        assertTrue(viewModel.state.value.connection is ConnectionState.Connected)
        viewModel.onBackground()
    }

    @Test
    fun `selecting a device persists it and connects`() = runTest(dispatcher) {
        val connection = BikeConnection(transport, this, clock = { 1_000L }, jitter = { it })
        val viewModel = BleViewModel(devices, scanner(), connection, transport)
        viewModel.onSession(vin)

        viewModel.select(DiscoveredDevice(target.id, target.name, -42, bonded = false))
        advanceUntilIdle()

        assertEquals(target, devices.selected(vin))
        assertEquals(1, transport.connectCalls)
        assertTrue(viewModel.state.value.connection is ConnectionState.Connected)
        viewModel.onBackground()
    }

    @Test
    fun `background stops scanning and disconnects`() = runTest(dispatcher) {
        devices.select(target)
        val connection = BikeConnection(transport, this, clock = { 1_000L }, jitter = { it })
        val viewModel = BleViewModel(devices, scanner(), connection, transport)
        viewModel.onSession(vin)
        viewModel.select(DiscoveredDevice(target.id, target.name, -42, bonded = false))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.connection is ConnectionState.Connected)

        viewModel.onBackground()
        advanceUntilIdle()

        assertEquals(ConnectionState.Idle, viewModel.state.value.connection)
        assertFalse(viewModel.state.value.scanning)
        assertTrue(transport.sessions.single().closed)
    }

    @Test
    fun `scan publishes deduplicated nearby devices`() = runTest(dispatcher) {
        val connection = BikeConnection(transport, this, clock = { 1_000L }, jitter = { it })
        val viewModel = BleViewModel(
            devices,
            scanner(
                DiscoveredDevice("AA", "Pulsar", -70, false),
                DiscoveredDevice("AA", "Pulsar", -40, false),
                DiscoveredDevice("BB", null, -55, false),
            ),
            connection,
            transport,
        )
        viewModel.onSession(vin)

        viewModel.scan()
        advanceUntilIdle()

        assertEquals(listOf("AA", "BB"), viewModel.state.value.devices.map { it.id })
        assertEquals(-40, viewModel.state.value.devices.first().rssi)
        assertFalse(viewModel.state.value.scanning)
    }

    @Test
    fun `switching vin closes previous bike and clears scan results`() = runTest(dispatcher) {
        devices.select(target)
        val connection = BikeConnection(transport, this, jitter = { it })
        val viewModel = BleViewModel(
            devices,
            scanner(DiscoveredDevice(target.id, target.name, -42, false)),
            connection,
            transport,
        )
        viewModel.onSession(vin)
        viewModel.connect()
        viewModel.scan()
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.devices.size)

        viewModel.onSession("MD2ABCDEFGH654321")
        advanceUntilIdle()

        assertTrue(transport.sessions.single().closed)
        assertEquals(ConnectionState.Idle, viewModel.state.value.connection)
        assertTrue(viewModel.state.value.devices.isEmpty())
        assertFalse(viewModel.state.value.scanning)
        viewModel.onBackground()
    }

    @Test
    fun `disabling auto connect preserves session but prevents reconnect`() = runTest(dispatcher) {
        devices.select(target)
        devices.setAutoConnect(vin, true)
        val connection = BikeConnection(transport, this, jitter = { it })
        val viewModel = BleViewModel(devices, scanner(), connection, transport)
        viewModel.onSession(vin)
        advanceUntilIdle()

        viewModel.setAutoConnect(false)
        assertFalse(transport.sessions.single().closed)
        transport.sessions.single().disconnect()
        advanceUntilIdle()

        assertEquals(1, transport.connectCalls)
        assertTrue(viewModel.state.value.connection is ConnectionState.Failed)
        viewModel.onBackground()
    }

    @Test
    fun `foreground retries auto connect after permissions become available`() = runTest(dispatcher) {
        devices.select(target)
        devices.setAutoConnect(vin, true)
        transport.missing = setOf(BlePermission.CONNECT)
        val connection = BikeConnection(transport, this, jitter = { it })
        val viewModel = BleViewModel(devices, scanner(), connection, transport)
        viewModel.onSession(vin)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.connection is ConnectionState.MissingPermissions)

        transport.missing = emptySet()
        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals(1, transport.connectCalls)
        assertTrue(viewModel.state.value.connection is ConnectionState.Connected)
        viewModel.onBackground()
    }

    @Test
    fun `signed out view model cannot start a scan`() = runTest(dispatcher) {
        var scanCalls = 0
        val scanner = object : BleScanner {
            override fun scan(timeoutMillis: Long): Flow<List<DiscoveredDevice>> {
                scanCalls++
                return flowOf(emptyList())
            }
        }
        val connection = BikeConnection(transport, this)
        val viewModel = BleViewModel(devices, scanner, connection, transport)
        viewModel.onSession(vin)
        viewModel.signOut()

        viewModel.scan()
        advanceUntilIdle()

        assertEquals(0, scanCalls)
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
}
