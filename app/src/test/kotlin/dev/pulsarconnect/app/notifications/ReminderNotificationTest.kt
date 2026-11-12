package dev.pulsarconnect.app.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Looper
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.pulsarconnect.app.MainActivity
import dev.pulsarconnect.app.PulsarConnectApplication
import dev.pulsarconnect.core.data.InMemoryReminderRepository
import dev.pulsarconnect.core.data.InMemoryTripRepository
import dev.pulsarconnect.core.data.ReminderRepository
import dev.pulsarconnect.core.data.TripRepository
import dev.pulsarconnect.core.model.Credentials
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.model.Session
import dev.pulsarconnect.core.model.StoredSession
import dev.pulsarconnect.core.model.Vin
import dev.pulsarconnect.core.session.SecretStore
import dev.pulsarconnect.core.session.SessionRepository
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers


@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 35])
class ReminderNotificationTest {
    private lateinit var context: Context
    private lateinit var notifications: NotificationManager
    private lateinit var manager: ReminderNotificationManager
    private val vin = "MD2ABCDEFGH123456"
    private val otherVin = "MD2ABCDEFGH654321"
    private val trips = InMemoryTripRepository()
    private val reminders = InMemoryReminderRepository()
    private var sessionReads = 0
    private var storageFailure = false
    private val secrets = object : SecretStore {
        var value: String? = null
        override fun read(key: String): String? {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Session IO on main" }
            sessionReads++
            if (storageFailure) throw IOException("temporary storage failure")
            return value
        }
        override fun write(key: String, value: String) { this.value = value }
        override fun delete(key: String) { value = null }
    }
    private val sessions = SessionRepository(secrets)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("service_reminder_notifications", Context.MODE_PRIVATE).edit().clear().commit()
        notifications = context.getSystemService(NotificationManager::class.java)
        notifications.cancelAll()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters): ListenableWorker =
                    object : androidx.work.Worker(appContext, params) {
                        override fun doWork() = Result.success()
                    }
            }).build())
        grantPermission()
        sessions.save(StoredSession(Credentials(Vin.of(vin), "private-code"), Session("private-token", null, Instant.EPOCH)))
        manager = controller()
        manager.ensureChannel()
    }

    private fun controller(tripRepo: TripRepository = trips, reminderRepo: ReminderRepository = reminders) =
        ReminderNotificationManager(context, sessions, tripRepo, reminderRepo)

    private fun grantPermission() {
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(true)
    }

    private suspend fun due(vin: String = this.vin, odometer: Double? = null, date: Long? = 1) = reminders.addReminder(
        ServiceReminder(vin = vin, title = "Private oil service", description = "Private location",
            dueOdometerKm = odometer, dueDateMillis = date, createdAtMillis = 0),
    )

    private fun worker(controller: ReminderNotificationManager = manager, attempt: Int = 0): ReminderCheckWorker =
        TestListenableWorkerBuilder<ReminderCheckWorker>(context).setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters) =
                    ReminderCheckWorker(appContext, params, controller)
            }).build()

    @Test fun `application startup creates channel and reconciles without loading keystore or database`() {
        manager.setEnabled(true)
        val app = PulsarConnectApplication()
        shadowOf(app).callAttach(context)
        app.onCreate()
        assertTrue(app.container.reminderNotifications.enabled)
        assertNotNull(notifications.getNotificationChannel(ReminderNotificationManager.CHANNEL_ID))
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(ReminderNotificationManager.PERIODIC_WORK)
            .get(10, TimeUnit.SECONDS).any { !it.state.isFinished })
        assertEquals(0, sessionReads)
        app.container.database.close()
    }

    @Test fun `application startup preserves persisted sign out pause despite stale credentials`() = runBlocking {
        due()
        manager.setEnabled(true)
        manager.onSignedOut()
        val original = context.getSharedPreferences("service_reminder_notifications", Context.MODE_PRIVATE)
        assertTrue(original.edit().commit())
        ReflectionHelpers.getStaticField<MutableMap<*, *>>(
            Class.forName("android.app.ContextImpl"), "sSharedPrefsCache",
        ).clear()

        val app = PulsarConnectApplication()
        shadowOf(app).callAttach(context)
        try {
            app.onCreate()
            val reloaded = context.getSharedPreferences("service_reminder_notifications", Context.MODE_PRIVATE)
            assertNotSame(original, reloaded)
            assertTrue(reloaded.getBoolean("paused", false))
            assertTrue(app.container.reminderNotifications.enabled)
            val work = WorkManager.getInstance(context)
            for (name in listOf(ReminderNotificationManager.PERIODIC_WORK, ReminderNotificationManager.IMMEDIATE_WORK)) {
                assertTrue(work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
            }
            assertEquals(ListenableWorker.Result.success(), worker(controller()).doWork())
            assertEquals(0, sessionReads)
            assertTrue(notifications.activeNotifications.isEmpty())
        } finally {
            app.container.database.close()
        }
    }

    @Test fun `confirmed signed in refresh clears pause while disabled so explicit enable schedules`() = runBlocking {
        due()
        manager.onSignedOut()
        assertFalse(manager.enabled)
        manager.refresh()
        val preferences = context.getSharedPreferences("service_reminder_notifications", Context.MODE_PRIVATE)
        assertFalse(preferences.getBoolean("paused", true))
        val work = WorkManager.getInstance(context)
        for (name in listOf(ReminderNotificationManager.PERIODIC_WORK, ReminderNotificationManager.IMMEDIATE_WORK)) {
            assertTrue(work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
        }
        assertEquals(0, sessionReads)

        manager.setEnabled(true)
        assertTrue(work.getWorkInfosForUniqueWork(ReminderNotificationManager.PERIODIC_WORK)
            .get(10, TimeUnit.SECONDS).any { !it.state.isFinished })
        assertTrue(work.getWorkInfosForUniqueWork(ReminderNotificationManager.IMMEDIATE_WORK)
            .get(10, TimeUnit.SECONDS).isNotEmpty())
        assertEquals(0, sessionReads)
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(1, notifications.activeNotifications.size)
    }

    @Test fun `application startup cancels stale scheduled work while paused or disabled`() {
        val preferences = context.getSharedPreferences("service_reminder_notifications", Context.MODE_PRIVATE)
        val work = WorkManager.getInstance(context)
        for (paused in listOf(true, false)) {
            manager.refresh()
            manager.setEnabled(true)
            // Model work left behind when a process died before cancellation completed.
            preferences.edit().putBoolean("paused", paused).putBoolean("enabled", paused).apply()
            val app = PulsarConnectApplication()
            shadowOf(app).callAttach(context)
            try {
                app.onCreate()
                assertEquals(paused, preferences.getBoolean("paused", false))
                for (name in listOf(ReminderNotificationManager.PERIODIC_WORK, ReminderNotificationManager.IMMEDIATE_WORK)) {
                    assertTrue(work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
                }
                assertEquals(0, sessionReads)
            } finally {
                app.container.database.close()
            }
        }
    }

    @Test fun `default disabled and no op never read session or deliver`() = runBlocking {
        due()
        assertFalse(manager.enabled)
        manager.refresh()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, sessionReads)
        assertTrue(notifications.activeNotifications.isEmpty())
        NoOpReminderNotifications.setEnabled(true)
        NoOpReminderNotifications.refresh()
        NoOpReminderNotifications.onSignedOut()
        NoOpReminderNotifications.onReminderCompleted(1)
        assertFalse(NoOpReminderNotifications.enabled)
    }

    @Test fun `date works without odometer and all content and intent are private generic`() = runBlocking {
        val reminder = due()
        due(odometer = 100.0, date = null)
        due(vin = otherVin)
        manager.setEnabled(true)
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        val posted = notifications.activeNotifications.single()
        assertEquals(ReminderNotificationManager.tag(reminder.id!!), posted.tag)
        val n = posted.notification
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Service reminder", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Open Pulsar Connect to review your service reminders.", n.extras.getString(Notification.EXTRA_TEXT))
        assertNotNull(n.publicVersion)
        assertEquals("Service reminder", n.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(shadowOf(n.contentIntent).isImmutable)
        val intent = shadowOf(n.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertNull(intent.extras)
        assertNull(intent.data)
        n.contentIntent.send()
        assertEquals(MainActivity::class.java.name, shadowOf(context as Application).nextStartedActivity.component!!.className)
        assertFalse(n.extras.toString().contains(vin))
        assertFalse(n.extras.toString().contains("Private"))
    }

    @Test fun `ledger survives new controller dismissal and opt out without re alert`() = runBlocking {
        due()
        manager.setEnabled(true)
        worker().doWork()
        notifications.cancelAll() // User dismisses; delivery is still remembered.
        val restored = controller()
        assertTrue(restored.enabled)
        worker(restored).doWork()
        assertTrue(notifications.activeNotifications.isEmpty())
        restored.setEnabled(false)
        restored.setEnabled(true)
        worker(restored).doWork()
        assertTrue(notifications.activeNotifications.isEmpty())
        val prefs = context.getSharedPreferences("service_reminder_notifications", Context.MODE_PRIVATE)
        assertFalse(prefs.all.toString().contains(vin))
        assertFalse(prefs.all.toString().contains("private-token"))
    }

    @Test fun `process restart reloads the delivery ledger from disk`() = runBlocking {
        val reminder = due()
        manager.setEnabled(true)
        worker().doWork()
        notifications.cancelAll()
        val original = context.getSharedPreferences("service_reminder_notifications", Context.MODE_PRIVATE)
        // Flush asynchronous writes, then discard Android's process-local prefs cache.
        assertTrue(original.edit().commit())
        ReflectionHelpers.getStaticField<MutableMap<*, *>>(
            Class.forName("android.app.ContextImpl"), "sSharedPrefsCache",
        ).clear()
        val reloaded = context.getSharedPreferences("service_reminder_notifications", Context.MODE_PRIVATE)
        assertNotSame(original, reloaded)
        assertEquals(setOf(reminder.id.toString()), reloaded.getStringSet("delivered_ids", emptySet()))
        val restarted = controller()
        assertTrue(restarted.enabled)
        restarted.refresh()
        worker(restarted).doWork()
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun `active trip odometer takes priority then latest ended reading`() = runBlocking {
        due(odometer = 200.0, date = null)
        val first = trips.startTrip(vin, 100.0, null, null)
        trips.endTrip(first, 250.0, null, null)
        val active = trips.startTrip(vin, 150.0, null, null)
        manager.setEnabled(true)
        worker().doWork()
        assertTrue(notifications.activeNotifications.isEmpty())
        trips.endTrip(active, 200.0, null, null)
        worker().doWork()
        assertEquals(1, notifications.activeNotifications.size)
    }

    @Test fun `missing session and different VIN never deliver`() = runBlocking {
        due(otherVin)
        manager.setEnabled(true)
        worker().doWork()
        assertTrue(notifications.activeNotifications.isEmpty())
        due()
        sessions.clear()
        worker().doWork()
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun `global disabled does not mark and later enable delivers`() = runBlocking {
        due()
        manager.setEnabled(true)
        shadowOf(notifications).setNotificationsEnabled(false)
        worker().doWork()
        assertTrue(notifications.activeNotifications.isEmpty())
        grantPermission()
        worker().doWork()
        assertEquals(1, notifications.activeNotifications.size)
    }

    @Test fun `channel blocked does not mark and later enable delivers`() = runBlocking {
        due()
        manager.setEnabled(true)
        val channel = notifications.getNotificationChannel(ReminderNotificationManager.CHANNEL_ID)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        worker().doWork()
        assertTrue(notifications.activeNotifications.isEmpty())
        // Mutate the shadow's stored channel to model a user action in Settings.
        channel.importance = NotificationManager.IMPORTANCE_DEFAULT
        worker().doWork()
        assertEquals(1, notifications.activeNotifications.size)
    }

    @Test
    @Config(shadows = [RevocableNotificationManager::class])
    fun `permission revoked at notify is safe and a later check can deliver`() = runBlocking {
        due()
        manager.setEnabled(true)
        Shadow.extract<RevocableNotificationManager>(notifications).revokeNextPost = true
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertTrue(notifications.activeNotifications.isEmpty())
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(1, notifications.activeNotifications.size)
    }

    @Test fun `offline worker delivers using only local repositories`() = runBlocking {
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(null)
        due()
        manager.setEnabled(true)
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(1, notifications.activeNotifications.size)
    }

    @Test fun `runtime permission denied does not mark and grant delivers`() = runBlocking {
        if (Build.VERSION.SDK_INT < 33) return@runBlocking
        due()
        manager.setEnabled(true)
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        worker().doWork()
        assertTrue(notifications.activeNotifications.isEmpty())
        grantPermission()
        worker().doWork()
        assertEquals(1, notifications.activeNotifications.size)
    }

    @Test fun `completion suppresses even stale repository result and cancels just own tag`() = runBlocking {
        val first = due()
        val second = due()
        manager.setEnabled(true)
        worker().doWork()
        assertEquals(2, notifications.activeNotifications.size)
        manager.onReminderCompleted(first.id!!)
        worker(controller()).doWork()
        assertEquals(ReminderNotificationManager.tag(second.id!!), notifications.activeNotifications.single().tag)
    }

    @Test fun `completion before first check never posts`() = runBlocking {
        val reminder = due()
        manager.setEnabled(true)
        manager.onReminderCompleted(reminder.id!!)
        worker(controller()).doWork()
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun `rearming an edited reminder clears delivery so the new threshold can alert`() = runBlocking {
        val reminder = due(date = 1_000)
        manager.setEnabled(true)
        worker().doWork()
        assertEquals(1, notifications.activeNotifications.size)

        reminders.updateReminder(reminder.copy(dueDateMillis = 9_000_000_000_000))
        manager.onReminderRearmed(reminder.id!!)
        worker().doWork()
        assertTrue(notifications.activeNotifications.isEmpty())

        reminders.updateReminder(reminder.copy(dueDateMillis = 1_000))
        worker().doWork()
        assertEquals(1, notifications.activeNotifications.size)
    }

    @Test fun `reopening completion re-arms the same reminder`() = runBlocking {
        val reminder = due()
        manager.setEnabled(true)
        worker().doWork()
        assertEquals(1, notifications.activeNotifications.size)

        reminders.completeReminder(reminder.id!!, 2_000)
        manager.onReminderCompleted(reminder.id!!)
        worker().doWork()
        assertTrue(notifications.activeNotifications.isEmpty())

        reminders.reopenReminder(reminder.id!!)
        manager.onReminderReopened(reminder.id!!)
        worker().doWork()
        assertEquals(1, notifications.activeNotifications.size)
    }

    @Test fun `deleting a reminder forgets its delivery and completion markers`() = runBlocking {
        val reminder = due()
        manager.setEnabled(true)
        worker().doWork()
        manager.onReminderCompleted(reminder.id!!)

        manager.onReminderDeleted(reminder.id!!)

        val prefs = context.getSharedPreferences("service_reminder_notifications", Context.MODE_PRIVATE)
        assertFalse(prefs.getStringSet("delivered_ids", emptySet())!!.contains(reminder.id.toString()))
        assertFalse(prefs.getStringSet("completed_ids", emptySet())!!.contains(reminder.id.toString()))
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun `long IDs do not collide and malformed foreign repository results are excluded`() = runBlocking {
        val base = due()
        val repo = object : ReminderRepository by reminders {
            override suspend fun getDueReminders(vin: String, currentOdometer: Double, currentTimeMillis: Long) = listOf(
                base.copy(id = 1L), base.copy(id = 1L + (1L shl 32)), base.copy(id = 3, vin = otherVin), base.copy(id = 4, completed = true),
            )
        }
        manager.setEnabled(true)
        worker(controller(reminderRepo = repo)).doWork()
        assertEquals(2, notifications.activeNotifications.map { it.tag }.toSet().size)
    }

    @Test fun `sign out during suspended check prevents delivery across controller instances until refresh`() = runBlocking {
        val reminder = due()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repo = object : ReminderRepository by reminders {
            override suspend fun getDueReminders(vin: String, currentOdometer: Double, currentTimeMillis: Long): List<ServiceReminder> {
                entered.complete(Unit)
                release.await()
                return listOf(reminder)
            }
        }
        manager.setEnabled(true)
        val running = async { worker(controller(reminderRepo = repo)).doWork() }
        entered.await()
        manager.onSignedOut()
        release.complete(Unit)
        running.await()
        assertTrue(notifications.activeNotifications.isEmpty())
        worker(controller()).doWork() // Session clear may not have finished yet.
        assertTrue(notifications.activeNotifications.isEmpty())
        manager.setEnabled(true) // Only a successful restore/sign-in refresh may resume a sign-out.
        worker().doWork()
        assertTrue(notifications.activeNotifications.isEmpty())
        manager.refresh() // Successful sign-in/restore integration resumes checks.
        worker().doWork()
        assertEquals(1, notifications.activeNotifications.size)
    }

    @Test fun `opt out invalidates an in flight check even if quickly reenabled`() = runBlocking {
        val reminder = due()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repo = object : ReminderRepository by reminders {
            override suspend fun getDueReminders(vin: String, currentOdometer: Double, currentTimeMillis: Long): List<ServiceReminder> {
                entered.complete(Unit)
                release.await()
                return listOf(reminder)
            }
        }
        manager.setEnabled(true)
        val running = async { worker(controller(reminderRepo = repo)).doWork() }
        entered.await()
        manager.setEnabled(false)
        manager.setEnabled(true)
        release.complete(Unit)
        running.await()
        assertTrue(notifications.activeNotifications.isEmpty())
        worker().doWork()
        assertEquals(1, notifications.activeNotifications.size)
        manager.onSignedOut()
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun `repository storage failure retries without consuming delivery`() = runBlocking {
        due()
        manager.setEnabled(true)
        val repo = object : ReminderRepository by reminders {
            override suspend fun getDueReminders(vin: String, currentOdometer: Double, currentTimeMillis: Long): List<ServiceReminder> {
                throw android.database.sqlite.SQLiteException("temporarily unavailable")
            }
        }
        assertEquals(ListenableWorker.Result.retry(), worker(controller(reminderRepo = repo)).doWork())
        assertEquals(ListenableWorker.Result.failure(), worker(controller(reminderRepo = repo), 2).doWork())
        assertTrue(notifications.activeNotifications.isEmpty())
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(1, notifications.activeNotifications.size)
    }

    @Test fun `token rotation for the same VIN during query still delivers due reminder`() = runBlocking {
        val reminder = due()
        val repo = object : ReminderRepository by reminders {
            override suspend fun getDueReminders(vin: String, currentOdometer: Double, currentTimeMillis: Long): List<ServiceReminder> {
                val stored = sessions.load()!!
                sessions.save(stored.copy(session = stored.session.copy(
                    accessToken = "rotated-access-token",
                    userToken = "rotated-user-token",
                    issuedAt = stored.session.issuedAt.plusSeconds(60),
                )))
                return listOf(reminder)
            }
        }
        manager.setEnabled(true)
        assertEquals(ListenableWorker.Result.success(), worker(controller(reminderRepo = repo)).doWork())
        assertEquals(1, notifications.activeNotifications.size)
        assertEquals(ReminderNotificationManager.tag(reminder.id!!), notifications.activeNotifications.single().tag)
    }

    @Test fun `session recheck prevents delivery when VIN switches during query`() = runBlocking {
        val reminder = due()
        val repo = object : ReminderRepository by reminders {
            override suspend fun getDueReminders(vin: String, currentOdometer: Double, currentTimeMillis: Long): List<ServiceReminder> {
                val stored = sessions.load()!!
                sessions.save(stored.copy(credentials = Credentials(Vin.of(otherVin), "private-code")))
                return listOf(reminder)
            }
        }
        manager.setEnabled(true)
        assertEquals(ListenableWorker.Result.success(), worker(controller(reminderRepo = repo)).doWork())
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun `session recheck prevents delivery when account changes during query`() = runBlocking {
        val reminder = due()
        val repo = object : ReminderRepository by reminders {
            override suspend fun getDueReminders(vin: String, currentOdometer: Double, currentTimeMillis: Long): List<ServiceReminder> {
                sessions.clear()
                return listOf(reminder)
            }
        }
        manager.setEnabled(true)
        worker(controller(reminderRepo = repo)).doWork()
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun `storage retry is bounded and successful retry posts`() = runBlocking {
        due()
        manager.setEnabled(true)
        storageFailure = true
        assertEquals(ListenableWorker.Result.retry(), worker(attempt = 0).doWork())
        assertEquals(ListenableWorker.Result.retry(), worker(attempt = 1).doWork())
        assertEquals(ListenableWorker.Result.failure(), worker(attempt = 2).doWork())
        storageFailure = false
        assertEquals(ListenableWorker.Result.success(), worker(attempt = 1).doWork())
        assertEquals(1, notifications.activeNotifications.size)
    }

    @Test fun `refresh schedules unique unconstrained twelve hourly work and cancellation preserves other notifications`() = runBlocking {
        val work = WorkManager.getInstance(context)
        manager.setEnabled(true)
        manager.refresh()
        manager.refresh()
        assertEquals(0, sessionReads)
        val periodic = work.getWorkInfosForUniqueWork(ReminderNotificationManager.PERIODIC_WORK).get(10, TimeUnit.SECONDS)
        val pending = periodic.filter { !it.state.isFinished }.single()
        assertEquals(NetworkType.NOT_REQUIRED, pending.constraints.requiredNetworkType)
        assertEquals(TimeUnit.HOURS.toMillis(12), pending.periodicityInfo!!.repeatIntervalMillis)
        assertTrue(work.getWorkInfosForUniqueWork(ReminderNotificationManager.IMMEDIATE_WORK).get(10, TimeUnit.SECONDS).isNotEmpty())
        due()
        worker().doWork()
        notifications.notify("unrelated", 99, Notification.Builder(context, ReminderNotificationManager.CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info).build())
        manager.setEnabled(false)
        assertEquals("unrelated", notifications.activeNotifications.single().tag)
        assertTrue(work.getWorkInfosForUniqueWork(ReminderNotificationManager.PERIODIC_WORK).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
        assertTrue(work.getWorkInfosForUniqueWork(ReminderNotificationManager.IMMEDIATE_WORK).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
        manager.refresh()
        assertFalse(manager.enabled)
        manager.setEnabled(true)
        manager.onSignedOut()
        assertTrue(manager.enabled) // Sign-out pauses scheduling, not the user's opt-in.
        assertTrue(work.getWorkInfosForUniqueWork(ReminderNotificationManager.PERIODIC_WORK).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
    }

    @Test fun `notification offers mark done and snooze for the same reminder but not on the lockscreen`() = runBlocking {
        val reminder = due()
        manager.setEnabled(true)
        worker().doWork()
        val n = notifications.activeNotifications.single().notification
        assertEquals(listOf("Mark done", "Snooze"), n.actions.map { it.title.toString() })

        val markDone = n.actions[0].actionIntent
        val markDoneIntent = shadowOf(markDone).savedIntent
        assertEquals(ReminderActionReceiver::class.java.name, markDoneIntent.component!!.className)
        assertEquals(ReminderActionReceiver.ACTION_MARK_DONE, markDoneIntent.action)
        assertEquals(reminder.id, markDoneIntent.getLongExtra(ReminderActionReceiver.EXTRA_REMINDER_ID, -1))
        assertTrue(shadowOf(markDone).isImmutable)

        val snoozeIntent = shadowOf(n.actions[1].actionIntent).savedIntent
        assertEquals(ReminderActionReceiver.ACTION_SNOOZE, snoozeIntent.action)
        assertEquals(reminder.id, snoozeIntent.getLongExtra(ReminderActionReceiver.EXTRA_REMINDER_ID, -1))
        assertNotEquals(
            ReminderActionReceiver.requestCode(ReminderActionReceiver.ACTION_MARK_DONE, reminder.id!!),
            ReminderActionReceiver.requestCode(ReminderActionReceiver.ACTION_SNOOZE, reminder.id!!),
        )
        // Locked phones must not offer reminder actions.
        assertTrue(n.publicVersion.actions.isNullOrEmpty())
    }
}
