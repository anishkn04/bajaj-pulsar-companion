package dev.pulsarconnect.core.ble

import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControlPollerTest {

    private val fake = FakeBleTransport()
    private val controlFrame = ByteArray(ControlProtocol.RESPONSE_SIZE).apply {
        this[1] = 4
        this[8] = 2
    }

    private fun services(withControl: Boolean = true): List<BleService> {
        val general = BleService(
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
        )
        if (!withControl) return listOf(general)
        return listOf(
            general,
            BleService(
                ControlProtocol.SERVICE_UUID,
                listOf(
                    BleCharacteristic(
                        ControlProtocol.CONTROL_UUID,
                        canRead = true,
                        canWrite = false,
                        canNotify = false,
                        canIndicate = false,
                    ),
                ),
            ),
        )
    }

    private fun kotlinx.coroutines.test.TestScope.connect(
        withControl: Boolean = true,
    ): BikeConnection {
        fake.services = services(withControl)
        fake.readResponse = controlFrame
        val connection = BikeConnection(fake, backgroundScope, jitter = { it })
        connection.connect(testTarget(), autoReconnect = false)
        runCurrent()
        assertTrue(connection.state.value is ConnectionState.Connected)
        return connection
    }

    @Test
    fun `polls on the recovered cadence and publishes parsed frames`() = runTest {
        val connection = connect()
        val poller = ControlPoller(connection, backgroundScope, intervalMillis = 700)
        try {
            assertTrue(poller.start())
            runCurrent()
            assertEquals(1, fake.latestSession.reads.size)
            assertEquals(4, poller.state.value.frame?.callAccept)
            assertEquals(2, poller.state.value.frame?.next)
            assertTrue(poller.state.value.polling)

            advanceTimeBy(699)
            runCurrent()
            assertEquals(1, fake.latestSession.reads.size)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, fake.latestSession.reads.size)
        } finally {
            poller.stop()
            connection.stop()
        }
    }

    @Test
    fun `a disconnect ends polling without inventing a frame`() = runTest {
        val connection = connect()
        val poller = ControlPoller(connection, backgroundScope)
        try {
            poller.start()
            runCurrent()
            assertTrue(poller.state.value.polling)

            fake.latestSession.disconnect()
            runCurrent()
            advanceTimeBy(700)
            runCurrent()

            assertFalse(poller.state.value.polling)
            assertNull(poller.state.value.frame)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(1, fake.latestSession.reads.size)
        } finally {
            poller.stop()
            connection.stop()
        }
    }

    @Test
    fun `a peer without the control characteristic is never polled`() = runTest {
        val connection = connect(withControl = false)
        val poller = ControlPoller(connection, backgroundScope)
        try {
            poller.start()
            runCurrent()

            assertFalse(poller.state.value.polling)
            assertTrue(fake.latestSession.reads.isEmpty())
        } finally {
            poller.stop()
            connection.stop()
        }
    }

    @Test
    fun `a short response stops polling instead of publishing zeros`() = runTest {
        val connection = connect()
        fake.readResponse = ByteArray(ControlProtocol.MIN_FRAME_SIZE - 1)
        val poller = ControlPoller(connection, backgroundScope)
        try {
            poller.start()
            runCurrent()

            assertFalse(poller.state.value.polling)
            assertNull(poller.state.value.frame)
        } finally {
            poller.stop()
            connection.stop()
        }
    }

    @Test
    fun `reading uses the recovered control characteristic`() = runTest {
        val connection = connect()
        val poller = ControlPoller(connection, backgroundScope)
        try {
            poller.start()
            runCurrent()

            assertEquals(
                listOf(ControlProtocol.SERVICE_UUID to ControlProtocol.CONTROL_UUID),
                fake.latestSession.reads,
            )
        } finally {
            poller.stop()
            connection.stop()
        }
    }

    @Test
    fun `an unsupported control uuid is not polled`() = runTest {
        fake.services = listOf(
            BleService(
                ControlProtocol.SERVICE_UUID,
                listOf(
                    BleCharacteristic(
                        UUID.randomUUID(),
                        canRead = true,
                        canWrite = false,
                        canNotify = false,
                        canIndicate = false,
                    ),
                ),
            ),
        )
        val connection = BikeConnection(fake, backgroundScope, jitter = { it })
        connection.connect(testTarget(), autoReconnect = false)
        runCurrent()
        val poller = ControlPoller(connection, backgroundScope)
        try {
            poller.start()
            runCurrent()

            assertFalse(poller.state.value.polling)
            assertTrue(fake.latestSession.reads.isEmpty())
        } finally {
            poller.stop()
            connection.stop()
        }
    }
}
