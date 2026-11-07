package dev.pulsarconnect.core.ble

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BikeConnectionPhoneStatusTest {
    private val fake = FakeBleTransport()
    private val status = PhoneStatus(47, 2)
    private val serviceUuid = UUID.fromString("0010676e-6972-6565-6e69-676e4543544f")
    private val generalUuid = UUID.fromString("0210676e-6972-6565-6e69-676e4543544f")
    private val unrelatedUuid = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")

    private fun characteristic(uuid: UUID = generalUuid, canWrite: Boolean = true) =
        BleCharacteristic(uuid, canRead = true, canWrite = canWrite, canNotify = true, canIndicate = true)

    private fun supportedServices() = listOf(BleService(serviceUuid, listOf(characteristic())))

    private fun TestScope.connection() = BikeConnection(fake, backgroundScope, jitter = { it })

    private fun TestScope.connect(connection: BikeConnection) {
        connection.connect(testTarget())
        runCurrent()
        assertTrue(connection.state.value is ConnectionState.Connected)
    }

    @Test
    fun `supported connected session writes exact idle payload with response`() = runTest {
        fake.services = supportedServices()
        val connection = connection()
        connect(connection)

        assertTrue(connection.sendPhoneStatus(status, 166))

        val write = fake.latestSession.writes.single()
        assertEquals(serviceUuid, write.service)
        assertEquals(generalUuid, write.characteristic)
        assertTrue(write.withResponse)
        assertArrayEquals(PhoneStatusProtocol.encodeIdle(status, 166), write.bytes)
    }

    @Test
    fun `status write negotiates the preferred mtu before writing`() = runTest {
        fake.services = supportedServices()
        val connection = connection()
        connect(connection)
        // The readiness probe already negotiated the payload MTU to reach Connected.
        assertEquals(listOf(PhoneStatusProtocol.PREFERRED_MTU), fake.latestSession.mtuRequests)

        assertTrue(connection.sendPhoneStatus(status, 1))

        assertEquals(2, fake.latestSession.mtuRequests.size)
        assertEquals(1, fake.latestSession.writes.size)
    }

    @Test
    fun `mtu too small for the idle frame fails without writing`() = runTest {
        fake.services = supportedServices()
        val connection = connection()
        connect(connection)
        fake.latestSession.negotiatedMtu = 23

        val error = runCatching { connection.sendPhoneStatus(status, 1) }.exceptionOrNull()

        assertTrue(error is BleException)
        assertEquals(BleFailure.MtuTooSmall(PhoneStatusProtocol.REQUIRED_MTU, 23), (error as BleException).failure)
        assertTrue(fake.latestSession.writes.isEmpty())
    }

    @Test
    fun `mtu negotiation failure propagates without writing`() = runTest {
        fake.services = supportedServices()
        val connection = connection()
        connect(connection)
        val failure = BleException(BleFailure.GattError("requestMtu", 133))
        fake.latestSession.mtuFailure = failure

        val result = runCatching { connection.sendPhoneStatus(status, 1) }

        assertSame(failure, result.exceptionOrNull())
        assertTrue(fake.latestSession.writes.isEmpty())
    }

    @Test
    fun `unrelated GATT and nonwritable GENERAL never receive a write`() = runTest {
        val unsupported = listOf(
            emptyList(),
            listOf(BleService(unrelatedUuid, listOf(characteristic(unrelatedUuid)))),
            listOf(BleService(unrelatedUuid, listOf(characteristic()))),
            listOf(BleService(serviceUuid, listOf(characteristic(unrelatedUuid)))),
            listOf(BleService(serviceUuid, listOf(characteristic(canWrite = false)))),
            listOf(
                BleService(serviceUuid, listOf(characteristic(canWrite = false))),
                BleService(unrelatedUuid, listOf(characteristic())),
            ),
        )
        val connection = connection()
        for (services in unsupported) {
            fake.services = services
            connect(connection)
            assertFalse(connection.sendPhoneStatus(status, 1))
            assertTrue(fake.latestSession.writes.isEmpty())
        }
    }

    @Test
    fun `supported endpoint can coexist with unrelated services and characteristics`() = runTest {
        fake.services = listOf(
            BleService(unrelatedUuid, listOf(characteristic())),
            BleService(serviceUuid, listOf(characteristic(unrelatedUuid), characteristic())),
        )
        val connection = connection()
        connect(connection)
        assertTrue(connection.sendPhoneStatus(status, 1))
        assertEquals(generalUuid, fake.latestSession.writes.single().characteristic)
    }

    @Test
    fun `idle connecting and discovering states do not write`() = runTest {
        fake.services = supportedServices()
        fake.servicesDelayMillis = 1_000
        val connection = connection()
        assertFalse(connection.sendPhoneStatus(status, 1))
        connection.connect(testTarget())
        assertTrue(connection.state.value is ConnectionState.Connecting)
        assertFalse(connection.sendPhoneStatus(status, 1))
        runCurrent()
        assertTrue(connection.state.value is ConnectionState.Discovering)
        assertFalse(connection.sendPhoneStatus(status, 1))
        assertTrue(fake.latestSession.writes.isEmpty())
    }

    @Test
    fun `stop and remote disconnect prevent further writes`() = runTest {
        fake.services = supportedServices()
        val connection = connection()
        connect(connection)
        val stopped = fake.latestSession
        connection.stop()
        assertFalse(connection.sendPhoneStatus(status, 1))
        assertTrue(stopped.writes.isEmpty())

        connect(connection)
        val disconnected = fake.latestSession
        disconnected.disconnect()
        runCurrent()
        assertTrue(connection.state.value is ConnectionState.Reconnecting)
        assertFalse(connection.sendPhoneStatus(status, 1))
        assertTrue(disconnected.writes.isEmpty())
    }

    @Test
    fun `write errors propagate unchanged`() = runTest {
        fake.services = supportedServices()
        val connection = connection()
        connect(connection)
        for (failure in listOf(
            BleException(BleFailure.GattError("write", 133)),
            IllegalStateException("Synthetic write failure"),
        )) {
            fake.latestSession.writeAction = { throw failure }
            val result = runCatching { connection.sendPhoneStatus(status, 1) }
            assertSame(failure, result.exceptionOrNull())
        }
    }

    @Test
    fun `transport cancellation propagates unchanged`() = runTest {
        fake.services = supportedServices()
        val connection = connection()
        connect(connection)
        val cancellation = CancellationException("Synthetic cancellation")
        fake.latestSession.writeAction = { throw cancellation }

        val result = runCatching { connection.sendPhoneStatus(status, 1) }

        assertSame(cancellation, result.exceptionOrNull())
    }

    @Test
    fun `caller cancellation cancels an in flight write`() = runTest {
        fake.services = supportedServices()
        val connection = connection()
        connect(connection)
        val release = CompletableDeferred<Unit>()
        fake.latestSession.writeAction = { release.await() }
        val sending = async { connection.sendPhoneStatus(status, 1) }
        runCurrent()
        assertEquals(1, fake.latestSession.writes.size)

        sending.cancel()
        runCurrent()

        assertTrue(sending.isCancelled)
        assertTrue(connection.state.value is ConnectionState.Connected)
    }

    @Test
    fun `disconnect during a suspended write cannot report success`() = runTest {
        fake.services = supportedServices()
        val connection = connection()
        connect(connection)
        val old = fake.latestSession
        val release = CompletableDeferred<Unit>()
        old.writeAction = { release.await() }
        val sending = async { connection.sendPhoneStatus(status, 1) }
        runCurrent()
        assertEquals(1, old.writes.size)

        old.disconnect()
        runCurrent()
        release.complete(Unit)
        runCurrent()

        assertFalse(sending.await())
        assertTrue(old.closed)
        assertFalse(connection.sendPhoneStatus(status, 2))
    }

    @Test
    fun `stop during a suspended write cannot report success`() = runTest {
        fake.services = supportedServices()
        val connection = connection()
        connect(connection)
        val old = fake.latestSession
        val release = CompletableDeferred<Unit>()
        old.writeAction = { release.await() }
        val sending = async { connection.sendPhoneStatus(status, 1) }
        runCurrent()

        connection.stop()
        release.complete(Unit)
        runCurrent()

        assertFalse(sending.await())
        assertTrue(old.closed)
        assertEquals(1, old.writes.size)
    }

    @Test
    fun `in flight write stays on captured session and replacement completion is stale`() = runTest {
        fake.services = supportedServices()
        val connection = connection()
        connect(connection)
        val old = fake.latestSession
        val release = CompletableDeferred<Unit>()
        old.writeAction = { release.await() }
        val sending = async { connection.sendPhoneStatus(status, 1) }
        runCurrent()
        assertEquals(1, old.writes.size)

        // Even reconnecting the same target is a different session and generation.
        connect(connection)
        val replacement = fake.latestSession
        assertTrue(old.closed)
        assertTrue(replacement.writes.isEmpty())
        release.complete(Unit)
        runCurrent()

        assertFalse(sending.await())
        assertEquals(1, old.writes.size)
        assertTrue(replacement.writes.isEmpty())
        assertTrue(connection.sendPhoneStatus(status, 2))
        assertEquals(1, replacement.writes.size)
        assertFalse(replacement.closed)
    }

    @Test
    fun `automatic reconnect also invalidates an old write without changing run generation`() = runTest {
        fake.services = supportedServices()
        val connection = connection()
        connect(connection)
        val old = fake.latestSession
        val release = CompletableDeferred<Unit>()
        old.writeAction = { release.await() }
        val sending = async { connection.sendPhoneStatus(status, 1) }
        runCurrent()

        old.disconnect()
        runCurrent()
        testScheduler.advanceTimeBy(1_000)
        runCurrent()
        val replacement = fake.latestSession
        assertTrue(replacement !== old)
        assertTrue(connection.state.value is ConnectionState.Connected)
        release.complete(Unit)
        runCurrent()

        assertFalse(sending.await())
        assertTrue(replacement.writes.isEmpty())
        assertFalse(replacement.closed)
    }

    @Test
    fun `replacement capabilities are checked anew rather than inherited`() = runTest {
        fake.services = supportedServices()
        val connection = connection()
        connect(connection)
        assertTrue(connection.sendPhoneStatus(status, 1))
        val old = fake.latestSession
        fake.services = listOf(BleService(unrelatedUuid, listOf(characteristic())))

        connect(connection)

        assertFalse(connection.sendPhoneStatus(status, 2))
        assertEquals(1, old.writes.size)
        assertTrue(fake.latestSession.writes.isEmpty())
    }
}
