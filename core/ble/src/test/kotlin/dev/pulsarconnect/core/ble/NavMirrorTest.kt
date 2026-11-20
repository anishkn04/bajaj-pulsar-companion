package dev.pulsarconnect.core.ble

import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavMirrorTest {

    private val cue = NavCue(
        maneuver = NavManeuver.TURN_RIGHT,
        distanceMeters = 250,
        totalDistanceMeters = 3_220,
        etaHour = 4,
        etaMinute = 57,
        etaPm = false,
        roundaboutExit = 0,
        text = "MG Road",
    )

    @Test
    fun `NavText preserves digits and normalizes arrows within 30 bytes`() {
        val wire = NavText.wireText("250m → MG Road International Boulevard")
        assertTrue(wire.toByteArray(Charsets.UTF_8).size <= 30)
        assertTrue(wire.startsWith("250m > MG Road"))
    }

    @Test
    fun `TBT frame carries maneuver distance ETA text and checksum`() {
        val frame = TbtProtocol.encode(cue)

        assertEquals(TbtProtocol.FRAME_SIZE, frame.size)
        assertEquals((1 or (1 shl 4)).toByte(), frame[0]) // update + metre step unit
        assertEquals(NavManeuver.TURN_RIGHT.iconId.toByte(), frame[1])
        assertArrayEquals(byteArrayOf(0, 0, 250.toByte(), 0), frame.copyOfRange(2, 6))
        assertEquals(57.toByte(), frame[6])
        assertEquals(4.toByte(), frame[7])
        assertArrayEquals(byteArrayOf(22, 0, 3, 0), frame.copyOfRange(8, 12)) // 3.22 km
        assertEquals(0x04.toByte(), frame[12]) // km total + GPS active
        assertEquals("MG Road".length.toByte(), frame[14])
        assertEquals("MG Road", frame.copyOfRange(15, 22).toString(Charsets.US_ASCII))
        assertEquals(
            (0 until 47).sumOf { frame[it].toInt() and 0xFF }.toByte(),
            frame[47],
        )
    }

    @Test
    fun `TBT frame blinks the maneuver icon under 100 meters`() {
        val frame = TbtProtocol.encode(cue.copy(distanceMeters = 80))
        assertEquals((NavManeuver.TURN_RIGHT.iconId + 32).toByte(), frame[1])
    }

    @Test
    fun `TBT stop frame clears type but keeps link flags`() {
        val frame = TbtProtocol.stopFrame()

        assertEquals((1 shl 4).toByte(), frame[0])
        assertEquals(0.toByte(), frame[1])
        assertEquals(0x05.toByte(), frame[12]) // zero distance still carries the metre unit + GPS
        assertEquals((0x10 + 0x05).toByte(), frame[47])
    }

    @Test
    fun `transport resolver prefers TBT then ALERT then call text`() {
        val chars = listOf(
            characteristic(TbtProtocol.TBT_UUID),
            characteristic(AlertProtocol.ALERT_UUID),
            characteristic(PhoneStatusProtocol.GENERAL_UUID),
        )
        val service = BleService(PhoneStatusProtocol.SERVICE_UUID, chars)

        assertEquals(NavTransport.TBT, NavTransportResolver.select(listOf(service)))
        assertEquals(
            NavTransport.ALERT,
            NavTransportResolver.select(
                listOf(service.copy(characteristics = chars.drop(1))),
            ),
        )
        assertEquals(
            NavTransport.CALL_TEXT,
            NavTransportResolver.select(
                listOf(service.copy(characteristics = chars.drop(2))),
            ),
        )
        assertEquals(
            null,
            NavTransportResolver.select(emptyList()),
        )
    }

    @Test
    fun `navigation uses TBT frame and never sets ACTIVE_CALL`() = runTest {
        val fake = FakeBleTransport()
        fake.services = listOf(
            BleService(
                PhoneStatusProtocol.SERVICE_UUID,
                listOf(
                    characteristic(TbtProtocol.TBT_UUID),
                    characteristic(PhoneStatusProtocol.GENERAL_UUID),
                ),
            ),
        )
        val connection = connect(fake)
        val navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 16)
        val callEvents = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
        val sender = PhoneStatusSender(
            connection = connection,
            statuses = MutableStateFlow(PhoneStatus(85, 3)),
            scope = backgroundScope,
            callEvents = callEvents,
            callOptions = { CallMirrorOptions(callerName = true) },
            navEvents = navEvents,
            navOptions = { NavMirrorOptions(enabled = true) },
            intervalMillis = 1_000,
        )
        try {
            assertTrue(sender.start())
            runCurrent()
            navEvents.emit(NavEvent.Update(cue))
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            val navWrite = fake.latestSession.writes.last()
            assertEquals(TbtProtocol.TBT_UUID, navWrite.characteristic)
            assertArrayEquals(TbtProtocol.encode(cue), navWrite.bytes)
            assertFalse(fake.latestSession.writes.any { write ->
                write.characteristic == PhoneStatusProtocol.GENERAL_UUID &&
                    (write.bytes[1].toInt() and 0x07) == CallState.ACTIVE_CALL.wireValue
            })

            callEvents.emit(CallEvent.Incoming("Alice"))
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            val callWrite = fake.latestSession.writes.last()
            assertEquals(PhoneStatusProtocol.GENERAL_UUID, callWrite.characteristic)
            assertEquals(CallState.INCOMING_CALL.wireValue, callWrite.bytes[1].toInt() and 0x07)
            assertEquals("Alice", decodeCallerSlot(callWrite.bytes))

            callEvents.emit(CallEvent.Ended)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(TbtProtocol.TBT_UUID, fake.latestSession.writes.last().characteristic)
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `ALERT fallback writes text frame without touching call state`() = runTest {
        val fake = FakeBleTransport()
        fake.services = listOf(
            BleService(
                PhoneStatusProtocol.SERVICE_UUID,
                listOf(
                    characteristic(AlertProtocol.ALERT_UUID),
                    characteristic(PhoneStatusProtocol.GENERAL_UUID),
                ),
            ),
        )
        val connection = connect(fake)
        val navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 16)
        val sender = PhoneStatusSender(
            connection = connection,
            statuses = MutableStateFlow(PhoneStatus(85, 3)),
            scope = backgroundScope,
            navEvents = navEvents,
            navOptions = { NavMirrorOptions(enabled = true) },
            intervalMillis = 1_000,
        )
        try {
            assertTrue(sender.start())
            runCurrent()
            navEvents.emit(NavEvent.Update(cue))
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            val write = fake.latestSession.writes.last()
            assertEquals(AlertProtocol.ALERT_UUID, write.characteristic)
            assertEquals(AlertProtocol.FRAME_SIZE, write.bytes.size)
            assertEquals(1.toByte(), write.bytes[0])
            assertTrue(write.bytes[1].toInt() > 0)
            assertTrue(write.bytes.any { it != 0.toByte() })
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `call text fallback uses incoming call slot without active call flag`() = runTest {
        val fake = FakeBleTransport()
        fake.services = listOf(
            BleService(
                PhoneStatusProtocol.SERVICE_UUID,
                listOf(characteristic(PhoneStatusProtocol.GENERAL_UUID)),
            ),
        )
        val connection = connect(fake)
        val navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 16)
        val sender = PhoneStatusSender(
            connection = connection,
            statuses = MutableStateFlow(PhoneStatus(85, 3)),
            scope = backgroundScope,
            navEvents = navEvents,
            navOptions = { NavMirrorOptions(enabled = true) },
            intervalMillis = 1_000,
        )
        try {
            assertTrue(sender.start())
            runCurrent()
            navEvents.emit(NavEvent.Update(cue))
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            val frame = fake.latestSession.writes.last().bytes
            assertEquals(CallState.INCOMING_CALL.wireValue, frame[1].toInt() and 0x07)
            assertEquals(0.toByte(), frame[3])
            assertEquals(1.toByte(), frame[4])
            assertEquals("> 250m MG Road", decodeCallerSlot(frame))
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    private suspend fun TestScope.connect(fake: FakeBleTransport): BikeConnection {
        val connection = BikeConnection(fake, backgroundScope, jitter = { it })
        connection.connect(testTarget(), autoReconnect = false)
        runCurrent()
        assertNotNull(connection.state.value as? ConnectionState.Connected)
        return connection
    }

    private fun characteristic(uuid: UUID) = BleCharacteristic(
        uuid = uuid,
        canRead = false,
        canWrite = true,
        canNotify = false,
        canIndicate = false,
    )

    private fun decodeCallerSlot(frame: ByteArray): String {
        val length = frame[PhoneStatusProtocol.CALLER_LENGTH_OFFSET].toInt() and 0xFF
        return frame.copyOfRange(
            PhoneStatusProtocol.CALLER_TEXT_OFFSET,
            PhoneStatusProtocol.CALLER_TEXT_OFFSET + length,
        ).toString(Charsets.UTF_8)
    }
}
