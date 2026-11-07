package dev.pulsarconnect.core.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BikeConnectionHandshakeTest {

    private val fake = FakeBleTransport()
    private val target = testTarget()
    private val status = PhoneStatus(50, 2)

    private fun kotlinx.coroutines.test.TestScope.connection(
        handshakeSettleMillis: Long = 0,
        handshakeTimeoutMillis: Long = 60_000,
        maxHandshakeAttempts: Int = 3,
    ): BikeConnection = BikeConnection(
        transport = fake,
        scope = this,
        handshakeSettleMillis = handshakeSettleMillis,
        handshakeTimeoutMillis = handshakeTimeoutMillis,
        maxHandshakeAttempts = maxHandshakeAttempts,
        jitter = { it },
    )

    @Test
    fun `connected is not published until the readiness probe succeeds`() = runTest {
        fake.services = emptyList()
        val gate = CompletableDeferred<Int>()
        fake.mtuAction = { gate.await() }
        val connection = connection()

        connection.connect(target)
        runCurrent()

        assertEquals(ConnectionState.Discovering(target), connection.state.value)
        assertEquals(
            listOf(PhoneStatusProtocol.PREFERRED_MTU),
            fake.latestSession.mtuRequests,
        )
        assertFalse(connection.sendPhoneStatus(status, 1))
        assertTrue(fake.latestSession.writes.isEmpty())

        gate.complete(PhoneStatusProtocol.PREFERRED_MTU)
        runCurrent()

        assertTrue(connection.state.value is ConnectionState.Connected)
        connection.stop()
    }

    @Test
    fun `a failing probe never publishes connected and reports not responding after retries`() = runTest {
        fake.services = emptyList()
        fake.mtuFailure = BleException(BleFailure.GattError("requestMtu", 133))
        val connection = connection(maxHandshakeAttempts = 3)

        connection.connect(target)
        advanceUntilIdle()

        val failed = connection.state.value as ConnectionState.Failed
        assertEquals(BleFailure.NotResponding, failed.reason)
        assertEquals(3, fake.connectCalls)
        assertTrue(fake.sessions.all { it.closed })
        assertTrue(fake.sessions.all { it.writes.isEmpty() })
        connection.stop()
    }

    @Test
    fun `a timed out probe closes each session and retries with backoff`() = runTest {
        fake.services = emptyList()
        fake.mtuDelayMillis = 60_000
        val connection = connection(handshakeTimeoutMillis = 50, maxHandshakeAttempts = 2)

        connection.connect(target)
        advanceUntilIdle()

        val failed = connection.state.value as ConnectionState.Failed
        assertEquals(BleFailure.NotResponding, failed.reason)
        assertEquals(2, fake.connectCalls)
        assertEquals(2, fake.sessions.size)
        assertTrue(fake.sessions.all { it.closed })
        assertTrue(fake.sessions.all { it.writes.isEmpty() })
        connection.stop()
    }

    @Test
    fun `a negotiated mtu below the payload requirement fails immediately`() = runTest {
        fake.services = emptyList()
        fake.negotiatedMtu = 23
        val connection = connection()

        connection.connect(target)
        advanceUntilIdle()

        val failed = connection.state.value as ConnectionState.Failed
        assertEquals(
            BleFailure.MtuTooSmall(PhoneStatusProtocol.REQUIRED_MTU, 23),
            failed.reason,
        )
        assertEquals(1, fake.connectCalls)
        assertTrue(fake.latestSession.closed)
        connection.stop()
    }

    @Test
    fun `the probe runs only after the encryption settle delay`() = runTest {
        fake.services = emptyList()
        val connection = connection(handshakeSettleMillis = 100)

        connection.connect(target)
        runCurrent()
        assertEquals(ConnectionState.Discovering(target), connection.state.value)
        assertTrue(fake.latestSession.mtuRequests.isEmpty())

        advanceTimeBy(99)
        runCurrent()
        assertTrue(fake.latestSession.mtuRequests.isEmpty())
        assertFalse(connection.state.value is ConnectionState.Connected)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(
            listOf(PhoneStatusProtocol.PREFERRED_MTU),
            fake.latestSession.mtuRequests,
        )
        assertTrue(connection.state.value is ConnectionState.Connected)
        connection.stop()
    }
}
