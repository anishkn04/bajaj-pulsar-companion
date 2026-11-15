package dev.pulsarconnect.core.data

import dev.pulsarconnect.core.model.BikeProfile
import dev.pulsarconnect.core.model.FuelFillUp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FuelDatabaseTest {
    private val vin = "MD2ABCDEFGH123456"
    private val otherVin = "MD2ABCDEFGH654321"
    private lateinit var db: Database
    private lateinit var tempFile: java.io.File
    private lateinit var fuels: SqliteFuelRepository

    @BeforeEach
    fun setUp() = runBlocking {
        tempFile = java.io.File.createTempFile("fuel_", ".db")
        db = Database.create(tempFile.absolutePath)
        db.upsertBike(BikeProfile(vin, null, null, null, null))
        db.upsertBike(BikeProfile(otherVin, null, null, null, null))
        fuels = SqliteFuelRepository(db)
    }

    @AfterEach
    fun tearDown() {
        db.close()
        tempFile.delete()
    }

    private fun fillUp(odometer: Double, vin: String = this.vin, liters: Double = 8.0) = FuelFillUp(
        vin = vin, filledAtMillis = odometer.toLong(), odometerKm = odometer,
        liters = liters, fullTank = true,
    )

    private suspend fun failure(block: suspend () -> Unit): IllegalArgumentException {
        try {
            block()
        } catch (error: IllegalArgumentException) {
            return error
        }
        throw AssertionError("Expected IllegalArgumentException")
    }

    @Test
    fun `fill-ups are scoped ordered and monotonic`() = runTest {
        val first = fuels.addFillUp(fillUp(1_000.0))
        val second = fuels.addFillUp(fillUp(1_200.0, liters = 6.5))
        fuels.addFillUp(fillUp(500.0, vin = otherVin))

        assertEquals(listOf(second.id, first.id), fuels.getFillUps(vin).map { it.id })
        assertEquals(second, fuels.getFillUp(second.id!!))
        assertEquals(6.5, second.liters, 0.0)

        failure { fuels.addFillUp(fillUp(1_200.0)) }
        failure { fuels.addFillUp(fillUp(900.0)) }
        failure { fuels.addFillUp(fillUp(1_300.0, liters = 0.0)) }
        assertEquals(2, fuels.getFillUps(vin).size)
    }

    @Test
    fun `delete and restore preserve ids`() = runTest {
        val saved = fuels.addFillUp(fillUp(1_000.0))
        fuels.deleteFillUp(saved.id!!)
        assertNull(fuels.getFillUp(saved.id!!))
        failure { fuels.deleteFillUp(saved.id!!) }

        fuels.restoreFillUp(saved)
        assertEquals(saved, fuels.getFillUp(saved.id!!))
        failure { fuels.restoreFillUp(saved) }
    }

    @Test
    fun `fill-ups survive reopening the database file`() = runBlocking {
        val saved = fuels.addFillUp(fillUp(1_500.0))
        db.close()

        db = Database.create(tempFile.absolutePath)
        fuels = SqliteFuelRepository(db)

        assertEquals(listOf(saved), fuels.getFillUps(vin))
    }
}
