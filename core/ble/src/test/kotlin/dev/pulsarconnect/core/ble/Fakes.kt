package dev.pulsarconnect.core.ble

import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeBleTransport : BleTransport {
    override val adapterState = MutableStateFlow(AdapterState.ON)
    var missing: Set<BlePermission> = emptySet()
    var connectDelayMillis: Long = 0
    var connectFailure: Throwable? = null
    var services: List<BleService> = emptyList()
    var servicesDelayMillis: Long = 0
    var servicesFailure: Throwable? = null
    var servicesAction: (suspend () -> List<BleService>)? = null
    var readResponse: ByteArray = ByteArray(0)
    var negotiatedMtu: Int = PhoneStatusProtocol.PREFERRED_MTU
    var mtuFailure: Throwable? = null
    var mtuDelayMillis: Long = 0
    var mtuAction: (suspend () -> Int)? = null
    var writeAction: (suspend () -> Unit)? = null

    val sessions = mutableListOf<FakeBleSession>()
    var connectCalls: Int = 0
        private set
    val passiveCalls = mutableListOf<Boolean>()

    override fun missingPermissions(): Set<BlePermission> = missing

    override suspend fun connect(target: BikeTarget, passive: Boolean): BleSession {
        connectCalls++
        passiveCalls += passive
        if (connectDelayMillis > 0) delay(connectDelayMillis)
        connectFailure?.let { throw it }
        return FakeBleSession(this).also { sessions += it }
    }

    val latestSession: FakeBleSession
        get() = sessions.last()
}

class FakeBleSession(
    private val transport: FakeBleTransport,
) : BleSession {
    private val mutableEvents = MutableSharedFlow<BleSessionEvent>(extraBufferCapacity = 16)
    override val events: Flow<BleSessionEvent> = mutableEvents

    var closed: Boolean = false
        private set
    var closeCount: Int = 0
        private set

    override suspend fun services(): List<BleService> {
        transport.servicesAction?.let { return it() }
        if (transport.servicesDelayMillis > 0) delay(transport.servicesDelayMillis)
        transport.servicesFailure?.let { throw it }
        return transport.services
    }

    override suspend fun setNotifications(service: UUID, characteristic: UUID, enabled: Boolean) = Unit

    val reads = mutableListOf<Pair<UUID, UUID>>()

    override suspend fun read(service: UUID, characteristic: UUID): ByteArray {
        reads += service to characteristic
        return transport.readResponse.copyOf()
    }

    var negotiatedMtu: Int = transport.negotiatedMtu
    var mtuFailure: Throwable? = transport.mtuFailure
    var mtuDelayMillis: Long = transport.mtuDelayMillis
    var mtuAction: (suspend () -> Int)? = transport.mtuAction
    val mtuRequests = mutableListOf<Int>()

    override suspend fun requestMtu(mtu: Int): Int {
        mtuRequests += mtu
        mtuAction?.let { return it() }
        if (mtuDelayMillis > 0) delay(mtuDelayMillis)
        mtuFailure?.let { throw it }
        return negotiatedMtu
    }

    data class Write(
        val service: UUID,
        val characteristic: UUID,
        val bytes: ByteArray,
        val withResponse: Boolean,
    )

    val writes = mutableListOf<Write>()
    var writeAction: (suspend () -> Unit)? = transport.writeAction

    override suspend fun write(
        service: UUID,
        characteristic: UUID,
        bytes: ByteArray,
        withResponse: Boolean,
    ) {
        writes += Write(service, characteristic, bytes.copyOf(), withResponse)
        writeAction?.invoke()
    }

    fun disconnect(status: Int = 0) {
        check(mutableEvents.tryEmit(BleSessionEvent.Disconnected(status))) {
            "Could not emit disconnect; the test buffer is full"
        }
    }

    override fun close() {
        closed = true
        closeCount++
    }
}

fun testTarget(
    id: String = "AA:BB:CC:DD:EE:FF",
    name: String? = "Pulsar",
    vin: String = "MD2ABCDEFGH123456",
): BikeTarget = BikeTarget(id = id, name = name, vin = vin)

fun testDevice(
    id: String,
    rssi: Int,
    name: String? = id,
    bonded: Boolean = false,
): DiscoveredDevice = DiscoveredDevice(id = id, name = name, rssi = rssi, bonded = bonded)
