package dev.pulsarconnect.core.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BikeConnectionTest {

    private val fake = FakeBleTransport()
    private val target = testTarget()
    private var now = 1_000L

    private fun TestScope.connection(
        maxReconnectAttempts: Int = BikeConnection.DEFAULT_MAX_RECONNECT_ATTEMPTS,
    ): BikeConnection = BikeConnection(
        transport = fake,
        scope = this,
        clock = { now },
        jitter = { it },
        maxReconnectAttempts = maxReconnectAttempts,
    )

    @Test
    fun `connect discovers services and reaches connected`() = runTest {
        val services = listOf(BleService(java.util.UUID.randomUUID(), emptyList()))
        fake.services = services
        val connection = connection()

        connection.connect(target)
        advanceUntilIdle()

        val state = connection.state.value as ConnectionState.Connected
        assertEquals(target, state.target)
        assertEquals(services, state.services)
        assertEquals(now, state.sinceMillis)
        assertEquals(1, fake.connectCalls)
        assertFalse(fake.latestSession.closed)
        connection.stop()
    }

    @Test
    fun `connect timeout fails without leaking a session`() = runTest {
        fake.connectDelayMillis = 60_000
        val connection = connection()

        connection.connect(target)
        advanceUntilIdle()

        val failed = connection.state.value as ConnectionState.Failed
        assertEquals(target, failed.target)
        assertTrue(failed.reason is BleFailure.ConnectTimeout)
        assertTrue(fake.sessions.isEmpty())
    }

    @Test
    fun `discovery timeout closes the session`() = runTest {
        fake.servicesDelayMillis = 60_000
        val connection = connection()

        connection.connect(target)
        advanceUntilIdle()

        val failed = connection.state.value as ConnectionState.Failed
        assertTrue(failed.reason is BleFailure.DiscoveryTimeout)
        assertTrue(fake.latestSession.closed)
    }

    @Test
    fun `initial connect failure is reported without retrying`() = runTest {
        fake.connectFailure = BleException(BleFailure.GattError("connect", 133))
        val connection = connection()

        connection.connect(target)
        advanceUntilIdle()

        val failed = connection.state.value as ConnectionState.Failed
        assertEquals(BleFailure.GattError("connect", 133), failed.reason)
        assertEquals(1, fake.connectCalls)
    }

    @Test
    fun `unexpected disconnect reconnects with growing backoff`() = runTest {
        val connection = connection()
        val states = mutableListOf<ConnectionState>()
        val collector = launch { connection.state.collect { states += it } }

        connection.connect(target)
        advanceUntilIdle()
        fake.latestSession.disconnect(19)
        advanceUntilIdle()

        assertEquals(2, fake.connectCalls)
        assertTrue(connection.state.value is ConnectionState.Connected)
        val firstRetry = states.filterIsInstance<ConnectionState.Reconnecting>().single()
        assertEquals(1, firstRetry.attempt)
        assertEquals(now + 1_000, firstRetry.retryAtMillis)

        fake.latestSession.disconnect(19)
        advanceUntilIdle()

        assertEquals(3, fake.connectCalls)
        val retries = states.filterIsInstance<ConnectionState.Reconnecting>()
        assertEquals(listOf(1, 2), retries.map { it.attempt })
        assertEquals(listOf(now + 1_000, now + 2_000), retries.map { it.retryAtMillis })
        collector.cancel()
        connection.stop()
    }

    @Test
    fun `stable connection resets the backoff`() = runTest {
        val connection = connection()
        val states = mutableListOf<ConnectionState>()
        val collector = launch { connection.state.collect { states += it } }

        connection.connect(target)
        advanceUntilIdle()
        now += 61_000
        fake.latestSession.disconnect(19)
        advanceUntilIdle()
        now += 61_000
        fake.latestSession.disconnect(19)
        advanceUntilIdle()

        val attempts = states.filterIsInstance<ConnectionState.Reconnecting>().map { it.attempt }
        assertEquals(listOf(1, 1), attempts)
        collector.cancel()
        connection.stop()
    }

    @Test
    fun `auto reconnect disabled ends in failed on disconnect`() = runTest {
        val connection = connection()

        connection.connect(target, autoReconnect = false)
        advanceUntilIdle()
        fake.latestSession.disconnect(19)
        advanceUntilIdle()

        val failed = connection.state.value as ConnectionState.Failed
        assertEquals(BleFailure.Disconnected(19), failed.reason)
        assertEquals(1, fake.connectCalls)
    }

    @Test
    fun `disabling auto reconnect preserves a healthy session but prevents its next retry`() = runTest {
        val connection = connection()
        try {
            connection.connect(target)
            runCurrent()
            val session = fake.latestSession
            val connected = connection.state.value

            connection.setAutoReconnect(false)
            runCurrent()

            assertEquals(connected, connection.state.value)
            assertEquals(1, fake.connectCalls)
            assertFalse(session.closed)

            session.disconnect(19)
            advanceUntilIdle()

            assertEquals(ConnectionState.Failed(target, BleFailure.Disconnected(19)), connection.state.value)
            assertEquals(1, fake.connectCalls)
            assertEquals(1, session.closeCount)
        } finally {
            connection.stop()
        }
    }

    @Test
    fun `enabling auto reconnect preserves a healthy session and retries its next disconnect`() = runTest {
        val connection = connection()
        try {
            connection.connect(target, autoReconnect = false)
            runCurrent()
            val session = fake.latestSession
            val connected = connection.state.value

            connection.setAutoReconnect(true)
            runCurrent()

            assertEquals(connected, connection.state.value)
            assertEquals(1, fake.connectCalls)
            assertFalse(session.closed)

            session.disconnect(19)
            advanceUntilIdle()

            assertTrue(connection.state.value is ConnectionState.Connected)
            assertEquals(2, fake.connectCalls)
            assertEquals(1, session.closeCount)
        } finally {
            connection.stop()
        }
    }

    @Test
    fun `disabling during backoff becomes idle and retains disabled policy for manual retry`() = runTest {
        val connection = connection()
        try {
            connection.connect(target)
            runCurrent()
            fake.latestSession.disconnect(19)
            runCurrent()
            assertTrue(connection.state.value is ConnectionState.Reconnecting)

            connection.setAutoReconnect(false)
            assertEquals(ConnectionState.Idle, connection.state.value)
            advanceUntilIdle()

            assertEquals(ConnectionState.Idle, connection.state.value)
            assertEquals(1, fake.connectCalls)
            assertTrue(fake.sessions.all { it.closed })

            connection.retry()
            runCurrent()
            assertTrue(connection.state.value is ConnectionState.Connected)
            assertEquals(2, fake.connectCalls)
            fake.latestSession.disconnect(19)
            advanceUntilIdle()

            assertEquals(ConnectionState.Failed(target, BleFailure.Disconnected(19)), connection.state.value)
            assertEquals(2, fake.connectCalls)
        } finally {
            connection.stop()
        }
    }

    @Test
    fun `enabling after cancelled backoff does not restart until manual retry`() = runTest {
        val connection = connection()
        try {
            connection.connect(target)
            runCurrent()
            fake.latestSession.disconnect(19)
            runCurrent()
            assertTrue(connection.state.value is ConnectionState.Reconnecting)

            connection.setAutoReconnect(false)
            connection.setAutoReconnect(true)
            advanceUntilIdle()

            assertEquals(ConnectionState.Idle, connection.state.value)
            assertEquals(1, fake.connectCalls)

            connection.retry()
            runCurrent()
            assertEquals(2, fake.connectCalls)
            fake.latestSession.disconnect(19)
            advanceUntilIdle()

            assertTrue(connection.state.value is ConnectionState.Connected)
            assertEquals(3, fake.connectCalls)
        } finally {
            connection.stop()
        }
    }

    @Test
    fun `policy changed after failure is used by manual retry`() = runTest {
        val connection = connection()
        try {
            fake.connectFailure = BleException(BleFailure.GattError("connect", 133))
            connection.connect(target)
            runCurrent()
            val failed = connection.state.value
            assertTrue(failed is ConnectionState.Failed)

            connection.setAutoReconnect(false)
            runCurrent()
            assertEquals(failed, connection.state.value)
            assertEquals(1, fake.connectCalls)

            fake.connectFailure = null
            connection.retry()
            runCurrent()
            assertTrue(connection.state.value is ConnectionState.Connected)
            fake.latestSession.disconnect(19)
            advanceUntilIdle()

            assertEquals(ConnectionState.Failed(target, BleFailure.Disconnected(19)), connection.state.value)
            assertEquals(2, fake.connectCalls)
        } finally {
            connection.stop()
        }
    }

    @Test
    fun `policy changed during discovery applies when the session connects`() = runTest {
        val connection = connection()
        try {
            fake.servicesDelayMillis = 100
            connection.connect(target)
            runCurrent()
            assertTrue(connection.state.value is ConnectionState.Discovering)

            connection.setAutoReconnect(false)
            advanceUntilIdle()
            assertTrue(connection.state.value is ConnectionState.Connected)
            assertEquals(1, fake.connectCalls)
            assertFalse(fake.latestSession.closed)

            fake.latestSession.disconnect(19)
            advanceUntilIdle()

            assertEquals(ConnectionState.Failed(target, BleFailure.Disconnected(19)), connection.state.value)
            assertEquals(1, fake.connectCalls)
        } finally {
            connection.stop()
        }
    }

    @Test
    fun `disabling while waiting for adapter recovery cancels automatic reconnect`() = runTest {
        val connection = connection()
        try {
            connection.connect(target)
            runCurrent()
            fake.adapterState.value = AdapterState.OFF
            runCurrent()
            assertEquals(ConnectionState.BluetoothUnavailable, connection.state.value)
            assertTrue(fake.latestSession.closed)

            connection.setAutoReconnect(false)
            assertEquals(ConnectionState.Idle, connection.state.value)
            fake.adapterState.value = AdapterState.ON
            advanceUntilIdle()

            assertEquals(ConnectionState.Idle, connection.state.value)
            assertEquals(1, fake.connectCalls)
        } finally {
            connection.stop()
        }
    }

    @Test
    fun `stop during reconnect cancels the pending attempt`() = runTest {
        val connection = connection()

        connection.connect(target)
        advanceUntilIdle()
        fake.latestSession.disconnect(19)
        runCurrent()

        assertTrue(connection.state.value is ConnectionState.Reconnecting)
        connection.stop()
        advanceUntilIdle()

        assertEquals(ConnectionState.Idle, connection.state.value)
        assertEquals(1, fake.connectCalls)
        assertTrue(fake.sessions.all { it.closed })
    }

    @Test
    fun `late disconnect after stop is ignored`() = runTest {
        val connection = connection()

        connection.connect(target)
        advanceUntilIdle()
        val session = fake.latestSession
        connection.stop()
        session.disconnect(0)
        advanceUntilIdle()

        assertEquals(ConnectionState.Idle, connection.state.value)
        assertEquals(1, fake.connectCalls)
    }

    @Test
    fun `adapter off before connect reports unavailable without connecting`() = runTest {
        fake.adapterState.value = AdapterState.OFF
        val connection = connection()

        connection.connect(target)
        advanceUntilIdle()

        assertEquals(ConnectionState.BluetoothUnavailable, connection.state.value)
        assertEquals(0, fake.connectCalls)
    }

    @Test
    fun `adapter off closes the session and adapter on reconnects`() = runTest {
        val connection = connection()

        connection.connect(target)
        advanceUntilIdle()
        val first = fake.latestSession

        fake.adapterState.value = AdapterState.OFF
        advanceUntilIdle()
        assertEquals(ConnectionState.BluetoothUnavailable, connection.state.value)
        assertTrue(first.closed)

        fake.adapterState.value = AdapterState.ON
        advanceUntilIdle()
        assertTrue(connection.state.value is ConnectionState.Connected)
        assertEquals(2, fake.connectCalls)
        connection.stop()
    }

    @Test
    fun `missing permissions stop before any connection`() = runTest {
        fake.missing = setOf(BlePermission.SCAN)
        val connection = connection()

        connection.connect(target)
        advanceUntilIdle()

        val state = connection.state.value as ConnectionState.MissingPermissions
        assertEquals(setOf(BlePermission.SCAN), state.missing)
        assertEquals(0, fake.connectCalls)
    }

    @Test
    fun `reconnect failures stop after the attempt limit`() = runTest {
        val connection = connection(maxReconnectAttempts = 2)

        connection.connect(target)
        advanceUntilIdle()
        fake.connectFailure = BleException(BleFailure.GattError("connect", 133))
        fake.latestSession.disconnect(19)
        advanceUntilIdle()

        val failed = connection.state.value as ConnectionState.Failed
        assertTrue(failed.reason is BleFailure.TooManyAttempts)
        assertEquals(3, fake.connectCalls)
    }

    @Test
    fun `connecting to a new target closes the previous session`() = runTest {
        val connection = connection()

        connection.connect(target)
        advanceUntilIdle()
        val first = fake.latestSession
        val other = testTarget(id = "11:22:33:44:55:66", name = "Other", vin = "MD2ABCDEFGH654321")

        connection.connect(other)
        advanceUntilIdle()

        assertTrue(first.closed)
        val state = connection.state.value as ConnectionState.Connected
        assertEquals(other, state.target)
        assertEquals(2, fake.connectCalls)
        connection.stop()
    }

    @Test
    fun `owner cancellation closes a connected session`() = runTest {
        val owner = Job(coroutineContext[Job])
        val connection = BikeConnection(fake, CoroutineScope(coroutineContext + owner))
        try {
            connection.connect(target)
            runCurrent()
            assertTrue(connection.state.value is ConnectionState.Connected)

            owner.cancel()
            runCurrent()

            assertTrue(fake.latestSession.closed)
            assertEquals(1, fake.latestSession.closeCount)
            assertEquals(ConnectionState.Idle, connection.state.value)
        } finally {
            connection.stop()
            owner.cancel()
        }
    }

    @Test
    fun `owner cancellation during discovery closes the session`() = runTest {
        fake.servicesDelayMillis = 60_000
        val owner = Job(coroutineContext[Job])
        val connection = BikeConnection(fake, CoroutineScope(coroutineContext + owner))
        try {
            connection.connect(target)
            runCurrent()
            assertTrue(connection.state.value is ConnectionState.Discovering)

            owner.cancel()
            runCurrent()

            assertTrue(fake.latestSession.closed)
            assertEquals(1, fake.latestSession.closeCount)
            assertEquals(ConnectionState.Idle, connection.state.value)
        } finally {
            connection.stop()
            owner.cancel()
        }
    }

    @Test
    fun `permissions revoked during backoff prevent the next connect`() = runTest {
        val connection = connection()
        try {
            connection.connect(target)
            runCurrent()
            fake.latestSession.disconnect(19)
            runCurrent()
            assertTrue(connection.state.value is ConnectionState.Reconnecting)

            fake.missing = setOf(BlePermission.CONNECT)
            advanceUntilIdle()

            assertEquals(1, fake.connectCalls)
            assertEquals(ConnectionState.MissingPermissions(fake.missing), connection.state.value)
        } finally {
            connection.stop()
        }
    }

    @Test
    fun `adapter disabled during backoff prevents the next connect`() = runTest {
        val connection = connection()
        try {
            connection.connect(target)
            runCurrent()
            fake.latestSession.disconnect(19)
            runCurrent()
            assertTrue(connection.state.value is ConnectionState.Reconnecting)

            fake.adapterState.value = AdapterState.OFF
            advanceUntilIdle()

            assertEquals(1, fake.connectCalls)
            assertEquals(ConnectionState.BluetoothUnavailable, connection.state.value)
        } finally {
            connection.stop()
        }
    }

    @Test
    fun `late discovery failure cannot close a replacement session`() = runTest {
        fake.servicesAction = {
            withContext(NonCancellable) {
                delay(100)
                throw BleException(BleFailure.DiscoveryFailed("Late failure"))
            }
        }
        val connection = connection()
        try {
            connection.connect(target)
            runCurrent()
            val old = fake.latestSession
            assertTrue(connection.state.value is ConnectionState.Discovering)

            fake.servicesAction = null
            val replacement = target.copy(id = "11:22:33:44:55:66")
            connection.connect(replacement)
            runCurrent()
            val current = fake.latestSession
            assertEquals(replacement, (connection.state.value as ConnectionState.Connected).target)

            advanceTimeBy(100)
            runCurrent()

            assertTrue(old.closed)
            assertEquals(1, old.closeCount)
            assertFalse(current.closed, "Stale discovery must only clean up its own session")
            assertEquals(replacement, (connection.state.value as ConnectionState.Connected).target)
        } finally {
            connection.stop()
        }
    }

    @Test
    fun `retry restarts the last target after failure`() = runTest {
        fake.connectFailure = BleException(BleFailure.GattError("connect", 133))
        val connection = connection()

        connection.connect(target, autoReconnect = false)
        advanceUntilIdle()
        assertTrue(connection.state.value is ConnectionState.Failed)

        fake.connectFailure = null
        connection.retry()
        advanceUntilIdle()

        assertTrue(connection.state.value is ConnectionState.Connected)
        assertEquals(2, fake.connectCalls)
        connection.stop()
    }
}
