package dev.pulsarconnect.core.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallerNameTest {
    @Test
    fun `phone-shaped values are detected without letters`() {
        for (value in listOf("+1 (555) 010-9999", "555-0100", "5550100", "12345", " +44 20 7946 0958 ")) {
            assertTrue(CallerName.isPhoneLike(value), value)
        }
        for (value in listOf("Asha", "Taxi 555", "Incoming call", "", "A1")) {
            assertFalse(CallerName.isPhoneLike(value), value)
        }
    }

    @Test
    fun `unknown callers collapse to the generic label`() {
        assertEquals(CallerName.UNKNOWN_LABEL, CallerName.wireText(null))
        assertEquals(CallerName.UNKNOWN_LABEL, CallerName.wireText("   "))
        assertEquals(CallerName.UNKNOWN_LABEL, CallerName.wireText("555-0100"))
        assertEquals("Asha", CallerName.wireText("Asha"))
    }

    @Test
    fun `sanitization keeps ordinary punctuation and removes native unwanted characters`() {
        assertEquals("Asha", CallerName.sanitize(" Asha^-+ "))
        assertEquals("A.B", CallerName.sanitize("A.B"))
        assertEquals("", CallerName.sanitize(null))
    }

    @Test
    fun `truncation never exceeds the byte cap or splits a code point`() {
        assertEquals(30, CallerName.truncate("A".repeat(80)).size)
        val multibyte = CallerName.truncate("é".repeat(40))
        assertEquals(30, multibyte.size)
        assertEquals(15, String(multibyte, Charsets.UTF_8).length)
        val emoji = CallerName.truncate("😀".repeat(20))
        assertEquals(28, emoji.size)
        assertEquals(7, String(emoji, Charsets.UTF_8).codePointCount(0, String(emoji, Charsets.UTF_8).length))
    }
}
