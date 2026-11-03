package dev.pulsarconnect.app.ble

import dev.pulsarconnect.core.ble.AdapterState
import dev.pulsarconnect.core.ble.BikeConnection
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
import dev.pulsarconnect.core.ble.PhoneStatus
import dev.pulsarconnect.core.ble.PhoneStatusProtocol
import java.util.UUID
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

    private fun TestScope.connectedViewModel(
        source: PhoneStatusSource = PhoneStatusSource { statuses },
    ): BleViewModel {
        devices.select(target)
        val connection = BikeConnection(transport, this, jitter = { it })
        val viewModel = BleViewModel(
            devices,
            BikeScanner(BleScanSource { flowOf() }),
            connection,
            transport,
            source,
        )
        viewModel.onSession(vin)
        viewModel.connect()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.connection is ConnectionState.Connected)
        return viewModel
    }

    private fun heartbeat(bytes: ByteArray): Int = bytes[53].toInt() and 0xff

    @Test
    fun `mirroring is opt in and sends once per second until disabled`() = runTest(dispatcher) {
        val viewModel = connectedViewModel()
        try {
            assertEquals(0, transport.sessions.single().writes.size)
            assertFalse(viewModel.state.value.phoneStatusEnabled)
            assertTrue(viewModel.state.value.phoneStatusAvailable)

            viewModel.setPhoneStatusEnabled(true)
            runCurrent()
            assertTrue(viewModel.state.value.phoneStatusEnabled)
            assertTrue(viewModel.state.value.phoneStatusSending)
            assertEquals(1, transport.sessions.single().writes.size)
            assertEquals(1, heartbeat(transport.sessions.single().writes[0]))

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, transport.sessions.single().writes.size)
            assertEquals(2, heartbeat(transport.sessions.single().writes[1]))

            viewModel.setPhoneStatusEnabled(false)
            runCurrent()
            val count = transport.sessions.single().writes.size
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(count, transport.sessions.single().writes.size)
            assertFalse(viewModel.state.value.phoneStatusEnabled)
            assertFalse(viewModel.state.value.phoneStatusSending)
            assertNull(viewModel.state.value.phoneStatusError)
        } finally {
            viewModel.signOut()
        }
    }

    @Test
    fun `background stops mirroring and requires a fresh opt in`() = runTest(dispatcher) {
        val viewModel = connectedViewModel()
        try {
            viewModel.setPhoneStatusEnabled(true)
            runCurrent()
            assertEquals(1, transport.sessions.single().writes.size)

            viewModel.onBackground()
            runCurrent()
            val count = transport.sessions.single().writes.size
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(count, transport.sessions.single().writes.size)
            assertFalse(viewModel.state.value.phoneStatusEnabled)
            assertFalse(viewModel.state.value.phoneStatusSending)
            assertEquals(ConnectionState.Idle, viewModel.state.value.connection)
        } finally {
            viewModel.signOut()
        }
    }

    @Test
    fun `sign out stops mirroring and clears stale state`() = runTest(dispatcher) {
        val viewModel = connectedViewModel()
        viewModel.setPhoneStatusEnabled(true)
        runCurrent()
        val writes = transport.sessions.single().writes.size

        viewModel.signOut()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertNull(viewModel.state.value.vin)
        assertFalse(viewModel.state.value.phoneStatusEnabled)
        assertEquals(writes, transport.sessions.single().writes.size)
    }

    @Test
    fun `switching vin stops mirroring before the new bike is used`() = runTest(dispatcher) {
        val viewModel = connectedViewModel()
        try {
            viewModel.setPhoneStatusEnabled(true)
            runCurrent()
            val writes = transport.sessions.single().writes.size

            viewModel.onSession("MD2ABCDEFGH654321")
            advanceUntilIdle()

            assertFalse(viewModel.state.value.phoneStatusEnabled)
            assertEquals(writes, transport.sessions.single().writes.size)
            assertTrue(transport.sessions.single().closed)
        } finally {
            viewModel.signOut()
        }
    }

    @Test
    fun `disconnect stops mirroring without an error`() = runTest(dispatcher) {
        val viewModel = connectedViewModel()
        try {
            viewModel.setPhoneStatusEnabled(true)
            runCurrent()
            val writes = transport.sessions.single().writes.size

            transport.sessions.single().disconnect()
            advanceTimeBy(1_000)
            runCurrent()

            assertFalse(viewModel.state.value.phoneStatusEnabled)
            assertNull(viewModel.state.value.phoneStatusError)
            assertEquals(writes, transport.sessions.single().writes.size)
        } finally {
            viewModel.signOut()
        }
    }

    @Test
    fun `unsupported peer cannot opt in`() = runTest(dispatcher) {
        transport.supported = false
        val viewModel = connectedViewModel()
        try {
            assertFalse(viewModel.state.value.phoneStatusAvailable)
            viewModel.setPhoneStatusEnabled(true)
            runCurrent()

            assertFalse(viewModel.state.value.phoneStatusEnabled)
            assertFalse(viewModel.state.value.phoneStatusSending)
            assertEquals("Connect a compatible bike first.", viewModel.state.value.phoneStatusError)
            assertTrue(transport.sessions.single().writes.isEmpty())
        } finally {
            viewModel.signOut()
        }
    }

    @Test
    fun `write failure stops mirroring and shows a generic error`() = runTest(dispatcher) {
        val failure = BleException(BleFailure.GattError("writeCharacteristic", 133))
        transport.writeFailure = failure
        val viewModel = connectedViewModel()
        try {
            viewModel.setPhoneStatusEnabled(true)
            runCurrent()

            assertFalse(viewModel.state.value.phoneStatusEnabled)
            assertFalse(viewModel.state.value.phoneStatusSending)
            assertEquals(
                "Phone status mirroring stopped. Reconnect and try again.",
                viewModel.state.value.phoneStatusError,
            )
        } finally {
            viewModel.signOut()
        }
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
        val viewModel = connectedViewModel(source)
        try {
            assertEquals(0, collections)

            viewModel.setPhoneStatusEnabled(true)
            runCurrent()

            assertEquals(1, collections)
            assertEquals(1, transport.sessions.single().writes.size)
        } finally {
            viewModel.signOut()
        }
    }

    private class SupportedTransport : BleTransport {
        override val adapterState = MutableStateFlow(AdapterState.ON)
        var missing: Set<BlePermission> = emptySet()
        var supported = true
        var writeFailure: Throwable? = null
        val sessions = mutableListOf<SupportedSession>()

        override fun missingPermissions(): Set<BlePermission> = missing

        override suspend fun connect(target: BikeTarget): BleSession =
            SupportedSession(this).also { sessions += it }
    }

    private class SupportedSession(
        private val transport: SupportedTransport,
    ) : BleSession {
        private val mutableEvents = MutableSharedFlow<BleSessionEvent>()
        override val events: Flow<BleSessionEvent> = mutableEvents
        var closed = false
            private set
        val writes = mutableListOf<ByteArray>()

        suspend fun disconnect() {
            mutableEvents.emit(BleSessionEvent.Disconnected(0))
        }

        override suspend fun services(): List<BleService> = if (transport.supported) {
            listOf(
                BleService(
                    PhoneStatusProtocol.SERVICE_UUID,
                    listOf(
                        BleCharacteristic(
                            PhoneStatusProtocol.GENERAL_UUID,
                            canRead = true,
                            canWrite = true,
                            canNotify = false,
                            canIndicate = false,
                        ),
                    ),
                ),
            )
        } else {
            emptyList()
        }

        override suspend fun setNotifications(service: UUID, characteristic: UUID, enabled: Boolean) = Unit

        override suspend fun read(service: UUID, characteristic: UUID): ByteArray = ByteArray(0)

        override suspend fun requestMtu(mtu: Int): Int = mtu

        override suspend fun write(
            service: UUID,
            characteristic: UUID,
            bytes: ByteArray,
            withResponse: Boolean,
        ) {
            writes += bytes.copyOf()
            transport.writeFailure?.let { throw it }
        }

        override fun close() {
            closed = true
        }
    }
}
