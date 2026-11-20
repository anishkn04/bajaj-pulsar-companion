package dev.pulsarconnect.core.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavMirrorTest {

    @Test
    fun `NavText preserves digits and normalizes arrows within 30 bytes`() {
        val raw = "250m → MG Road International Boulevard"
        val wire = NavText.wireText(raw)
        assertTrue(wire.toByteArray(Charsets.UTF_8).size <= 30)
        assertTrue(wire.startsWith("250m > MG Road"))
    }

    @Test
    fun `encodeNavigation uses requested callState and skips CallerName phone filter`() {
        val frame = PhoneStatusProtocol.encodeNavigation(
            status = PhoneStatus(batteryPercent = 85, signalLevel = 3),
            heartbeat = 7,
            callState = CallState.ACTIVE_CALL,
            navText = "> 250m NH48",
        )
        assertEquals(PhoneStatusProtocol.FRAME_SIZE, frame.size)
        // batteryLevel 4 (85%) shifted by 3 | ACTIVE_CALL (3) = 0x23
        assertEquals(0x23.toByte(), frame[1])
        assertEquals(1.toByte(), frame[3])
        assertEquals(0.toByte(), frame[4])
        assertEquals("> 250m NH48", decodeCallerSlot(frame))
    }

    @Test
    fun `real incoming call preempts active navigation and resumes nav on call end`() = runTest {
        val fake = FakeBleTransport()
        fake.services = listOf(
            BleService(
                PhoneStatusProtocol.SERVICE_UUID,
                listOf(
                    BleCharacteristic(
                        PhoneStatusProtocol.GENERAL_UUID,
                        canRead = true,
                        canWrite = true,
                        canNotify = false,
                        canIndicate = false,
                    ),
                ),
            ),
        )
        val connection = BikeConnection(fake, backgroundScope, jitter = { it })
        connection.connect(testTarget(), autoReconnect = false)
        runCurrent()
        assertTrue(connection.state.value is ConnectionState.Connected)

        val statuses = MutableStateFlow(PhoneStatus(batteryPercent = 90, signalLevel = 4))
        val callEvents = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
        val navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 16)
        var navEnabled = true
        var mode = NavWireMode.ACTIVE_CALL

        val sender = PhoneStatusSender(
            connection = connection,
            statuses = statuses,
            scope = backgroundScope,
            callEvents = callEvents,
            callOptions = { CallMirrorOptions(callerName = true, missedCalls = true) },
            navEvents = navEvents,
            navOptions = { NavMirrorOptions(enabled = navEnabled, wireMode = mode) },
            intervalMillis = 1_000,
        )
        try {
            assertTrue(sender.start())
            runCurrent()

            navEvents.emit(NavEvent.Update("> 250m MG Rd"))
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            val navFrame = fake.latestSession.writes.last().bytes
            assertEquals(CallState.ACTIVE_CALL.wireValue, navFrame[1].toInt() and 0x07)
            assertEquals("> 250m MG Rd", decodeCallerSlot(navFrame))

            // Real incoming call preempts nav cue immediately on the next tick.
            callEvents.emit(CallEvent.Incoming("Alice"))
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            val callFrame = fake.latestSession.writes.last().bytes
            assertEquals(CallState.INCOMING_CALL.wireValue, callFrame[1].toInt() and 0x07)
            assertEquals("Alice", decodeCallerSlot(callFrame))

            // Ending the call restores active navigation cue, now tested with INCOMING_CALL mode.
            mode = NavWireMode.INCOMING_CALL
            callEvents.emit(CallEvent.Ended)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            val resumedNavFrame = fake.latestSession.writes.last().bytes
            assertEquals(CallState.INCOMING_CALL.wireValue, resumedNavFrame[1].toInt() and 0x07)
            assertEquals("> 250m MG Rd", decodeCallerSlot(resumedNavFrame))

            // Disabling nav mirror stops writing text on the next frame.
            navEnabled = false
            advanceTimeBy(1_000)
            runCurrent()
            val idleFrame = fake.latestSession.writes.last().bytes
            assertEquals(CallState.NO_CALL.wireValue, idleFrame[1].toInt() and 0x07)
            assertEquals(0, idleFrame[PhoneStatusProtocol.CALLER_LENGTH_OFFSET].toInt())
            assertFalse(idleFrame.contentEquals(resumedNavFrame))
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    private fun decodeCallerSlot(frame: ByteArray): String {
        val len = frame[PhoneStatusProtocol.CALLER_LENGTH_OFFSET].toInt() and 0xFF
        return frame.copyOfRange(
            PhoneStatusProtocol.CALLER_TEXT_OFFSET,
            PhoneStatusProtocol.CALLER_TEXT_OFFSET + len,
        ).toString(Charsets.UTF_8)
    }
}
