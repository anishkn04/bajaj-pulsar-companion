package dev.pulsarconnect.app.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dev.pulsarconnect.core.ble.BlePermission
import dev.pulsarconnect.core.ble.BlePermissionRequirements

/** Maps the platform-independent BLE permissions to Android manifest permissions. */
object AndroidBlePermissions {

    fun androidPermission(permission: BlePermission): String = when (permission) {
        BlePermission.SCAN -> Manifest.permission.BLUETOOTH_SCAN
        BlePermission.CONNECT -> Manifest.permission.BLUETOOTH_CONNECT
        BlePermission.LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
    }

    fun missing(context: Context, sdkInt: Int): Set<BlePermission> =
        BlePermissionRequirements.requiredForSdk(sdkInt).filter { permission ->
            context.checkSelfPermission(androidPermission(permission)) != PackageManager.PERMISSION_GRANTED
        }.toSet()

    fun androidPermissions(sdkInt: Int): Array<String> =
        BlePermissionRequirements.requiredForSdk(sdkInt)
            .map(::androidPermission)
            .distinct()
            .toTypedArray()
}
