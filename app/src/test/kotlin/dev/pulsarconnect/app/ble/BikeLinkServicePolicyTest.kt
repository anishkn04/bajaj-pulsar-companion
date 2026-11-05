package dev.pulsarconnect.app.ble

import dev.pulsarconnect.core.ble.BikeLinkSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeLinkServicePolicyTest {

    private val vin = "MD2ABCDEFGH123456"

    @Test
    fun `service runs only while background mirroring is armed to a selected bike`() {
        assertTrue(
            shouldRunBikeLinkService(
                BikeLinkSettings(
                    mirroring = true,
                    keepInBackground = true,
                    startOnBoot = true,
                    armedVin = vin,
                ),
                hasSelectedBike = true,
            ),
        )
        assertFalse(
            shouldRunBikeLinkService(
                BikeLinkSettings(mirroring = false, keepInBackground = true, armedVin = vin),
                hasSelectedBike = true,
            ),
        )
        assertFalse(
            shouldRunBikeLinkService(
                BikeLinkSettings(mirroring = true, keepInBackground = false, armedVin = vin),
                hasSelectedBike = true,
            ),
        )
        assertFalse(
            shouldRunBikeLinkService(
                BikeLinkSettings(mirroring = true, keepInBackground = true, armedVin = null),
                hasSelectedBike = true,
            ),
        )
        assertFalse(
            shouldRunBikeLinkService(
                BikeLinkSettings(mirroring = true, keepInBackground = true, armedVin = vin),
                hasSelectedBike = false,
            ),
        )
    }

    @Test
    fun `a sticky restart with no intent resumes the service`() {
        assertEquals(BikeLinkServiceCommand.RESUME, bikeLinkServiceCommand(null))
        assertEquals(BikeLinkServiceCommand.RESUME, bikeLinkServiceCommand(BikeLinkService.ACTION_START))
        assertEquals(BikeLinkServiceCommand.RESUME, bikeLinkServiceCommand("unexpected"))
    }

    @Test
    fun `the stop action disarms the service`() {
        assertEquals(BikeLinkServiceCommand.STOP, bikeLinkServiceCommand(BikeLinkService.ACTION_STOP))
    }
}
