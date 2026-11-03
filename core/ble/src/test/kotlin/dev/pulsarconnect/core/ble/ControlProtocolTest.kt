package dev.pulsarconnect.core.ble

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlProtocolTest {

    private fun frame(vararg pairs: Pair<Int, Int>): ByteArray {
        val bytes = ByteArray(ControlProtocol.RESPONSE_SIZE)
        pairs.forEach { (index, value) -> bytes[index] = value.toByte() }
        return bytes
    }

    private fun controlService(readable: Boolean = true): BleService = BleService(
        ControlProtocol.SERVICE_UUID,
        listOf(
            BleCharacteristic(
                ControlProtocol.CONTROL_UUID,
                canRead = readable,
                canWrite = false,
                canNotify = false,
                canIndicate = false,
            ),
        ),
    )

    @Test
    fun `supports only a peer exposing the readable control characteristic`() {
        assertTrue(ControlProtocol.isSupportedBy(listOf(controlService())))
        assertFalse(ControlProtocol.isSupportedBy(listOf(controlService(readable = false))))
        assertFalse(ControlProtocol.isSupportedBy(emptyList()))
    }

    @Test
    fun `an all zero response parses as an idle control frame`() {
        val frame = ControlProtocol.parse(frame())

        assertEquals(
            ClusterControlFrame(
                volume = 0,
                callAccept = 0,
                callReject = 0,
                callRejectWithSms = 0,
                playlistPage = 0,
                takeMeHome = 0,
                resume = 0,
                pause = 0,
                next = 0,
                previous = 0,
                stop = 0,
                missedCallRead = 0,
                alertRead = 0,
                launchMediaPlayer = 0,
            ),
            frame,
        )
    }

    @Test
    fun `every field maps to its recovered byte`() {
        val parsed = ControlProtocol.parse(
            frame(
                0 to 0xff,
                1 to 1,
                2 to 2,
                3 to 3,
                4 to 0xff,
                5 to 5,
                6 to 6,
                7 to 7,
                8 to 8,
                9 to 9,
                10 to 10,
                11 to 11,
                12 to 12,
                13 to 13,
            ),
        )!!

        assertEquals(0x0f, parsed.volume)
        assertEquals(1, parsed.callAccept)
        assertEquals(2, parsed.callReject)
        assertEquals(3, parsed.callRejectWithSms)
        assertEquals(0x3f, parsed.playlistPage)
        assertEquals(5, parsed.takeMeHome)
        assertEquals(6, parsed.resume)
        assertEquals(7, parsed.pause)
        assertEquals(8, parsed.next)
        assertEquals(9, parsed.previous)
        assertEquals(10, parsed.stop)
        assertEquals(11, parsed.missedCallRead)
        assertEquals(12, parsed.alertRead)
        assertEquals(13, parsed.launchMediaPlayer)
    }

    @Test
    fun `twenty bytes are enough but nineteen are rejected`() {
        assertNull(ControlProtocol.parse(ByteArray(ControlProtocol.MIN_FRAME_SIZE - 1)))
        assertEquals(
            ClusterControlFrame(),
            ControlProtocol.parse(ByteArray(ControlProtocol.MIN_FRAME_SIZE)),
        )
    }

    @Test
    fun `unsigned values are not sign extended`() {
        val parsed = ControlProtocol.parse(frame(0 to 0x80, 1 to 0xff, 12 to 0x80))!!

        assertEquals(0, parsed.volume)
        assertEquals(255, parsed.callAccept)
        assertEquals(128, parsed.alertRead)
    }

    @Test
    fun `characteristic uuid belongs to the bike's custom service`() {
        assertEquals(
            UUID.fromString("0010676e-6972-6565-6e69-676e4543544f"),
            ControlProtocol.SERVICE_UUID,
        )
        assertEquals(
            UUID.fromString("0a10676e-6972-6565-6e69-676e4543544f"),
            ControlProtocol.CONTROL_UUID,
        )
    }

    @Test
    fun `poll cadence matches the official app`() {
        assertEquals(700L, ControlProtocol.POLL_INTERVAL_MILLIS)
    }
}
