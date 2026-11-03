package dev.pulsarconnect.core.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BlePermissionRequirementsTest {

    @Test
    fun `api 26 and 30 require location for scanning`() {
        assertEquals(setOf(BlePermission.LOCATION), BlePermissionRequirements.requiredForSdk(26))
        assertEquals(setOf(BlePermission.LOCATION), BlePermissionRequirements.requiredForSdk(30))
    }

    @Test
    fun `api 31 and newer require nearby device permissions`() {
        assertEquals(
            setOf(BlePermission.SCAN, BlePermission.CONNECT),
            BlePermissionRequirements.requiredForSdk(31),
        )
        assertEquals(
            setOf(BlePermission.SCAN, BlePermission.CONNECT),
            BlePermissionRequirements.requiredForSdk(35),
        )
    }
}
