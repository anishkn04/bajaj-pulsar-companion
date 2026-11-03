package dev.pulsarconnect.app.ble

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes

import dev.pulsarconnect.core.ble.BikeConnection
import dev.pulsarconnect.core.ble.BikeTarget
import dev.pulsarconnect.core.ble.BleCharacteristic
import dev.pulsarconnect.core.ble.BleException
import dev.pulsarconnect.core.ble.BleFailure
import dev.pulsarconnect.core.ble.BleService
import dev.pulsarconnect.core.ble.BleSessionEvent
import dev.pulsarconnect.core.ble.ConnectionState
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowBluetoothGatt

@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [35],
    shadows = [AndroidBleTransportTest.TestGatt::class],
)
class AndroidBleTransportTest {
    private val target = BikeTarget("AA:BB:CC:DD:EE:FF", "Pulsar", "MD2ABCDEFGH123456")
    private lateinit var transport: AndroidBleTransport
    private lateinit var device: ShadowBluetoothDevice
    private lateinit var gatt: BluetoothGatt
    private lateinit var platform: TestGatt
    private lateinit var callback: BluetoothGattCallback
    private var onConnect: () -> Unit = {}
    private var onDiscover: () -> Unit = {}

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        device = shadowOf(adapter.getRemoteDevice(target.id))
        device.setGattConnectionInterceptor { client ->
            gatt = client
            platform = Shadow.extract(client)
            callback = platform.gattCallback
            platform.onDiscover = { onDiscover() }
            callback.onConnectionStateChange(client, BluetoothGatt.GATT_SUCCESS, BluetoothGatt.STATE_CONNECTED)
            onConnect()
        }
        transport = AndroidBleTransport(context)
    }

    @After
    fun tearDown() {
        if (::gatt.isInitialized) gatt.close()
        transport.close()
    }

    @Test
    fun `connect returns before starting discovery`() = runBlocking {
        val session = withTimeout(2_000) { transport.connect(target) }
        try {
            assertEquals(0, platform.discoveryCalls)
            assertFalse(platform.isClosed)
        } finally {
            session.close()
        }
    }

    @Test
    fun `core shows discovering and owns discovery timeout and close`() = runBlocking {
        val connection = BikeConnection(
            transport, this,
            connectTimeoutMillis = 5_000,
            discoveryTimeoutMillis = 200,
        )
        try {
            connection.connect(target, autoReconnect = false)
            withTimeout(2_000) { connection.state.first { it is ConnectionState.Discovering } }
            assertFalse(platform.isClosed)
            assertEquals(
                ConnectionState.Failed(target, BleFailure.DiscoveryTimeout(200)),
                withTimeout(2_000) { connection.state.first { it is ConnectionState.Failed } },
            )
            assertTrue(platform.isClosed)
            assertEquals(1, platform.closeCalls)
        } finally {
            connection.stop()
        }
    }

    @Test
    fun `services discovers and maps raw characteristic capabilities`() = runBlocking {
        val serviceId = UUID.randomUUID()
        val characteristicId = UUID.randomUUID()
        val service = BluetoothGattService(serviceId, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                characteristicId,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE,
                0,
            ),
        )
        onDiscover = { callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS) }
        val session = withTimeout(2_000) { transport.connect(target) }
        try {
            platform.discoveredServices = listOf(service)
            assertEquals(
                listOf(BleService(serviceId, listOf(BleCharacteristic(characteristicId, true, true, false, true)))),
                withTimeout(2_000) { session.services() },
            )
            assertEquals(1, platform.discoveryCalls)
        } finally {
            session.close()
        }
    }

    @Test
    fun `discovery start refusal is a discovery gatt error`() = runBlocking {
        // Also lets the old transport reach services(), so the assertion catches the misplaced discovery.
        onDiscover = { callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS) }
        val session = withTimeout(2_000) { transport.connect(target) }
        try {
            platform.discoveryAccepted = false
            assertFailure(BleFailure.GattError("discoverServices", -1)) { session.services() }
        } finally {
            session.close()
        }
    }

    @Test
    fun `discovery callback error preserves status`() = runBlocking {
        onDiscover = { callback.onServicesDiscovered(gatt, 133) }
        val session = withTimeout(2_000) { transport.connect(target) }
        try {
            assertFailure(BleFailure.GattError("discoverServices", 133)) { session.services() }
        } finally {
            session.close()
        }
    }

    @Test
    fun `disconnect during discovery fails promptly instead of timing out`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        onDiscover = { started.complete(Unit) }
        val connection = BikeConnection(
            transport, this,
            connectTimeoutMillis = 5_000,
            discoveryTimeoutMillis = 5_000,
        )
        try {
            connection.connect(target, autoReconnect = false)
            withTimeout(2_000) { started.await() }
            callback.onConnectionStateChange(gatt, 19, BluetoothGatt.STATE_DISCONNECTED)
            assertEquals(
                ConnectionState.Failed(target, BleFailure.Disconnected(19)),
                withTimeout(2_000) { connection.state.first { it is ConnectionState.Failed } },
            )
            assertTrue(platform.isClosed)
            assertEquals(1, platform.closeCalls)
        } finally {
            connection.stop()
        }
    }

    @Test
    fun `disconnect before connect hands off session is retained`() = runBlocking {
        onConnect = { callback.onConnectionStateChange(gatt, 22, BluetoothGatt.STATE_DISCONNECTED) }
        onDiscover = { callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS) }
        val session = withTimeout(2_000) { transport.connect(target) }
        try {
            assertEquals(
                BleSessionEvent.Disconnected(22),
                withTimeout(2_000) { session.events.first { it is BleSessionEvent.Disconnected } },
            )
            assertFailure(BleFailure.Disconnected(22)) { session.services() }
        } finally {
            session.close()
        }
    }

    @Test
    fun `disconnect after discovery before event collection is replayed to core`() = runBlocking {
        onDiscover = {
            callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
            callback.onConnectionStateChange(gatt, 8, BluetoothGatt.STATE_DISCONNECTED)
        }
        val connection = BikeConnection(transport, this)
        try {
            connection.connect(target, autoReconnect = false)
            assertEquals(
                ConnectionState.Failed(target, BleFailure.Disconnected(8)),
                withTimeout(2_000) { connection.state.first { it is ConnectionState.Failed } },
            )
            assertTrue(platform.isClosed)
        } finally {
            connection.stop()
        }
    }

    @Test
    fun `cancelling core during discovery closes its owned session exactly once`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        onDiscover = { started.complete(Unit) }
        val owner = Job()
        val connection = BikeConnection(transport, CoroutineScope(coroutineContext + owner))
        try {
            connection.connect(target, autoReconnect = false)
            withTimeout(2_000) { started.await() }
            assertEquals(ConnectionState.Discovering(target), connection.state.value)
            owner.cancelAndJoin()
            assertEquals(ConnectionState.Idle, connection.state.value)
            assertTrue(platform.isClosed)
            assertEquals(1, platform.closeCalls)
        } finally {
            owner.cancelAndJoin()
            connection.stop()
        }
    }

    @Test
    fun `mtu negotiation returns and caches the platform value`() = runBlocking {
        val session = withTimeout(2_000) { transport.connect(target) }
        try {
            assertEquals(146, requestMtu(session, requested = 146, negotiated = 146))
            assertEquals(146, withTimeout(2_000) { session.requestMtu(146) })
            assertEquals(1, platform.mtuRequests)
            assertEquals(146, platform.lastMtuRequest)
        } finally {
            session.close()
        }
    }

    @Test
    fun `mtu refusal preserves status and clears the pending request`() = runBlocking {
        val session = withTimeout(2_000) { transport.connect(target) }
        try {
            assertFailure(BleFailure.GattError("requestMtu", 133)) {
                requestMtu(session, requested = 146, negotiated = 146, status = 133)
            }

            assertEquals(146, requestMtu(session, requested = 146, negotiated = 146))
            assertEquals(2, platform.mtuRequests)
        } finally {
            session.close()
        }
    }

    @Test
    fun `mtu timeout is recoverable and ignores a late callback`() = runBlocking {
        val shortTransport = AndroidBleTransport(
            RuntimeEnvironment.getApplication(),
            operationTimeoutMillis = 50,
        )
        val session = withTimeout(2_000) { shortTransport.connect(target) }
        try {
            assertFailure(BleFailure.Unexpected("requestMtu timed out")) {
                withTimeout(2_000) { session.requestMtu(146) }
            }

            callback.onMtuChanged(gatt, 23, BluetoothGatt.GATT_SUCCESS)
            assertEquals(146, requestMtu(session, requested = 146, negotiated = 146))
        } finally {
            session.close()
            shortTransport.close()
        }
    }

    @Test
    fun `disconnect during mtu negotiation fails promptly`() = runBlocking {
        supervisorScope {
            val session = withTimeout(2_000) { transport.connect(target) }
            try {
                val before = platform.mtuRequests
                val request = async { session.requestMtu(146) }
                withTimeout(2_000) {
                    while (platform.mtuRequests == before) delay(10)
                }

                callback.onConnectionStateChange(gatt, 19, BluetoothGatt.STATE_DISCONNECTED)

                val failure = runCatching { withTimeout(2_000) { request.await() } }.exceptionOrNull()
                assertEquals(BleFailure.Disconnected(19), (failure as BleException).failure)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `writes larger than the negotiated mtu fail before reaching the platform`() = runBlocking {
        val serviceId = UUID.randomUUID()
        val characteristicId = UUID.randomUUID()
        val service = BluetoothGattService(serviceId, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                characteristicId,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                0,
            ),
        )
        onDiscover = { callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS) }
        val session = withTimeout(2_000) { transport.connect(target) }
        try {
            platform.discoveredServices = listOf(service)
            assertEquals(1, session.services().size)

            assertEquals(23, requestMtu(session, requested = 146, negotiated = 23))
            assertFailure(BleFailure.MtuTooSmall(58, 23)) {
                session.write(serviceId, characteristicId, ByteArray(55), withResponse = true)
            }
            assertEquals(0, platform.writeCalls)

            writeFrame(session, serviceId, characteristicId, ByteArray(20))
            assertEquals(1, platform.writeCalls)
        } finally {
            session.close()
        }
    }

    @Test
    fun `default att mtu rejects a frame before negotiation`() = runBlocking {
        val serviceId = UUID.randomUUID()
        val characteristicId = UUID.randomUUID()
        val service = BluetoothGattService(serviceId, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                characteristicId,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                0,
            ),
        )
        onDiscover = { callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS) }
        val session = withTimeout(2_000) { transport.connect(target) }
        try {
            platform.discoveredServices = listOf(service)
            session.services()
            assertFailure(BleFailure.MtuTooSmall(58, 23)) {
                session.write(serviceId, characteristicId, ByteArray(55), withResponse = true)
            }
            assertEquals(0, platform.writeCalls)
        } finally {
            session.close()
        }
    }

    private suspend fun requestMtu(
        session: dev.pulsarconnect.core.ble.BleSession,
        requested: Int,
        negotiated: Int,
        status: Int = BluetoothGatt.GATT_SUCCESS,
    ): Int = kotlinx.coroutines.coroutineScope {
        val before = platform.mtuRequests
        val request = async { session.requestMtu(requested) }
        withTimeout(2_000) {
            while (platform.mtuRequests == before) delay(1)
        }
        callback.onMtuChanged(gatt, negotiated, status)
        withTimeout(2_000) { request.await() }
    }

    private suspend fun writeFrame(
        session: dev.pulsarconnect.core.ble.BleSession,
        serviceId: UUID,
        characteristicId: UUID,
        bytes: ByteArray,
    ) = kotlinx.coroutines.coroutineScope {
        val characteristic = gatt.getService(serviceId).getCharacteristic(characteristicId)
        val before = platform.writeCalls
        val request = async {
            session.write(serviceId, characteristicId, bytes, withResponse = true)
        }
        withTimeout(2_000) {
            while (platform.writeCalls == before) delay(1)
        }
        callback.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        withTimeout(2_000) { request.await() }
    }

    private suspend fun assertFailure(expected: BleFailure, block: suspend () -> Unit) {
        val failure = try {
            withTimeout(2_000) { block() }
            null
        } catch (error: BleException) {
            error.failure
        }
        assertEquals(expected, failure)
    }

    @Implements(BluetoothGatt::class)
    class TestGatt : ShadowBluetoothGatt() {

        var discoveryCalls = 0
        var closeCalls = 0
        var discoveryAccepted = true
        var discoveredServices: List<BluetoothGattService> = emptyList()
        var onDiscover: () -> Unit = {}

        var mtuRequests = 0
        var lastMtuRequest = 0
        var mtuAccepted = true

        var writeCalls = 0

        @Implementation
        override fun discoverServices(): Boolean {
            discoveryCalls++
            if (discoveryAccepted) onDiscover()
            return discoveryAccepted
        }

        @Implementation
        override fun getServices(): List<BluetoothGattService> = discoveredServices

        @Implementation
        override fun getService(uuid: UUID): BluetoothGattService? =
            discoveredServices.firstOrNull { it.uuid == uuid }

        @Implementation
        override fun requestMtu(mtu: Int): Boolean {
            mtuRequests++
            lastMtuRequest = mtu
            return mtuAccepted
        }

        @Implementation
        override fun writeCharacteristic(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            writeType: Int,
        ): Int {
            writeCalls++
            return BluetoothStatusCodes.SUCCESS
        }

        @Implementation
        override fun close() {
            closeCalls++
            super.close()
        }
    }
}
