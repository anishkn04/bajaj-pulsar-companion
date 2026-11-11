package dev.pulsarconnect.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import dev.pulsarconnect.core.ble.AdapterState
import dev.pulsarconnect.core.ble.BleCharacteristic
import dev.pulsarconnect.core.ble.BleException
import dev.pulsarconnect.core.ble.BleFailure
import dev.pulsarconnect.core.ble.BlePermission
import dev.pulsarconnect.core.ble.BleService
import dev.pulsarconnect.core.ble.BleSession
import dev.pulsarconnect.core.ble.BleSessionEvent
import dev.pulsarconnect.core.ble.BleTransport
import dev.pulsarconnect.core.ble.BikeTarget
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Android GATT adapter.
 *
 * This class deliberately exposes raw services and bytes. Cluster UUIDs and frame meaning
 * arrive with the protocol capture; nothing here guesses them.
 */
class AndroidBleTransport(
    private val context: Context,
    private val operationTimeoutMillis: Long = DEFAULT_OPERATION_TIMEOUT_MILLIS,
) : BleTransport, AutoCloseable {

    init {
        require(operationTimeoutMillis > 0) { "Operation timeout must be positive" }
    }

    private val manager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    private val _adapterState = MutableStateFlow(readAdapterState())
    override val adapterState: StateFlow<AdapterState> = _adapterState.asStateFlow()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            _adapterState.value = readAdapterState()
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(stateReceiver, filter)
            }
        } catch (_: SecurityException) {
            // Without nearby-device permission the UI still sees a static adapter state.
        }
    }

    override fun missingPermissions(): Set<BlePermission> =
        AndroidBlePermissions.missing(context, Build.VERSION.SDK_INT)

    override fun refreshAdapterState() {
        _adapterState.value = readAdapterState()
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(target: BikeTarget, passive: Boolean): BleSession = withContext(Dispatchers.IO) {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) throw BleException(BleFailure.PermissionDenied(missing))

        val adapter = manager?.adapter
            ?: throw BleException(BleFailure.Unexpected("Bluetooth is not supported on this device"))
        if (!adapter.isEnabled) throw BleException(BleFailure.AdapterOff)

        val device = try {
            adapter.getRemoteDevice(target.id)
        } catch (_: IllegalArgumentException) {
            throw BleException(BleFailure.Unexpected("The selected Bluetooth device is no longer known"))
        }

        val callback = Callback()
        val gatt = try {
            // A passive client is kept by the Bluetooth stack and connects when the bike
            // advertises again. It avoids background polling/scanning while the bike is off.
            device.connectGatt(context, passive, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (error: SecurityException) {
            throw BleException(
                BleFailure.PermissionDenied(
                    AndroidBlePermissions.missing(context, Build.VERSION.SDK_INT),
                ),
                error,
            )
        } ?: throw BleException(BleFailure.Unexpected("Android could not open a GATT client"))

        try {
            val connectStatus = callback.connected.await()
            if (connectStatus != BluetoothGatt.GATT_SUCCESS) {
                throw BleException(BleFailure.GattError("connect", connectStatus))
            }
            AndroidBleSession(
                gatt,
                callback.discovered,
                callback.disconnected,
                callback.disconnectEvents.asSharedFlow(),
                operationTimeoutMillis,
            ).also { callback.session = it }
        } catch (error: Exception) {
            try {
                gatt.disconnect()
            } catch (_: Exception) {
                // Already disconnected.
            }
            try {
                gatt.close()
            } catch (_: Exception) {
                // Already closed.
            }
            throw error
        }
    }

    @SuppressLint("MissingPermission")
    private fun readAdapterState(): AdapterState {
        val adapter = manager?.adapter ?: return AdapterState.OFF
        return try {
            if (adapter.isEnabled) AdapterState.ON else AdapterState.OFF
        } catch (_: SecurityException) {
            AdapterState.UNKNOWN
        }
    }

    override fun close() {
        try {
            context.unregisterReceiver(stateReceiver)
        } catch (_: IllegalArgumentException) {
            // Never registered or already unregistered.
        }
    }

    private inner class Callback : BluetoothGattCallback() {
        val connected = CompletableDeferred<Int>()
        val discovered = CompletableDeferred<Int>()
        val disconnected = CompletableDeferred<Nothing>()
        val disconnectEvents = MutableSharedFlow<BleSessionEvent.Disconnected>(replay = 1)
        @Volatile
        var session: AndroidBleSession? = null

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    if (!connected.isCompleted) connected.complete(status)
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    val failure = BleException(BleFailure.Disconnected(status))
                    connected.completeExceptionally(failure)
                    discovered.completeExceptionally(failure)
                    disconnected.completeExceptionally(failure)
                    // Retain disconnects even before the session is attached or core subscribes.
                    disconnectEvents.tryEmit(BleSessionEvent.Disconnected(status))
                    session?.onDisconnected(status)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!discovered.isCompleted) discovered.complete(status)
        }

        @Deprecated("Deprecated by Android 13, which also calls the byte-array overload")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            session?.onChanged(
                characteristic.service.uuid,
                characteristic.uuid,
                characteristic.value ?: ByteArray(0),
            )
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            session?.onChanged(characteristic.service.uuid, characteristic.uuid, value)
        }

        @Deprecated("Deprecated by Android 13, which also calls the byte-array overload")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            session?.completeRead(characteristic.value, status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            session?.completeRead(value, status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            session?.completeOperation(status)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            session?.completeOperation(status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            session?.completeMtu(mtu, status)
        }
    }

    private companion object {
        const val DEFAULT_OPERATION_TIMEOUT_MILLIS = 10_000L
    }
}

private class AndroidBleSession(
    private val gatt: BluetoothGatt,
    private val discoveryResult: Deferred<Int>,
    private val disconnected: Deferred<Nothing>,
    disconnectEvents: Flow<BleSessionEvent.Disconnected>,
    private val operationTimeoutMillis: Long,
) : BleSession {

    private val operationMutex = Mutex()
    private val mutableEvents = MutableSharedFlow<BleSessionEvent>(extraBufferCapacity = 32)
    override val events = merge(disconnectEvents, mutableEvents.asSharedFlow())
    private var discoveredServices: List<BleService>? = null

    @Volatile
    private var pending: CompletableDeferred<Int>? = null

    @Volatile
    private var pendingMtu: CompletableDeferred<Int>? = null

    @Volatile
    private var negotiatedMtu: Int? = null

    @Volatile
    private var lastReadValue: ByteArray? = null

    @Volatile
    private var closed: Boolean = false

    @SuppressLint("MissingPermission")
    override suspend fun services(): List<BleService> = operationMutex.withLock {
        check(!closed) { "Bluetooth session is closed" }
        if (disconnected.isCompleted) disconnected.await()
        discoveredServices?.let { return@withLock it }

        if (!gatt.discoverServices()) {
            throw BleException(BleFailure.GattError("discoverServices", -1))
        }
        // BikeConnection owns the discovery timeout and closes this session on cancellation.
        val status = discoveryResult.await()
        if (status != BluetoothGatt.GATT_SUCCESS) {
            throw BleException(BleFailure.GattError("discoverServices", status))
        }
        gatt.services.map { service ->
            BleService(
                uuid = service.uuid,
                characteristics = service.characteristics.map { characteristic ->
                    BleCharacteristic(
                        uuid = characteristic.uuid,
                        canRead = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0,
                        canWrite = characteristic.properties and (
                            BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                            ) != 0,
                        canNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
                        canIndicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
                    )
                },
            )
        }.also { discoveredServices = it }
    }

    @SuppressLint("MissingPermission")
    override suspend fun setNotifications(
        service: UUID,
        characteristic: UUID,
        enabled: Boolean,
    ) {
        val target = requireCharacteristic(service, characteristic)
        val descriptor = target.getDescriptor(CCCD_UUID)
            ?: throw BleException(BleFailure.Unexpected("Characteristic has no client configuration descriptor"))
        val value = if (enabled) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }

        runOperation(
            name = "writeDescriptor",
            start = {
                if (!gatt.setCharacteristicNotification(target, enabled)) {
                    false
                } else if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            },
            result = { Unit },
        )
    }

    @SuppressLint("MissingPermission")
    override suspend fun read(service: UUID, characteristic: UUID): ByteArray {
        val target = requireCharacteristic(service, characteristic)
        return runOperation(
            name = "readCharacteristic",
            start = {
                lastReadValue = null
                gatt.readCharacteristic(target)
            },
            result = { lastReadValue ?: ByteArray(0) },
        )
    }

    @SuppressLint("MissingPermission")
    override suspend fun requestMtu(mtu: Int): Int {
        require(mtu in DEFAULT_ATT_MTU..MAX_ATT_MTU) { "MTU must be between $DEFAULT_ATT_MTU and $MAX_ATT_MTU" }
        if (disconnected.isCompleted) disconnected.await()
        negotiatedMtu?.let { if (it >= mtu) return it }

        return operationMutex.withLock {
            check(!closed) { "Bluetooth session is closed" }
            negotiatedMtu?.let { if (it >= mtu) return@withLock it }
            val deferred = CompletableDeferred<Int>()
            pendingMtu = deferred
            try {
                if (!gatt.requestMtu(mtu)) {
                    throw BleException(BleFailure.GattError("requestMtu", -1))
                }
                val negotiated = try {
                    withTimeout(operationTimeoutMillis) { deferred.await() }
                } catch (timeout: TimeoutCancellationException) {
                    throw BleException(BleFailure.Unexpected("requestMtu timed out"), timeout)
                }
                negotiatedMtu = negotiated
                negotiated
            } finally {
                pendingMtu = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun write(
        service: UUID,
        characteristic: UUID,
        bytes: ByteArray,
        withResponse: Boolean,
    ) {
        val target = requireCharacteristic(service, characteristic)
        val payloadMtu = bytes.size + ATT_HEADER_SIZE
        val availableMtu = negotiatedMtu ?: DEFAULT_ATT_MTU
        if (payloadMtu > availableMtu) {
            throw BleException(BleFailure.MtuTooSmall(payloadMtu, availableMtu))
        }
        val writeType = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        runOperation(
            name = "writeCharacteristic",
            start = {
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(target, bytes, writeType) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    target.writeType = writeType
                    @Suppress("DEPRECATION")
                    target.value = bytes
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(target)
                }
            },
            result = { Unit },
        )
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        if (closed) return
        closed = true
        val failure = BleException(BleFailure.Disconnected(-1))
        pending?.completeExceptionally(failure)
        pending = null
        pendingMtu?.completeExceptionally(failure)
        pendingMtu = null
        try {
            // Close alone leaves a pending ATT transaction on the stack until its 30 s response
            // timeout. Disconnecting first cancels it and tears the ACL down immediately.
            gatt.disconnect()
        } catch (_: Exception) {
            // Already disconnected or the stack went away.
        }
        try {
            gatt.close()
        } catch (_: Exception) {
            // Already closed by the platform.
        }
    }

    fun onChanged(service: UUID, characteristic: UUID, value: ByteArray) {
        mutableEvents.tryEmit(BleSessionEvent.Changed(service, characteristic, value))
    }

    fun onDisconnected(status: Int) {
        val failure = BleException(BleFailure.Disconnected(status))
        pending?.completeExceptionally(failure)
        pendingMtu?.completeExceptionally(failure)
    }

    fun completeOperation(status: Int) {
        pending?.complete(status)
    }

    fun completeMtu(mtu: Int, status: Int) {
        val deferred = pendingMtu ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            deferred.complete(mtu)
        } else {
            deferred.completeExceptionally(BleException(BleFailure.GattError("requestMtu", status)))
        }
    }

    fun completeRead(value: ByteArray?, status: Int) {
        if (value != null) lastReadValue = value
        pending?.complete(status)
    }

    private suspend fun <T> runOperation(
        name: String,
        start: () -> Boolean,
        result: () -> T,
    ): T = operationMutex.withLock {
        check(!closed) { "Bluetooth session is closed" }
        val deferred = CompletableDeferred<Int>()
        pending = deferred
        try {
            if (!start()) throw BleException(BleFailure.GattError(name, -1))
            val status = try {
                withTimeout(operationTimeoutMillis) { deferred.await() }
            } catch (timeout: TimeoutCancellationException) {
                throw BleException(BleFailure.Unexpected("$name timed out"), timeout)
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw BleException(BleFailure.GattError(name, status))
            }
            result()
        } finally {
            pending = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun requireCharacteristic(
        service: UUID,
        characteristic: UUID,
    ): BluetoothGattCharacteristic {
        val target = gatt.getService(service)?.getCharacteristic(characteristic)
            ?: throw BleException(BleFailure.Unexpected("Characteristic is not present on this device"))
        return target
    }

    private companion object {
        const val ATT_HEADER_SIZE = 3
        const val DEFAULT_ATT_MTU = 23
        const val MAX_ATT_MTU = 517
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
