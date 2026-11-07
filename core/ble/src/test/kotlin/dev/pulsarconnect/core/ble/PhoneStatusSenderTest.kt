package dev.pulsarconnect.core.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class PhoneStatusSenderTest {
    private val fake = FakeBleTransport()
    private val status = PhoneStatus(50, 2)

    private fun TestScope.connectSupported(): BikeConnection {
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
        return connection
    }

    private fun heartbeat(write: FakeBleSession.Write): Int = write.bytes[53].toInt() and 0xff

    @Test
    fun `sends on a one second cadence and wraps the heartbeat`() = runTest {
        val statuses = MutableStateFlow(status)
        val connection = connectSupported()
        val sender = PhoneStatusSender(
            connection,
            statuses,
            backgroundScope,
            intervalMillis = 1_000,
            initialHeartbeat = 254,
        )
        try {
            assertTrue(sender.start())
            runCurrent()
            assertEquals(1, fake.latestSession.writes.size)
            assertEquals(254, heartbeat(fake.latestSession.writes[0]))

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(255, heartbeat(fake.latestSession.writes[1]))

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(0, heartbeat(fake.latestSession.writes[2]))

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, heartbeat(fake.latestSession.writes[3]))

            statuses.value = PhoneStatus(80, 4)
            advanceTimeBy(1_000)
            runCurrent()
            assertArrayEquals(
                PhoneStatusProtocol.encodeIdle(PhoneStatus(80, 4), 2),
                fake.latestSession.writes[4].bytes,
            )
            assertEquals(5, fake.latestSession.writes.size)
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `starting twice does not create a second loop`() = runTest {
        val connection = connectSupported()
        val sender = PhoneStatusSender(connection, MutableStateFlow(status), backgroundScope, intervalMillis = 1_000)
        try {
            assertTrue(sender.start())
            assertFalse(sender.start())
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(2, fake.latestSession.writes.size)
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `disconnect stops sending without an error`() = runTest {
        val connection = connectSupported()
        val stopped = mutableListOf<Throwable?>()
        val sender = PhoneStatusSender(
            connection,
            MutableStateFlow(status),
            backgroundScope,
            intervalMillis = 1_000,
            onStopped = { stopped += it },
        )
        try {
            sender.start()
            runCurrent()
            assertEquals(1, fake.latestSession.writes.size)

            fake.latestSession.disconnect()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            assertFalse(sender.running)
            assertEquals(listOf<Throwable?>(null), stopped)
            assertEquals(1, fake.latestSession.writes.size)
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `write failure stops the loop and reports the error`() = runTest {
        val connection = connectSupported()
        val failure = BleException(BleFailure.GattError("writeCharacteristic", 133))
        fake.latestSession.writeAction = { throw failure }
        val stopped = mutableListOf<Throwable?>()
        val sender = PhoneStatusSender(
            connection,
            MutableStateFlow(status),
            backgroundScope,
            onStopped = { stopped += it },
        )
        try {
            sender.start()
            runCurrent()

            assertFalse(sender.running)
            assertEquals(listOf<Throwable?>(failure), stopped)
            assertEquals(1, fake.latestSession.writes.size)
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `unsupported peer stops the loop without writing`() = runTest {
        fake.services = emptyList()
        val connection = BikeConnection(fake, backgroundScope, jitter = { it })
        connection.connect(testTarget(), autoReconnect = false)
        runCurrent()
        assertTrue(connection.state.value is ConnectionState.Connected)
        val stopped = mutableListOf<Throwable?>()
        val sender = PhoneStatusSender(
            connection,
            MutableStateFlow(status),
            backgroundScope,
            onStopped = { stopped += it },
        )
        try {
            sender.start()
            runCurrent()

            assertFalse(sender.running)
            assertEquals(listOf<Throwable?>(null), stopped)
            assertTrue(fake.latestSession.writes.isEmpty())
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `cancelling the sender does not report an error`() = runTest {
        val connection = connectSupported()
        val stopped = mutableListOf<Throwable?>()
        val sender = PhoneStatusSender(
            connection,
            MutableStateFlow(status),
            backgroundScope,
            onStopped = { stopped += it },
        )
        try {
            sender.start()
            runCurrent()
            sender.stop()
            runCurrent()

            assertFalse(sender.running)
            assertTrue(stopped.isEmpty())
        } finally {
            connection.stop()
        }
    }

    @Test
    fun `sender reports the exact error only once`() = runTest {
        val connection = connectSupported()
        val failure = IllegalStateException("Synthetic failure")
        fake.latestSession.writeAction = { throw failure }
        var calls = 0
        val sender = PhoneStatusSender(
            connection,
            MutableStateFlow(status),
            backgroundScope,
            onStopped = { calls++ },
        )
        sender.start()
        runCurrent()
        sender.stop()
        runCurrent()

        assertEquals(1, calls)
        connection.stop()
    }

    @Test
    fun `status unavailable still reports later readings`() = runTest {
        val connection = connectSupported()
        val statuses = MutableStateFlow(PhoneStatus(null, null))
        val sender = PhoneStatusSender(connection, statuses, backgroundScope, intervalMillis = 1_000)
        try {
            sender.start()
            runCurrent()
            assertEquals(1, fake.latestSession.writes.size)

            statuses.value = PhoneStatus(23, 1)
            advanceTimeBy(1_000)
            runCurrent()

            assertArrayEquals(
                PhoneStatusProtocol.encodeIdle(PhoneStatus(23, 1), 2),
                fake.latestSession.writes[1].bytes,
            )
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `has sent stays false until the first write completes`() = runTest {
        val connection = connectSupported()
        val gate = CompletableDeferred<Unit>()
        fake.latestSession.writeAction = { gate.await() }
        var sent = 0
        val sender = PhoneStatusSender(
            connection,
            MutableStateFlow(status),
            backgroundScope,
            intervalMillis = 1_000,
            onSent = { sent++ },
        )
        try {
            sender.start()
            runCurrent()

            assertTrue(sender.running)
            assertFalse(sender.hasSent)
            assertEquals(0, sent)
            assertEquals(1, fake.latestSession.writes.size)

            gate.complete(Unit)
            runCurrent()

            assertTrue(sender.hasSent)
            assertEquals(1, sent)
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `has sent and on sent reset for a new run`() = runTest {
        val connection = connectSupported()
        var sent = 0
        val sender = PhoneStatusSender(
            connection,
            MutableStateFlow(status),
            backgroundScope,
            intervalMillis = 1_000,
            onSent = { sent++ },
        )
        try {
            sender.start()
            runCurrent()
            assertTrue(sender.hasSent)
            assertEquals(1, sent)

            sender.stop()
            assertFalse(sender.hasSent)
            assertEquals(1, sent)

            sender.start()
            runCurrent()
            assertTrue(sender.hasSent)
            assertEquals(2, sent)
            assertEquals(2, fake.latestSession.writes.size)
        } finally {
            sender.stop()
            connection.stop()
        }
    }

    @Test
    fun `a disconnect clears has sent without another callback`() = runTest {
        val connection = connectSupported()
        var sent = 0
        val sender = PhoneStatusSender(
            connection,
            MutableStateFlow(status),
            backgroundScope,
            intervalMillis = 1_000,
            onSent = { sent++ },
        )
        try {
            sender.start()
            runCurrent()
            assertTrue(sender.hasSent)

            fake.latestSession.disconnect()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            assertFalse(sender.running)
            assertFalse(sender.hasSent)
            assertEquals(1, sent)
        } finally {
            sender.stop()
            connection.stop()
        }
    }
}
