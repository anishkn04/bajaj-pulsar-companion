package dev.pulsarconnect.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class VinTest {

    @Test
    fun `normalises whitespace and case`() {
        assertEquals("MD2ABCDEFGH123456", Vin.of(" md2abcdefgh123456 ").value)
    }

    @Test
    fun `rejects values that are not 17 characters`() {
        assertThrows(IllegalArgumentException::class.java) { Vin.of("MD2ABCDEFGH12345") }
    }

    @Test
    fun `rejects characters that cannot appear in a VIN`() {
        assertThrows(IllegalArgumentException::class.java) { Vin.of("MD2ABCDEFGH12345I") }
    }
}
