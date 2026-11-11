package dev.pulsarconnect.core.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BikeLinkControllerTest {

    private val vin = "MD2ABCDEFGH123456"
    private val otherVin = "MD2ABCDEFGH654321"
    private val target = testTarget(vin = vin)
    private val fake = FakeBleTransport()
    private val devices = InMemoryBikeDeviceStore()
    private val statuses = MutableStateFlow(PhoneStatus(50, 2))

    private fun kotlinx.coroutines.test.TestScope.controller(
        preferences: BikeLinkPreferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(mirroring = false, keepInBackground = false, startOnBoot = false),
        ),
    ): Pair<BikeLinkController, BikeConnection> {
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
        fake.readResponse = ByteArray(ControlProtocol.RESPONSE_SIZE)
        val connection = BikeConnection(fake, backgroundScope, jitter = { it })
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = preferences,
            statuses = statuses,
            scope = backgroundScope,
        )
        return controller to connection
    }

    private fun writes(): Int = fake.latestSession.writes.size

    @Test
    fun `nothing connects or sends until the rider opts in`() = runTest {
        devices.select(target)
        val (controller, _) = controller()

        controller.onSession(vin)
        runCurrent()

        assertEquals(0, fake.connectCalls)
        assertTrue(controller.state.value.connection is ConnectionState.Idle)
        assertFalse(controller.state.value.settings.mirroring)
        assertFalse(controller.state.value.mirroringActive)
    }

    @Test
    fun `enabling mirroring connects the selected bike and starts sending`() = runTest {
        devices.select(target)
        val (controller, _) = controller()
        controller.onSession(vin)

        controller.setMirroring(true)
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(1, fake.connectCalls)
        assertTrue(controller.state.value.connection is ConnectionState.Connected)
        assertTrue(controller.state.value.mirroringActive)
        assertTrue(writes() >= 2)
        assertTrue(controller.controlState.value.polling)
    }

    @Test
    fun `enabling mirroring without a selected bike stays armed but disconnected`() = runTest {
        val (controller, _) = controller()
        controller.onSession(vin)

        controller.setMirroring(true)
        runCurrent()

        assertEquals(0, fake.connectCalls)
        assertTrue(controller.state.value.settings.mirroring)
        assertFalse(controller.state.value.mirroringActive)
    }

    @Test
    fun `background mode keeps the link alive`() = runTest {
        devices.select(target)
        val (controller, _) = controller()
        controller.onSession(vin)
        controller.setMirroring(true)
        controller.setKeepInBackground(true)
        advanceTimeBy(1_001)
        runCurrent()

        controller.onBackground()
        runCurrent()

        assertTrue(controller.state.value.connection is ConnectionState.Connected)
        assertTrue(controller.state.value.mirroringActive)
    }

    @Test
    fun `background mode keeps waiting after the first connect failure`() = runTest {
        devices.select(target)
        fake.connectFailure = BleException(BleFailure.GattError("connect", 133))
        val preferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(mirroring = true, keepInBackground = true),
        )
        val (controller, _) = controller(preferences)

        controller.onSession(vin)
        runCurrent()

        assertTrue(controller.state.value.connection is ConnectionState.WaitingForBike)
        assertEquals(listOf(true), fake.passiveCalls)

        fake.connectFailure = null
        advanceTimeBy(BikeConnection.DEFAULT_WAITING_RETRY_DELAYS.first() + 1)
        runCurrent()

        assertTrue(controller.state.value.connection is ConnectionState.Connected)
        assertTrue(controller.state.value.mirroringActive)
    }

    @Test
    fun `the background service adopts a passive wait after a process restart`() = runTest {
        devices.select(target)
        fake.connectFailure = BleException(BleFailure.GattError("connect", 133))
        val preferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(
                mirroring = true,
                keepInBackground = true,
                startOnBoot = true,
                armedVin = vin,
            ),
        )
        val (controller, _) = controller(preferences)

        controller.ensureRunning()
        runCurrent()

        assertTrue(controller.state.value.connection is ConnectionState.WaitingForBike)
        assertEquals(listOf(true), fake.passiveCalls)

        fake.connectFailure = null
        advanceTimeBy(BikeConnection.DEFAULT_WAITING_RETRY_DELAYS.first() + 1)
        runCurrent()

        assertTrue(controller.state.value.connection is ConnectionState.Connected)
    }

    @Test
    fun `turning background mode off stops waiting and reports the failure`() = runTest {
        devices.select(target)
        fake.connectFailure = BleException(BleFailure.GattError("connect", 133))
        val preferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(mirroring = true, keepInBackground = true),
        )
        val (controller, _) = controller(preferences)
        controller.onSession(vin)
        runCurrent()
        assertTrue(controller.state.value.connection is ConnectionState.WaitingForBike)

        controller.setKeepInBackground(false)
        runCurrent()

        assertTrue(controller.state.value.connection is ConnectionState.Failed)
        assertFalse(fake.passiveCalls.last())
    }

    @Test
    fun `signing out stops waiting for the bike`() = runTest {
        devices.select(target)
        fake.connectFailure = BleException(BleFailure.GattError("connect", 133))
        val preferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(mirroring = true, keepInBackground = true),
        )
        val (controller, _) = controller(preferences)
        controller.onSession(vin)
        runCurrent()
        assertTrue(controller.state.value.connection is ConnectionState.WaitingForBike)

        controller.signOut()
        runCurrent()

        assertEquals(ConnectionState.Idle, controller.state.value.connection)
        assertNull(preferences.settings.value.armedVin)
    }

    @Test
    fun `without background mode leaving the app stops the link but keeps the intent`() = runTest {
        devices.select(target)
        val (controller, _) = controller()
        controller.onSession(vin)
        controller.setMirroring(true)
        runCurrent()

        controller.onBackground()
        runCurrent()

        assertEquals(ConnectionState.Idle, controller.state.value.connection)
        assertFalse(controller.state.value.mirroringActive)
        assertTrue(controller.state.value.settings.mirroring)
    }

    @Test
    fun `a remote disconnect keeps mirroring armed and resumes on the next link`() = runTest {
        devices.select(target)
        val (controller, _) = controller()
        controller.onSession(vin)
        controller.setMirroring(true)
        runCurrent()
        assertEquals(1, fake.connectCalls)

        fake.latestSession.disconnect()
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(2, fake.connectCalls)
        assertTrue(controller.state.value.connection is ConnectionState.Connected)
        assertTrue(controller.state.value.settings.mirroring)
        assertTrue(controller.state.value.mirroringActive)
    }

    @Test
    fun `switching vin disarms mirroring and closes the old bike`() = runTest {
        devices.select(target)
        val (controller, _) = controller()
        controller.onSession(vin)
        controller.setMirroring(true)
        runCurrent()
        val oldSession = fake.latestSession

        controller.onSession(otherVin)
        runCurrent()

        assertFalse(controller.state.value.settings.mirroring)
        assertEquals(ConnectionState.Idle, controller.state.value.connection)
        assertTrue(oldSession.closed)
        assertEquals(otherVin, controller.state.value.vin)
    }

    @Test
    fun `sign out disarms mirroring and clears the armed vin`() = runTest {
        devices.select(target)
        val preferences = InMemoryBikeLinkPreferences()
        val (controller, _) = controller(preferences)
        controller.onSession(vin)
        controller.setMirroring(true)
        controller.setKeepInBackground(true)
        runCurrent()

        controller.signOut()
        runCurrent()

        assertNull(controller.state.value.vin)
        assertFalse(preferences.settings.value.mirroring)
        assertNull(preferences.settings.value.armedVin)
        assertEquals(ConnectionState.Idle, controller.state.value.connection)
    }

    @Test
    fun `disconnect turns mirroring off`() = runTest {
        devices.select(target)
        val (controller, _) = controller()
        controller.onSession(vin)
        controller.setMirroring(true)
        runCurrent()

        controller.disconnect()
        runCurrent()

        assertFalse(controller.state.value.settings.mirroring)
        assertEquals(ConnectionState.Idle, controller.state.value.connection)
        assertFalse(controller.state.value.mirroringActive)
    }

    @Test
    fun `a write failure stops sending but leaves mirroring armed for the next link`() = runTest {
        devices.select(target)
        val (controller, _) = controller()
        controller.onSession(vin)
        controller.setMirroring(true)
        runCurrent()
        fake.latestSession.writeAction = {
            throw BleException(BleFailure.GattError("writeCharacteristic", 133))
        }
        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(controller.state.value.mirroringActive)
        assertTrue(controller.state.value.settings.mirroring)
        assertEquals(BikeLinkController.MIRRORING_STOPPED_MESSAGE, controller.state.value.mirroringError)
    }

    @Test
    fun `foreground retries the last target after a terminal failure`() = runTest {
        devices.select(target)
        devices.setAutoConnect(vin, true)
        fake.connectFailure = BleException(BleFailure.GattError("connect", 133))
        val (controller, _) = controller()
        controller.onSession(vin)
        runCurrent()
        assertTrue(controller.state.value.connection is ConnectionState.Failed)
        assertEquals(1, fake.connectCalls)

        fake.connectFailure = null
        controller.onForeground()
        runCurrent()

        assertEquals(2, fake.connectCalls)
        assertTrue(controller.state.value.connection is ConnectionState.Connected)
    }

    @Test
    fun `foreground does not revive a failure after the rider turns auto connect off`() = runTest {
        devices.select(target)
        devices.setAutoConnect(vin, true)
        fake.connectFailure = BleException(BleFailure.GattError("connect", 133))
        val (controller, _) = controller()
        controller.onSession(vin)
        runCurrent()
        assertTrue(controller.state.value.connection is ConnectionState.Failed)

        controller.setAutoConnect(false)
        controller.onForeground()
        runCurrent()

        assertEquals(1, fake.connectCalls)
        assertTrue(controller.state.value.connection is ConnectionState.Failed)
    }

    @Test
    fun `turning mirroring back on retries a failed link`() = runTest {
        devices.select(target)
        fake.connectFailure = BleException(BleFailure.GattError("connect", 133))
        val (controller, _) = controller()
        controller.onSession(vin)
        controller.connect()
        runCurrent()
        assertTrue(controller.state.value.connection is ConnectionState.Failed)
        assertEquals(1, fake.connectCalls)

        fake.connectFailure = null
        controller.setMirroring(true)
        runCurrent()

        assertEquals(2, fake.connectCalls)
        assertTrue(controller.state.value.connection is ConnectionState.Connected)
    }

    @Test
    fun `disabling mirroring stops automatic reconnects`() = runTest {
        devices.select(target)
        val (controller, _) = controller()
        controller.onSession(vin)
        controller.setMirroring(true)
        runCurrent()
        assertEquals(1, fake.connectCalls)

        controller.setMirroring(false)
        runCurrent()
        fake.latestSession.disconnect()
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(1, fake.connectCalls)
        assertTrue(controller.state.value.connection is ConnectionState.Failed)
        assertFalse(controller.state.value.mirroringActive)
    }

    @Test
    fun `a successful reconnect clears a previous mirroring error`() = runTest {
        devices.select(target)
        val (controller, _) = controller()
        controller.onSession(vin)
        controller.setMirroring(true)
        runCurrent()
        fake.latestSession.writeAction = {
            throw BleException(BleFailure.GattError("writeCharacteristic", 133))
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(BikeLinkController.MIRRORING_STOPPED_MESSAGE, controller.state.value.mirroringError)

        fake.latestSession.writeAction = null
        fake.latestSession.disconnect()
        advanceTimeBy(1_001)
        runCurrent()

        assertTrue(controller.state.value.connection is ConnectionState.Connected)
        assertTrue(controller.state.value.mirroringActive)
        assertNull(controller.state.value.mirroringError)
    }

    @Test
    fun `mirroring and polling wait for the first confirmed status write`() = runTest {
        devices.select(target)
        val gate = CompletableDeferred<Unit>()
        fake.writeAction = { gate.await() }
        val (controller, _) = controller()
        controller.onSession(vin)
        controller.setMirroring(true)
        runCurrent()

        assertTrue(controller.state.value.connection is ConnectionState.Connected)
        assertFalse(controller.state.value.mirroringActive)
        assertEquals(1, writes())
        assertTrue(fake.latestSession.reads.isEmpty())
        assertFalse(controller.controlState.value.polling)

        gate.complete(Unit)
        runCurrent()

        assertTrue(controller.state.value.mirroringActive)
        assertNull(controller.state.value.mirroringError)
        assertTrue(fake.latestSession.reads.isNotEmpty())
        assertTrue(controller.controlState.value.polling)
    }

    @Test
    fun `a previous mirroring error survives reconnect until the next write succeeds`() = runTest {
        devices.select(target)
        val (controller, _) = controller()
        controller.onSession(vin)
        controller.setMirroring(true)
        runCurrent()
        fake.latestSession.writeAction = {
            throw BleException(BleFailure.GattError("writeCharacteristic", 133))
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(BikeLinkController.MIRRORING_STOPPED_MESSAGE, controller.state.value.mirroringError)

        val gate = CompletableDeferred<Unit>()
        fake.writeAction = { gate.await() }
        fake.latestSession.writeAction = null
        fake.latestSession.disconnect()
        advanceTimeBy(1_001)
        runCurrent()

        assertTrue(controller.state.value.connection is ConnectionState.Connected)
        assertEquals(BikeLinkController.MIRRORING_STOPPED_MESSAGE, controller.state.value.mirroringError)
        assertFalse(controller.state.value.mirroringActive)
        assertFalse(controller.controlState.value.polling)

        gate.complete(Unit)
        runCurrent()

        assertTrue(controller.state.value.mirroringActive)
        assertNull(controller.state.value.mirroringError)
        assertTrue(controller.controlState.value.polling)
    }

    @Test
    fun `ensure running adopts the armed vin after a process restart`() = runTest {
        devices.select(target)
        val preferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(
                mirroring = true,
                keepInBackground = true,
                startOnBoot = true,
                armedVin = vin,
            ),
        )
        val (controller, _) = controller(preferences)

        controller.ensureRunning()
        runCurrent()

        assertEquals(vin, controller.state.value.vin)
        assertEquals(1, fake.connectCalls)
        assertTrue(controller.state.value.settings.mirroring)
        assertTrue(controller.state.value.mirroringActive)
    }

    @Test
    fun `a saved mirroring preference survives the first sign in of a new process`() = runTest {
        devices.select(target)
        val preferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(
                mirroring = true,
                keepInBackground = true,
                startOnBoot = true,
                armedVin = vin,
            ),
        )
        val (controller, _) = controller(preferences)

        controller.onSession(vin)
        runCurrent()

        assertTrue(preferences.settings.value.mirroring)
        assertEquals(vin, controller.state.value.vin)
        assertTrue(controller.state.value.mirroringActive)
    }

    @Test
    fun `ensure running does nothing when mirroring is off`() = runTest {
        devices.select(target)
        val preferences = InMemoryBikeLinkPreferences(
            BikeLinkSettings(mirroring = false, armedVin = vin),
        )
        val (controller, _) = controller(preferences)

        controller.ensureRunning()
        runCurrent()

        assertEquals(0, fake.connectCalls)
        assertTrue(controller.state.value.connection is ConnectionState.Idle)
    }

    @Test
    fun `auto connect alone keeps the existing foreground behaviour`() = runTest {
        devices.select(target)
        devices.setAutoConnect(vin, true)
        val (controller, _) = controller()
        controller.onSession(vin)
        controller.onForeground()
        runCurrent()

        assertEquals(1, fake.connectCalls)
        assertTrue(controller.state.value.connection is ConnectionState.Connected)
        assertFalse(controller.state.value.mirroringActive)
    }

    @Test
    fun `control polling stops when mirroring is disabled`() = runTest {
        devices.select(target)
        val (controller, _) = controller()
        controller.onSession(vin)
        controller.setMirroring(true)
        runCurrent()
        assertTrue(controller.controlState.value.polling)

        controller.setMirroring(false)
        runCurrent()

        assertFalse(controller.controlState.value.polling)
    }
}
