package dev.pulsarconnect.core.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Background mode uses passive GATT connections, so the link reattaches when the bike powers on
 * instead of polling or giving up after the foreground reconnect budget.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BikeConnectionWaitTest {

    private val fake = FakeBleTransport()
    private val target = testTarget()
    private var now = 1_000L

    private fun TestScope.connection(): BikeConnection = BikeConnection(
        transport = fake,
        scope = backgroundScope,
        clock = { now },
        jitter = { it },
    )

    @Test
    fun `an ordinary connect is not passive`() = runTest {
        val connection = connection()

        connection.connect(target)
        runCurrent()

        assertEquals(listOf(false), fake.passiveCalls)
        assertTrue(connection.state.value is ConnectionState.Connected)
        connection.stop()
    }

    @Test
    fun `waiting retries a failed passive connection until the bike appears`() = runTest {
        fake.connectFailure = BleException(BleFailure.GattError("connect", 133))
        val connection = connection()

        connection.connect(target, waitForBike = true)
        runCurrent()

        assertTrue(connection.state.value is ConnectionState.WaitingForBike)
        assertEquals(listOf(true), fake.passiveCalls)
        assertEquals(1, fake.connectCalls)

        fake.connectFailure = null
        advanceTimeBy(BikeConnection.DEFAULT_WAITING_RETRY_DELAYS.first() + 1)
        runCurrent()

        assertEquals(2, fake.connectCalls)
        assertEquals(listOf(true, true), fake.passiveCalls)
        assertTrue(connection.state.value is ConnectionState.Connected)
        connection.stop()
    }

    @Test
    fun `waiting stays armed past the foreground reconnect limit`() = runTest {
        fake.connectFailure = BleException(BleFailure.GattError("connect", 133))
        val connection = connection()

        connection.connect(target, waitForBike = true)
        runCurrent()

        // Well past the ten-attempt foreground budget of roughly three minutes.
        advanceTimeBy(30 * 60_000L)
        runCurrent()

        assertTrue(fake.connectCalls > BikeConnection.DEFAULT_MAX_RECONNECT_ATTEMPTS)
        assertTrue(connection.state.value is ConnectionState.WaitingForBike)
        connection.stop()
    }

    @Test
    fun `a dropped link re-arms passively without polling`() = runTest {
        val connection = connection()
        connection.connect(target, waitForBike = true)
        runCurrent()
        assertTrue(connection.state.value is ConnectionState.Connected)

        fake.latestSession.disconnect()
        runCurrent()

        assertTrue(connection.state.value is ConnectionState.WaitingForBike)
        assertEquals(listOf(true), fake.passiveCalls)

        advanceTimeBy(BikeConnection.DEFAULT_WAITING_RETRY_DELAYS.first() + 1)
        runCurrent()

        assertEquals(2, fake.connectCalls)
        assertTrue(connection.state.value is ConnectionState.Connected)
        connection.stop()
    }

    @Test
    fun `enabling waiting revives a terminal failure`() = runTest {
        fake.connectFailure = BleException(BleFailure.GattError("connect", 133))
        val connection = connection()
        connection.connect(target)
        runCurrent()
        assertTrue(connection.state.value is ConnectionState.Failed)

        fake.connectFailure = null
        connection.setWaitForBike(true)
        runCurrent()

        assertEquals(listOf(false, true), fake.passiveCalls)
        assertTrue(connection.state.value is ConnectionState.Connected)
        connection.stop()
    }

    @Test
    fun `disabling waiting releases the passive connection`() = runTest {
        fake.connectFailure = BleException(BleFailure.GattError("connect", 133))
        val connection = connection()
        connection.connect(target, waitForBike = true)
        runCurrent()
        assertTrue(connection.state.value is ConnectionState.WaitingForBike)

        connection.setWaitForBike(false)
        runCurrent()

        assertEquals(ConnectionState.Idle, connection.state.value)
        assertEquals(1, fake.connectCalls)
    }
}
