package dev.pulsarconnect.app.ble

import dev.pulsarconnect.core.ble.AdapterState
import dev.pulsarconnect.core.ble.BikeConnection
import dev.pulsarconnect.core.ble.BikeLinkController
import dev.pulsarconnect.core.ble.BikeLinkPreferences
import dev.pulsarconnect.core.ble.BikeLinkSettings
import dev.pulsarconnect.core.ble.BikeScanner
import dev.pulsarconnect.core.ble.BikeTarget
import dev.pulsarconnect.core.ble.BleScanSource
import dev.pulsarconnect.core.ble.BleSession
import dev.pulsarconnect.core.ble.BleTransport
import dev.pulsarconnect.core.ble.DiscoveredDevice
import dev.pulsarconnect.core.ble.InMemoryBikeDeviceStore
import dev.pulsarconnect.core.ble.InMemoryBikeLinkPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BleViewModelSettingsTest {

    private val dispatcher = StandardTestDispatcher()
    private val vin = "MD2ABCDEFGH123456"
    private val target = BikeTarget("AA:BB:CC:DD:EE:FF", "Pulsar", vin)
    private val devices = InMemoryBikeDeviceStore()
    private val transport = SupportedTransport()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        preferences: BikeLinkPreferences,
        runtime: BikeLinkRuntime,
    ): BleViewModel {
        val scope = CoroutineScope(dispatcher)
        val connection = BikeConnection(transport, scope, jitter = { it })
        val controller = BikeLinkController(
            devices = devices,
            connection = connection,
            preferences = preferences,
            statuses = flowOf(),
            scope = scope,
        )
        return BleViewModel(
            application = org.robolectric.RuntimeEnvironment.getApplication(),
            devices = devices,
            scanner = BikeScanner(BleScanSource { flowOf() }),
            transport = transport,
            controller = controller,
            runtime = runtime,
        )
    }

    @Test
    fun `defaults are on`() = runTest {
        val preferences = InMemoryBikeLinkPreferences()
        assertFalse(preferences.settings.value.resumesAfterBoot)

        val viewModel = viewModel(preferences, FakeRuntime())
        viewModel.onSession(vin)
        runCurrent()

        assertTrue(viewModel.state.value.phoneStatusEnabled)
        assertTrue(viewModel.state.value.settings.keepInBackground)
        assertTrue(viewModel.state.value.settings.startOnBoot)
        assertTrue(preferences.settings.value.mirroring)
        assertTrue(preferences.settings.value.keepInBackground)
        assertTrue(preferences.settings.value.startOnBoot)
        assertTrue(preferences.settings.value.runsInBackground)
        assertTrue(preferences.settings.value.resumesAfterBoot)
        assertEquals(vin, preferences.settings.value.armedVin)
    }

    @Test
    fun `turning background mode off stops the service but keeps mirroring armed`() =
        runTest {
            devices.select(target)
            val preferences = InMemoryBikeLinkPreferences()
            val runtime = RecordingRuntime()
            val viewModel = viewModel(preferences, runtime)
            viewModel.onSession(vin)
            runCurrent()

            assertFalse(runtime.isEnabled)

            viewModel.setKeepInBackground(false)
            runCurrent()

            assertFalse(runtime.isEnabled)
            assertTrue(preferences.settings.value.mirroring)
            assertFalse(preferences.settings.value.runsInBackground)
            assertEquals(
                BikeLinkSettings(
                    mirroring = true,
                    keepInBackground = false,
                    startOnBoot = true,
                    armedVin = vin,
                ),
                preferences.settings.value,
            )
        }

    @Test
    fun `manual disconnect disarms mirroring and stops the service`() = runTest {
        devices.select(target)
        val preferences = InMemoryBikeLinkPreferences()
        val runtime = RecordingRuntime()
        val viewModel = viewModel(preferences, runtime)
        viewModel.onSession(vin)
        runCurrent()

        assertFalse(runtime.isEnabled)

        viewModel.disconnect()
        runCurrent()

        assertFalse(viewModel.state.value.phoneStatusEnabled)
        assertFalse(preferences.settings.value.mirroring)
        assertFalse(runtime.isEnabled)
    }

    @Test
    fun `boot resume is only promised when a background link is armed`() = runTest {
        val preferences = InMemoryBikeLinkPreferences()
        val viewModel = viewModel(preferences, FakeRuntime())
        viewModel.onSession(vin)
        runCurrent()

        // resumesAfterBoot requires armedVin (set by onSession) + mirroring + background
        assertTrue(preferences.settings.value.resumesAfterBoot)

        viewModel.setStartOnBoot(false)
        runCurrent()
        assertFalse(preferences.settings.value.resumesAfterBoot)

        viewModel.setStartOnBoot(true)
        runCurrent()
        assertTrue(preferences.settings.value.resumesAfterBoot)

        // Without background mode, resumesAfterBoot is false even with startOnBoot + armedVin
        viewModel.setKeepInBackground(false)
        runCurrent()
        assertFalse(preferences.settings.value.resumesAfterBoot)
    }

    private class FakeRuntime : BikeLinkRuntime {
        var enabled = false
            private set

        override fun setEnabled(enabled: Boolean) {
            this.enabled = enabled
        }
    }

    /** Records setEnabled calls for assertions. */
    private class RecordingRuntime : BikeLinkRuntime {
        var isEnabled = false
            private set
        val calls = mutableListOf<Boolean>()

        override fun setEnabled(active: Boolean) {
            isEnabled = active
            calls += active
        }
    }

    private class SupportedTransport : BleTransport {
        override val adapterState = MutableStateFlow(AdapterState.ON)

        override fun missingPermissions(): Set<dev.pulsarconnect.core.ble.BlePermission> = emptySet()

        override suspend fun connect(
            target: dev.pulsarconnect.core.ble.BikeTarget,
        ): BleSession = SupportedSession()

        private class SupportedSession : BleSession {
            override val events = kotlinx.coroutines.flow.MutableSharedFlow<dev.pulsarconnect.core.ble.BleSessionEvent>()

            override suspend fun services(): List<dev.pulsarconnect.core.ble.BleService> = emptyList()

            override suspend fun setNotifications(service: UUID, characteristic: UUID, enabled: Boolean) = Unit

            override suspend fun read(service: UUID, characteristic: UUID): ByteArray = ByteArray(0)

            override suspend fun requestMtu(mtu: Int): Int = mtu

            override suspend fun write(
                service: UUID,
                characteristic: UUID,
                bytes: ByteArray,
                withResponse: Boolean,
            ) = Unit

            override fun close() {}
        }
    }
}
