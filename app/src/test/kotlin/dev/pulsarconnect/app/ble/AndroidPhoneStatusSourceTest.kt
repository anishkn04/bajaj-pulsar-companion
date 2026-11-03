package dev.pulsarconnect.app.ble

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.BatteryManager
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import dev.pulsarconnect.core.ble.PhoneStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [26, 35],
    shadows = [AndroidPhoneStatusSourceTest.TestTelephony::class, AndroidPhoneStatusSourceTest.TestSignal::class],
)
class AndroidPhoneStatusSourceTest {
    private lateinit var app: Application
    private lateinit var telephony: TestTelephony

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        telephony = Shadow.extract(app.getSystemService(TelephonyManager::class.java))
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        battery(73)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cold flow reads initial battery only when collected and uses application context`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val activityLikeContext = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? = error("Must use application context")
        }
        val source: PhoneStatusSource = AndroidPhoneStatusSource(activityLikeContext)
        val flow = source.statuses()
        assertTrue(telephony.events.isEmpty())
        battery(81)
        val values = mutableListOf<PhoneStatus>()
        val job = backgroundScope.launch { flow.collect { values += it } }
        runCurrent()
        assertEquals(PhoneStatus(81, null), values.first())
        assertEquals(listOf(PhoneStateListener.LISTEN_SIGNAL_STRENGTHS), telephony.events)
        job.cancelAndJoin()
        assertEquals(PhoneStateListener.LISTEN_NONE, telephony.events.last())
    }

    @Test
    fun `battery refresh validates unavailable values and accepts both boundaries`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        battery(Int.MIN_VALUE)
        val values = mutableListOf<PhoneStatus>()
        val job = backgroundScope.launch { AndroidPhoneStatusSource(app).statuses().collect { values += it } }
        runCurrent()
        assertEquals(PhoneStatus(null, null), values.last())
        for (capacity in listOf(-1, 101, 0, 100, 42)) {
            battery(capacity)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(capacity.takeIf { it in 0..100 }, values.last().batteryPercent)
        }
        job.cancelAndJoin()
        val count = values.size
        battery(12)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(count, values.size)
    }

    @Test
    fun `permission absent leaves signal unknown without registering telephony`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val values = mutableListOf<PhoneStatus>()
        val job = backgroundScope.launch { AndroidPhoneStatusSource(app).statuses().collect { values += it } }
        runCurrent()
        assertEquals(listOf(PhoneStatus(73, null)), values)
        assertTrue(telephony.events.isEmpty())
        job.cancelAndJoin()
        assertTrue(telephony.events.isEmpty())
    }

    @Test
    fun `signal changes preserve battery and validate actual platform level`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val values = mutableListOf<PhoneStatus>()
        val job = backgroundScope.launch { AndroidPhoneStatusSource(app).statuses().collect { values += it } }
        runCurrent()
        for (level in listOf(0, 4, 2, -1, 5)) {
            telephony.emit(level)
            runCurrent()
            assertEquals(PhoneStatus(73, level.takeIf { it in 0..4 }), values.last())
        }
        job.cancelAndJoin()
        assertNull(telephony.listener)
    }

    @Test
    fun `permission revocation clears signal and cleanup tolerates SecurityException`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val values = mutableListOf<PhoneStatus>()
        val job = backgroundScope.launch { AndroidPhoneStatusSource(app).statuses().collect { values += it } }
        runCurrent()
        telephony.emit(3)
        runCurrent()
        assertEquals(3, values.last().signalLevel)
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(values.last().signalLevel)
        telephony.denyAccess = true
        job.cancelAndJoin()
        assertEquals(PhoneStateListener.LISTEN_NONE, telephony.events.last())
    }

    @Test
    fun `registration SecurityException still allows battery refresh`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        telephony.denyAccess = true
        val values = mutableListOf<PhoneStatus>()
        val job = backgroundScope.launch { AndroidPhoneStatusSource(app).statuses().collect { values += it } }
        runCurrent()
        assertEquals(PhoneStatus(73, null), values.last())
        battery(55)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(PhoneStatus(55, null), values.last())
        job.cancelAndJoin()
    }

    @Test
    fun `device without telephony still reports battery`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(app).removeSystemService(Context.TELEPHONY_SERVICE)
        val values = mutableListOf<PhoneStatus>()
        val job = backgroundScope.launch { AndroidPhoneStatusSource(app).statuses().collect { values += it } }
        runCurrent()
        assertEquals(PhoneStatus(73, null), values.last())
        job.cancelAndJoin()
    }

    @Test
    fun `each collection owns and unregisters its listener`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val flow = AndroidPhoneStatusSource(app).statuses()
        repeat(2) {
            val job = backgroundScope.launch { flow.collect() }
            runCurrent()
            job.cancelAndJoin()
        }
        assertEquals(
            listOf(PhoneStateListener.LISTEN_SIGNAL_STRENGTHS, PhoneStateListener.LISTEN_NONE,
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS, PhoneStateListener.LISTEN_NONE),
            telephony.events,
        )
    }

    private fun battery(value: Int) {
        shadowOf(app.getSystemService(BatteryManager::class.java))
            .setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, value)
    }

    @Implements(TelephonyManager::class)
    class TestTelephony {
        val events = mutableListOf<Int>()
        var listener: PhoneStateListener? = null
        var denyAccess = false

        @Implementation
        fun listen(listener: PhoneStateListener, events: Int) {
            this.events += events
            if (denyAccess) throw SecurityException("Permission revoked")
            this.listener = if (events == PhoneStateListener.LISTEN_NONE) null else listener
        }

        fun emit(level: Int) {
            val signal = Shadow.newInstanceOf(SignalStrength::class.java)
            Shadow.extract<TestSignal>(signal).reportedLevel = level
            listener?.onSignalStrengthsChanged(signal)
        }
    }

    @Implements(SignalStrength::class)
    class TestSignal {
        var reportedLevel = 0

        @Implementation
        fun getLevel(): Int = reportedLevel
    }
}
