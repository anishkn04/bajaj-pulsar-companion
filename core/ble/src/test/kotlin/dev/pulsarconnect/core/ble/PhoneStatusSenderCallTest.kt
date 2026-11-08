package dev.pulsarconnect.core.ble

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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneStatusSenderCallTest {
    private val fake = FakeBleTransport()
    private val status = PhoneStatus(50, 2)

    private fun TestScope.connect(withMissedCall: Boolean = true): BikeConnection {
        val characteristics = mutableListOf(
            BleCharacteristic(
                PhoneStatusProtocol.GENERAL_UUID,
                canRead = true,
                canWrite = true,
                canNotify = false,
                canIndicate = false,
            ),
        )
        if (withMissedCall) {
            characteristics += BleCharacteristic(
                PhoneStatusProtocol.MISSED_CALL_UUID,
                canRead = true,
                canWrite = true,
                canNotify = false,
                canIndicate = false,
            )
        }
        fake.services = listOf(BleService(PhoneStatusProtocol.SERVICE_UUID, characteristics))
        val connection = BikeConnection(fake, backgroundScope, jitter = { it })
        connection.connect(testTarget(), autoReconnect = false)
        runCurrent()
        assertTrue(connection.state.value is ConnectionState.Connected)
        return connection
    }

    private fun TestScope.sender(
        connection: BikeConnection,
        events: MutableSharedFlow<CallEvent>,
        options: () -> CallMirrorOptions,
    ) = PhoneStatusSender(
        connection = connection,
        statuses = MutableStateFlow(status),
        scope = backgroundScope,
        callEvents = events,
        callOptions = options,
        intervalMillis = 1_000,
    )

    private fun generalWrites() = fake.latestSession.writes.filter {
        it.characteristic == PhoneStatusProtocol.GENERAL_UUID
    }

    private fun missedWrites() = fake.latestSession.writes.filter {
        it.characteristic == PhoneStatusProtocol.MISSED_CALL_UUID
    }

    @Test
    fun `incoming caller name is added to the next GENERAL frame`() = runTest {
        val connection = connect()
        val events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
        val sender = sender(connection, events) { CallMirrorOptions(callerName = true) }
        try {
            sender.start()
            runCurrent()
            events.emit(CallEvent.Incoming("Asha"))
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            val latest = generalWrites().last()
            assertArrayEquals(
                PhoneStatusProtocol.encode(status, 2, CallState.INCOMING_CALL, "Asha"),
                latest.bytes,
            )
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `caller mirroring is off by default so events leave the frame idle`() = runTest {
        val connection = connect()
        val events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
        val sender = sender(connection, events) { CallMirrorOptions() }
        try {
            sender.start()
            runCurrent()
            events.emit(CallEvent.Incoming("Asha"))
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            for (write in generalWrites()) {
                assertEquals(0, write.bytes[20].toInt())
                assertEquals(0, write.bytes[1].toInt() and 0x07)
            }
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `ending a call clears the caller text on the next frame`() = runTest {
        val connection = connect()
        val events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
        val sender = sender(connection, events) { CallMirrorOptions(callerName = true) }
        try {
            sender.start()
            runCurrent()
            events.emit(CallEvent.Incoming("Asha"))
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(generalWrites().last().bytes[20].toInt() > 0)

            events.emit(CallEvent.Ended)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            val latest = generalWrites().last()
            assertEquals(0, latest.bytes[20].toInt())
            assertEquals(0, latest.bytes[1].toInt() and 0x07)
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `active call keeps the caller name`() = runTest {
        val connection = connect()
        val events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
        val sender = sender(connection, events) { CallMirrorOptions(callerName = true) }
        try {
            sender.start()
            runCurrent()
            events.emit(CallEvent.Active("Asha"))
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            assertArrayEquals(
                PhoneStatusProtocol.encode(status, 2, CallState.ACTIVE_CALL, "Asha"),
                generalWrites().last().bytes,
            )
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `a missed call sends one fixed icon frame ahead of the status frame`() = runTest {
        val connection = connect()
        val events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
        val sender = sender(connection, events) { CallMirrorOptions(missedCalls = true) }
        try {
            sender.start()
            runCurrent()
            assertEquals(1, generalWrites().size)
            assertTrue(missedWrites().isEmpty())

            events.emit(CallEvent.Missed)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(1, missedWrites().size)
            assertArrayEquals(PhoneStatusProtocol.encodeMissedCall(), missedWrites().single().bytes)
            assertEquals(2, generalWrites().size)

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, missedWrites().size, "the icon must not repeat")
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `missed call icon is not sent when the feature is off`() = runTest {
        val connection = connect()
        val events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
        val sender = sender(connection, events) {
            CallMirrorOptions(callerName = true, missedCalls = false)
        }
        try {
            sender.start()
            runCurrent()
            events.emit(CallEvent.Missed)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            assertTrue(missedWrites().isEmpty())
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `a missing missed-call endpoint does not stop normal mirroring`() = runTest {
        val connection = connect(withMissedCall = false)
        val events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
        val sender = sender(connection, events) { CallMirrorOptions(missedCalls = true) }
        try {
            sender.start()
            runCurrent()
            events.emit(CallEvent.Missed)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            assertTrue(missedWrites().isEmpty())
            assertTrue(sender.running)
            assertTrue(generalWrites().size >= 2)
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `disabling missed calls between event and write discards the pending icon`() = runTest {
        val connection = connect()
        val events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
        var options = CallMirrorOptions(missedCalls = true)
        val sender = PhoneStatusSender(
            connection = connection,
            statuses = MutableStateFlow(status),
            scope = backgroundScope,
            callEvents = events,
            callOptions = { options },
            intervalMillis = 1_000,
        )
        try {
            sender.start()
            runCurrent()
            events.emit(CallEvent.Missed)
            runCurrent()
            options = CallMirrorOptions(missedCalls = false)
            advanceTimeBy(1_000)
            runCurrent()

            assertTrue(missedWrites().isEmpty())
            assertTrue(generalWrites().isNotEmpty())
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `a missed-call first write still marks mirroring as live`() = runTest {
        val connection = connect()
        val events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
        var sent = 0
        val sender = PhoneStatusSender(
            connection = connection,
            statuses = MutableStateFlow(status),
            scope = backgroundScope,
            callEvents = events,
            callOptions = { CallMirrorOptions(missedCalls = true) },
            intervalMillis = 1_000,
            onSent = { sent++ },
        )
        try {
            sender.start()
            runCurrent()
            events.emit(CallEvent.Missed)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            assertTrue(sender.hasSent)
            assertEquals(1, sent)
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `events arriving while disconnected are dropped rather than replayed`() = runTest {
        val connection = connect()
        val events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
        val sender = sender(connection, events) { CallMirrorOptions(callerName = true, missedCalls = true) }
        try {
            sender.start()
            runCurrent()
            sender.stop()
            runCurrent()

            events.emit(CallEvent.Incoming("Asha"))
            events.emit(CallEvent.Missed)
            runCurrent()

            sender.start()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            assertTrue(missedWrites().isEmpty())
            assertEquals(0, generalWrites().last().bytes[20].toInt())
            assertFalse(generalWrites().isEmpty())
        } finally {
            sender.stop()
            connection.stop()
        }
    }
}
