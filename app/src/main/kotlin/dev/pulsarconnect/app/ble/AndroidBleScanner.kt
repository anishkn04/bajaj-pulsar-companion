package dev.pulsarconnect.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import dev.pulsarconnect.core.ble.BleException
import dev.pulsarconnect.core.ble.BleFailure
import dev.pulsarconnect.core.ble.BleScanSource
import dev.pulsarconnect.core.ble.DiscoveredDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Raw Android scan source. Filtering, deduplication and timeouts live in [dev.pulsarconnect.core.ble.BikeScanner].
 */
class AndroidBleScanner(
    private val context: Context,
) : BleScanSource {

    private val manager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    @SuppressLint("MissingPermission")
    override fun scan(): Flow<DiscoveredDevice> = callbackFlow {
        val adapter = manager?.adapter
        if (adapter == null) {
            close(BleException(BleFailure.Unexpected("Bluetooth is not supported on this device")))
            return@callbackFlow
        }
        val missing = AndroidBlePermissions.missing(context, android.os.Build.VERSION.SDK_INT)
        if (missing.isNotEmpty()) {
            close(BleException(BleFailure.PermissionDenied(missing)))
            return@callbackFlow
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            close(BleException(BleFailure.AdapterOff))
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.toDiscovered())
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(it.toDiscovered()) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleException(BleFailure.Unexpected("Bluetooth scan failed ($errorCode)")))
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()

        try {
            scanner.startScan(null, settings, callback)
        } catch (error: SecurityException) {
            close(BleException(BleFailure.PermissionDenied(AndroidBlePermissions.missing(context, android.os.Build.VERSION.SDK_INT))))
            return@callbackFlow
        }

        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                // Permission was revoked while scanning; the OS has already stopped it.
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toDiscovered(): DiscoveredDevice {
        val name = try {
            device.name
        } catch (_: SecurityException) {
            null
        }
        val bonded = try {
            device.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            false
        }
        return DiscoveredDevice(
            id = device.address,
            name = name,
            rssi = rssi,
            bonded = bonded,
        )
    }
}
