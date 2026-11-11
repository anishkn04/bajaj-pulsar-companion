package dev.pulsarconnect.core.ble

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Whether the platform Bluetooth adapter is available to this app. */
enum class AdapterState {
    UNKNOWN,
    OFF,
    ON,
}

/**
 * Runtime permissions this app needs before it may scan or connect.
 * The Android layer maps these to concrete permission strings.
 */
enum class BlePermission {
    SCAN,
    CONNECT,

    /** Legacy (API 26-30) runtime permission required for BLE scanning. */
    LOCATION,
}

/** A device observed during a scan. [id] is the platform address and must not be logged. */
data class DiscoveredDevice(
    val id: String,
    val name: String?,
    val rssi: Int,
    val bonded: Boolean,
)

/** A user-selected bike, scoped to a VIN so multiple bikes cannot reconnect to each other. */
data class BikeTarget(
    val id: String,
    val name: String?,
    val vin: String,
)

data class BleCharacteristic(
    val uuid: UUID,
    val canRead: Boolean,
    val canWrite: Boolean,
    val canNotify: Boolean,
    val canIndicate: Boolean,
)

data class BleService(
    val uuid: UUID,
    val characteristics: List<BleCharacteristic>,
)

sealed interface BleSessionEvent {
    /** Raw notification/indication bytes. Interpretation is deliberately out of scope for now. */
    data class Changed(
        val service: UUID,
        val characteristic: UUID,
        val value: ByteArray,
    ) : BleSessionEvent

    data class Disconnected(val status: Int) : BleSessionEvent
}

sealed interface BleFailure {
    data object AdapterOff : BleFailure
    data class PermissionDenied(val missing: Set<BlePermission>) : BleFailure
    data class ConnectTimeout(val millis: Long) : BleFailure
    data class DiscoveryTimeout(val millis: Long) : BleFailure
    data class DiscoveryFailed(val reason: String) : BleFailure
    data object NotResponding : BleFailure
    data class GattError(val operation: String, val status: Int) : BleFailure
    data class MtuTooSmall(val required: Int, val negotiated: Int) : BleFailure
    data class Disconnected(val status: Int) : BleFailure
    data class TooManyAttempts(val attempts: Int) : BleFailure
    data class Unexpected(val reason: String) : BleFailure
}

class BleException(
    val failure: BleFailure,
    cause: Throwable? = null,
) : Exception(failure.toString(), cause)

/** A connected GATT session. The caller owns it and must [close] it. */
interface BleSession {
    val events: Flow<BleSessionEvent>

    suspend fun services(): List<BleService>

    suspend fun setNotifications(service: UUID, characteristic: UUID, enabled: Boolean)

    suspend fun read(service: UUID, characteristic: UUID): ByteArray

    /**
     * Requests at least [mtu] bytes of ATT MTU and returns the negotiated value.
     * Implementations may cache a previously negotiated value. A value below the
     * payload requirement must fail the write rather than truncate it.
     */
    suspend fun requestMtu(mtu: Int): Int

    suspend fun write(service: UUID, characteristic: UUID, bytes: ByteArray, withResponse: Boolean)

    /** Idempotent. Must release the platform GATT client. */
    fun close()
}

/**
 * Platform BLE port. Implementations own scanning, GATT callbacks and permission checks;
 * [BikeConnection] owns retries and user-visible state.
 */
interface BleTransport {
    val adapterState: StateFlow<AdapterState>

    fun missingPermissions(): Set<BlePermission>

    /** Re-reads the platform adapter state, e.g. after permission is granted. */
    fun refreshAdapterState() = Unit

    /**
     * Opens a GATT client to [target].
     *
     * [passive] asks the platform to hold a background connection and attach when the bike
     * next advertises, instead of making one immediate connection attempt. It costs no radio
     * scanning while the bike is off, but may take longer to complete.
     */
    suspend fun connect(target: BikeTarget, passive: Boolean = false): BleSession
}

/** Raw scan events; the Android adapter implements this with BluetoothLeScanner. */
fun interface BleScanSource {
    fun scan(): Flow<DiscoveredDevice>
}

interface BleScanner {
    /**
     * Cold flow of deduplicated nearby devices, newest RSSI each time.
     * Scanning stops when collection stops or [timeoutMillis] elapses.
     */
    fun scan(timeoutMillis: Long = DEFAULT_SCAN_TIMEOUT_MILLIS): Flow<List<DiscoveredDevice>>

    companion object {
        const val DEFAULT_SCAN_TIMEOUT_MILLIS: Long = 10_000
    }
}

/** Per-VIN BLE selection. Implementations must keep the address app-private. */
interface BikeDeviceStore {
    fun selected(vin: String): BikeTarget?

    fun select(target: BikeTarget)

    fun clear(vin: String)

    fun autoConnect(vin: String): Boolean

    fun setAutoConnect(vin: String, enabled: Boolean)
}

/**
 * Android runtime permission requirements by SDK level.
 * API 31+ uses the nearby-devices permissions; API 26-30 requires location for scanning.
 */
object BlePermissionRequirements {
    fun requiredForSdk(sdkInt: Int): Set<BlePermission> =
        if (sdkInt >= 31) setOf(BlePermission.SCAN, BlePermission.CONNECT)
        else setOf(BlePermission.LOCATION)
}
