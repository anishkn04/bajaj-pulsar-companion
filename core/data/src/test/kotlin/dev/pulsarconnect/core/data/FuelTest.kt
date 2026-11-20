package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.FuelFillUp
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FuelTest {
    private val vin = "DEV-BIKE-A"

    private fun fillUp(
        odometerKm: Double,
        liters: Double,
        fullTank: Boolean,
        atMillis: Long = odometerKm.toLong(),
        vin: String = this.vin,
    ) = FuelFillUp(
        vin = vin, filledAtMillis = atMillis, odometerKm = odometerKm,
        liters = liters, fullTank = fullTank,
    )

    @Test
    fun `full to full intervals accumulate partial fill-ups`() {
        val economy = fuelEconomy(
            listOf(
                fillUp(1_000.0, 10.0, fullTank = true),
                fillUp(1_050.0, 4.0, fullTank = false),
                fillUp(1_100.0, 6.0, fullTank = true),
            ),
        )

        val interval = economy.latest!!
        assertEquals(100.0, interval.distanceKm, 0.0)
        assertEquals(10.0, interval.liters, 0.0)
        assertEquals(10.0, interval.lPer100Km, 1e-9)
        assertEquals(10.0, interval.kmPerLitre, 1e-9)
    }

    @Test
    fun `partial fill before the first full tank is not counted`() {
        val economy = fuelEconomy(
            listOf(
                fillUp(900.0, 3.0, fullTank = false),
                fillUp(1_000.0, 10.0, fullTank = true),
                fillUp(1_100.0, 5.0, fullTank = true),
            ),
        )

        assertEquals(5.0, economy.latest!!.liters, 0.0)
        assertEquals(1, economy.intervals.size)
    }

    @Test
    fun `latest average and totals cover every closed interval`() {
        val economy = fuelEconomy(
            listOf(
                fillUp(1_100.0, 4.0, fullTank = true),
                fillUp(1_000.0, 8.0, fullTank = true),
                fillUp(1_200.0, 6.0, fullTank = true),
            ),
        )

        assertEquals(2, economy.intervals.size)
        assertEquals(100.0, economy.latest!!.distanceKm, 0.0)
        assertEquals(200.0, economy.totalDistanceKm, 0.0)
        assertEquals(10.0, economy.totalLiters, 0.0)
        assertEquals(5.0, economy.averageLPer100Km!!, 1e-9)
        assertEquals(20.0, economy.averageKmPerLitre!!, 1e-9)
    }

    @Test
    fun `one full fill-up or only partials has no economy yet`() {
        assertNull(fuelEconomy(emptyList()).latest)
        assertNull(fuelEconomy(listOf(fillUp(1_000.0, 10.0, fullTank = true))).latest)
        assertNull(
            fuelEconomy(
                listOf(
                    fillUp(1_000.0, 10.0, fullTank = false),
                    fillUp(1_100.0, 5.0, fullTank = false),
                ),
            ).latest,
        )
        assertNull(fuelEconomy(emptyList()).averageLPer100Km)
    }

    @Test
    fun `invalid litre and odometer values are rejected`() {
        for (invalid in listOf(
            fillUp(1_000.0, 0.0, fullTank = true),
            fillUp(1_000.0, -1.0, fullTank = true),
            fillUp(-1.0, 5.0, fullTank = true),
            fillUp(Double.NaN, 5.0, fullTank = true),
        )) {
            assertThrows(IllegalArgumentException::class.java) { requireValidFillUp(invalid) }
            assertThrows(IllegalArgumentException::class.java) { fuelEconomy(listOf(invalid)) }
        }
    }

    private suspend fun failure(block: suspend () -> Unit): IllegalArgumentException {
        try {
            block()
        } catch (error: IllegalArgumentException) {
            return error
        }
        throw AssertionError("Expected IllegalArgumentException")
    }

    @Test
    fun `repository rejects non-increasing odometers per bike`() = runTest {
        val fuels = InMemoryFuelRepository()
        fuels.addFillUp(fillUp(1_000.0, 10.0, fullTank = true))
        fuels.addFillUp(fillUp(500.0, 5.0, fullTank = true, vin = "DEV-BIKE-B"))

        assertTrue(failure { fuels.addFillUp(fillUp(1_000.0, 4.0, fullTank = true)) }
            .message!!.contains("higher"))
        assertTrue(failure { fuels.addFillUp(fillUp(900.0, 4.0, fullTank = true)) }
            .message!!.contains("higher"))
        assertEquals(1, fuels.getFillUps(vin).size)
    }

    @Test
    fun `repository orders newest first and supports delete and restore`() = runTest {
        val fuels = InMemoryFuelRepository()
        val first = fuels.addFillUp(fillUp(1_000.0, 10.0, fullTank = true))
        val second = fuels.addFillUp(fillUp(1_200.0, 5.0, fullTank = false))

        assertEquals(listOf(second.id, first.id), fuels.getFillUps(vin).map { it.id })
        assertEquals(first, fuels.getFillUp(first.id!!))

        fuels.deleteFillUp(first.id!!)
        assertNull(fuels.getFillUp(first.id!!))
        failure { fuels.deleteFillUp(first.id!!) }

        fuels.restoreFillUp(first)
        assertEquals(first, fuels.getFillUp(first.id!!))
        assertTrue(fuels.getFillUps(vin).contains(first))
    }

    @Test
    fun `latest fill-up odometer ignores order`() {
        assertEquals(
            1_250.0,
            latestFillUpOdometerKm(
                listOf(
                    fillUp(1_250.0, 5.0, fullTank = true),
                    fillUp(1_000.0, 5.0, fullTank = true),
                ),
            )!!,
            0.0,
        )
        assertNull(latestFillUpOdometerKm(emptyList()))
    }
}
