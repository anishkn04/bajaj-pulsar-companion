package dev.pulsarconnect.core.ble

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PhoneStatusProtocolTest {
    @Test
    fun `protocol identifies only the recovered GENERAL endpoint`() {
        assertEquals(UUID.fromString("0010676e-6972-6565-6e69-676e4543544f"), PhoneStatusProtocol.SERVICE_UUID)
        assertEquals(UUID.fromString("0210676e-6972-6565-6e69-676e4543544f"), PhoneStatusProtocol.GENERAL_UUID)
        assertEquals(55, PhoneStatusProtocol.FRAME_SIZE)
    }

    @Test
    fun `synthetic idle frame matches all 55 bytes without caller text or Android checksum`() {
        // Invented inputs and expected bytes, not a capture fixture.
        val expected = """
            c5 10 02 00 01 00 00 00 00 00
            00 00 00 00 00 00 00 00 00 00
            00 00 00 00 00 00 00 00 00 00
            00 00 00 00 00 00 00 00 00 00
            00 00 00 00 00 00 00 00 00 00
            00 00 00 a6 00
        """.trimIndent().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

        val actual = PhoneStatusProtocol.encodeIdle(PhoneStatus(47, 2), heartbeat = 166)

        assertEquals(55, expected.size)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `battery uses inclusive lower bound ladder including full battery`() {
        val boundaries = listOf(
            0 to 0, 19 to 0, 20 to 1, 39 to 1, 40 to 2, 59 to 2,
            60 to 3, 79 to 3, 80 to 4, 99 to 4, 100 to 4,
        )
        for ((percent, level) in boundaries) {
            val frame = PhoneStatusProtocol.encodeIdle(PhoneStatus(percent, 0), 0)
            assertEquals(level shl 3, frame[1].toInt(), "battery $percent")
            assertIdlePadding(frame)
        }
    }

    @Test
    fun `unknown readings are indistinguishable from level zero on wire`() {
        val unknown = PhoneStatusProtocol.encodeIdle(PhoneStatus(null, null), 17)
        val zero = PhoneStatusProtocol.encodeIdle(PhoneStatus(0, 0), 17)
        assertArrayEquals(zero, unknown)
        assertEquals(0, unknown[1].toInt())
        assertEquals(0, unknown[2].toInt())
        assertIdlePadding(unknown)
    }

    @Test
    fun `every signal level is emitted independently from battery`() {
        for (signal in 0..4) {
            val frame = PhoneStatusProtocol.encodeIdle(PhoneStatus(100, signal), 0)
            assertEquals(32, frame[1].toInt())
            assertEquals(signal, frame[2].toInt())
            assertIdlePadding(frame)
        }
    }

    @Test
    fun `invalid known battery or signal readings are rejected rather than clamped`() {
        for (battery in listOf(Int.MIN_VALUE, -1, 101, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                PhoneStatusProtocol.encodeIdle(PhoneStatus(battery, null), 0)
            }
        }
        for (signal in listOf(Int.MIN_VALUE, -1, 5, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                PhoneStatusProtocol.encodeIdle(PhoneStatus(null, signal), 0)
            }
        }
    }

    @Test
    fun `caller supplies wrapped heartbeat without implicit increment or retained state`() {
        for (heartbeat in listOf(1, 127, 128, 254, 255, 0, 1)) {
            val frame = PhoneStatusProtocol.encodeIdle(PhoneStatus(null, null), heartbeat)
            assertEquals(heartbeat, frame[53].toInt() and 0xff)
            assertIdlePadding(frame)
        }
    }

    @Test
    fun `out of range heartbeat is rejected rather than silently wrapped`() {
        for (heartbeat in listOf(Int.MIN_VALUE, -1, 256, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                PhoneStatusProtocol.encodeIdle(PhoneStatus(null, null), heartbeat)
            }
        }
    }

    @Test
    fun `each encoding owns its zero initialized bytes`() {
        val status = PhoneStatus(47, 2)
        assertEquals(status, status.copy())
        val first = PhoneStatusProtocol.encodeIdle(status, 9)
        val second = PhoneStatusProtocol.encodeIdle(status, 9)
        assertNotSame(first, second)
        first.fill(0x7f)
        assertIdlePadding(second)
        assertArrayEquals(second, PhoneStatusProtocol.encodeIdle(status, 9))
    }

    private fun assertIdlePadding(frame: ByteArray) {
        assertEquals(55, frame.size)
        assertEquals(0xc5, frame[0].toInt() and 0xff)
        assertEquals(0, frame[3].toInt())
        assertEquals(1, frame[4].toInt())
        for (offset in 5..52) assertEquals(0, frame[offset].toInt(), "offset $offset")
        assertEquals(0, frame[54].toInt(), "Android does not append a checksum")
    }
}
