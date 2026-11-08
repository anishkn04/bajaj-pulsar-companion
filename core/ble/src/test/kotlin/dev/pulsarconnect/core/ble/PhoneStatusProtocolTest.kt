package dev.pulsarconnect.core.ble

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `call state is carried in the low bits of byte one`() {
        val expected = mapOf(
            CallState.NO_CALL to 0,
            CallState.INCOMING_CALL to 1,
            CallState.OUTGOING_CALL to 2,
            CallState.ACTIVE_CALL to 3,
            CallState.END_CALL to 4,
        )
        for ((state, value) in expected) {
            val frame = PhoneStatusProtocol.encode(PhoneStatus(47, 2), 9, state)
            assertEquals(value, frame[1].toInt() and 0x07, state.name)
            assertEquals(value, state.wireValue)
        }
    }

    @Test
    fun `only an active call flips bytes three and four`() {
        val active = PhoneStatusProtocol.encode(PhoneStatus(47, 2), 9, CallState.ACTIVE_CALL, "Asha")
        assertEquals(1, active[3].toInt())
        assertEquals(0, active[4].toInt())

        for (state in CallState.entries.filter { it != CallState.ACTIVE_CALL }) {
            val frame = PhoneStatusProtocol.encode(PhoneStatus(47, 2), 9, state, "Asha")
            assertEquals(0, frame[3].toInt(), state.name)
            assertEquals(1, frame[4].toInt(), state.name)
        }
    }

    @Test
    fun `caller text is length prefixed at byte twenty and capped at thirty bytes`() {
        val frame = PhoneStatusProtocol.encode(
            PhoneStatus(47, 2),
            9,
            CallState.INCOMING_CALL,
            "Asha",
        )
        val text = "Asha".toByteArray(Charsets.UTF_8)
        assertEquals(text.size, frame[20].toInt() and 0xff)
        assertArrayEquals(text, frame.copyOfRange(21, 21 + text.size))
        for (offset in 21 + text.size..50) assertEquals(0, frame[offset].toInt(), "padding $offset")

        val longName = "A".repeat(80)
        val longFrame = PhoneStatusProtocol.encode(
            PhoneStatus(47, 2), 9, CallState.INCOMING_CALL, longName,
        )
        assertEquals(30, longFrame[20].toInt() and 0xff)
        assertEquals(0, longFrame[51].toInt())
    }

    @Test
    fun `caller text is omitted for non-call and outgoing states`() {
        for (state in listOf(CallState.NO_CALL, CallState.OUTGOING_CALL, CallState.END_CALL)) {
            val frame = PhoneStatusProtocol.encode(PhoneStatus(47, 2), 9, state, "Asha")
            assertEquals(0, frame[20].toInt(), state.name)
            for (offset in 21..50) assertEquals(0, frame[offset].toInt(), "${state.name} $offset")
        }
    }

    @Test
    fun `unknown and phone-like callers never put a dialable value on the wire`() {
        val fallback = CallerName.UNKNOWN_LABEL.toByteArray(Charsets.UTF_8)
        for (name in listOf(null, "", "   ", "+1 (555) 010-9999", "5550100", "12345")) {
            val frame = PhoneStatusProtocol.encode(
                PhoneStatus(47, 2), 9, CallState.INCOMING_CALL, name,
            )
            assertEquals(fallback.size, frame[20].toInt() and 0xff, "name=$name")
            assertArrayEquals(fallback, frame.copyOfRange(21, 21 + fallback.size), "name=$name")
        }
    }

    @Test
    fun `caller sanitization removes native symbol characters before encoding`() {
        val frame = PhoneStatusProtocol.encode(
            PhoneStatus(47, 2), 9, CallState.INCOMING_CALL, "Asha^+-\u260E",
        )
        val expected = "Asha".toByteArray(Charsets.UTF_8)
        assertEquals(expected.size, frame[20].toInt() and 0xff)
        assertArrayEquals(expected, frame.copyOfRange(21, 21 + expected.size))
    }

    @Test
    fun `multibyte caller text is truncated on a code point boundary`() {
        val bytes = CallerName.wireBytes("é".repeat(20))
        assertTrue(bytes.size <= CallerName.MAX_BYTES)
        assertEquals(0, bytes.size % 2, "two-byte code points must not be split")
        assertEquals("é".repeat(bytes.size / 2), String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `missed call frame is the fixed seventy four byte icon trigger`() {
        val frame = PhoneStatusProtocol.encodeMissedCall()
        assertEquals(74, frame.size)
        assertEquals(1, frame[0].toInt())
        for (offset in 1 until frame.size) assertEquals(0, frame[offset].toInt(), "offset $offset")
        assertEquals(77, PhoneStatusProtocol.MISSED_CALL_REQUIRED_MTU)
    }

    @Test
    fun `missed call capability requires the writable missed call endpoint`() {
        val service = PhoneStatusProtocol.SERVICE_UUID
        fun characteristic(uuid: UUID, canWrite: Boolean = true) =
            BleCharacteristic(uuid, canRead = true, canWrite = canWrite, canNotify = false, canIndicate = false)

        assertTrue(
            PhoneStatusProtocol.isMissedCallSupportedBy(
                listOf(BleService(service, listOf(characteristic(PhoneStatusProtocol.MISSED_CALL_UUID)))),
            ),
        )
        assertFalse(
            PhoneStatusProtocol.isMissedCallSupportedBy(
                listOf(BleService(service, listOf(characteristic(PhoneStatusProtocol.MISSED_CALL_UUID, canWrite = false)))),
            ),
        )
        assertFalse(
            PhoneStatusProtocol.isMissedCallSupportedBy(
                listOf(BleService(service, listOf(characteristic(PhoneStatusProtocol.GENERAL_UUID)))),
            ),
        )
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
