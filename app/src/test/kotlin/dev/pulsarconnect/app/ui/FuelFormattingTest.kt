package dev.pulsarconnect.app.ui

import dev.pulsarconnect.core.model.FuelFillUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelFormattingTest {
    private fun fillUp(odometer: Double, liters: Double, fullTank: Boolean) = FuelFillUp(
        vin = "MD2ABCDEFGH123456",
        filledAtMillis = odometer.toLong(),
        odometerKm = odometer,
        liters = liters,
        fullTank = fullTank,
    )

    @Test
    fun `home card explains missing full fill-ups`() {
        assertEquals("Not enough full fill-ups yet", fuelSummaryValue(emptyList()))
        assertTrue(fuelSummaryDetail(emptyList()).contains("two full-tank"))
        assertEquals(
            "Not enough full fill-ups yet",
            fuelSummaryValue(listOf(fillUp(1_000.0, 8.0, fullTank = true))),
        )
    }

    @Test
    fun `home card shows the latest interval including partial fill-ups`() {
        val fillUps = listOf(
            fillUp(1_000.0, 8.0, fullTank = true),
            fillUp(1_050.0, 3.0, fullTank = false),
            fillUp(1_100.0, 5.0, fullTank = true),
        )

        assertEquals("8 L/100 km", fuelSummaryValue(fillUps))
        assertTrue(fuelSummaryDetail(fillUps).contains("100 km"))
        assertTrue(fuelSummaryDetail(fillUps).contains("8 L"))
    }
}
