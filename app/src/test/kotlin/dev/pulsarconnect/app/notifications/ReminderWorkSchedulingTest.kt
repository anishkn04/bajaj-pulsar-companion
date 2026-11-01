package dev.pulsarconnect.app.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context

import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

import androidx.work.testing.WorkManagerTestInitHelper

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.pulsarconnect.core.data.InMemoryReminderRepository
import dev.pulsarconnect.core.data.InMemoryTripRepository
import dev.pulsarconnect.core.model.Credentials
import dev.pulsarconnect.core.model.ServiceReminder
import dev.pulsarconnect.core.model.Session
import dev.pulsarconnect.core.model.StoredSession
import dev.pulsarconnect.core.model.Vin
import dev.pulsarconnect.core.session.SecretStore
import dev.pulsarconnect.core.session.SessionRepository
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 35])
class ReminderWorkSchedulingTest {
    private lateinit var context: Context
    private lateinit var work: WorkManager
    private lateinit var notifications: NotificationManager
    private lateinit var manager: ReminderNotificationManager
    private val executor = ManualExecutor()
    private val reminders = InMemoryReminderRepository()
    private val started = mutableListOf<HeldCheck>()
    private val vin = "MD2ABCDEFGH123456"

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("service_reminder_notifications", Context.MODE_PRIVATE).edit().clear().commit()
        notifications = context.getSystemService(NotificationManager::class.java)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(true)
        val sessions = SessionRepository(object : SecretStore {
            private var value: String? = null
            override fun read(key: String) = value
            override fun write(key: String, value: String) { this.value = value }
            override fun delete(key: String) { value = null }
        })
        sessions.save(StoredSession(Credentials(Vin.of(vin), "test-code"), Session("test-token", null, Instant.EPOCH)))
        manager = ReminderNotificationManager(context, sessions, InMemoryTripRepository(), reminders)
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(executor)
            .setTaskExecutor(executor)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters): ListenableWorker =
                    HeldCheck(appContext, params)
            }).build())
        work = WorkManager.getInstance(context)
    }

    @After fun tearDown() {
        val cancellation = work.cancelAllWork()
        executor.drain()
        cancellation.result.get(10, TimeUnit.SECONDS)
    }

    private class ManualExecutor : Executor {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() {
            var count = 0
            while (true) {
                val task = tasks.poll() ?: return
                check(count++ < 1000) { "WorkManager task queue did not settle" }
                task.run()
            }
        }
    }

    // Execute the real local delivery check, but retain the first worker's result future
    // after its snapshot/publish phase. Draining the executor controls every transition.
    private inner class HeldCheck(context: Context, params: WorkerParameters) : ListenableWorker(context, params) {
        private val completion = SettableFuture.create<Result>()

        override fun startWork(): ListenableFuture<Result> {
            val info = work.getWorkInfoById(id)
            info.addListener({
                // Only the immediate chain is under test; let periodic work finish normally.
                if (info.get(10, TimeUnit.SECONDS)!!.periodicityInfo != null) {
                    completion.set(Result.success())
                } else {
                    val hold = started.isEmpty()
                    started.add(this)
                    runBlocking { manager.checkDueReminders() }
                    if (!hold) completion.set(Result.success())
                }
            }, executor)
            return completion
        }

        fun finish(result: Result) { assertTrue(completion.set(result)) }
    }

    private fun due(): ServiceReminder = runBlocking {
        reminders.addReminder(ServiceReminder(
            vin = vin, title = "Service", description = null,
            dueOdometerKm = null, dueDateMillis = 1, createdAtMillis = 0,
        ))
    }

    private fun chain(): List<WorkInfo> {
        executor.drain()
        val query = work.getWorkInfosForUniqueWork(ReminderNotificationManager.IMMEDIATE_WORK)
        executor.drain()
        return query.get(10, TimeUnit.SECONDS)
    }

    @Test fun `refresh while check is running queues follow up which sees new due reminder`() {
        val original = due()
        manager.setEnabled(true)
        executor.drain()
        val first = started.single()
        assertEquals(WorkInfo.State.RUNNING, chain().single().state)
        assertEquals(ReminderNotificationManager.tag(original.id!!), notifications.activeNotifications.single().tag)

        val addedDuringCheck = due()
        manager.refresh()
        val queued = chain()
        assertEquals(2, queued.size)
        assertEquals(WorkInfo.State.RUNNING, queued.single { it.id == first.id }.state)
        assertEquals(WorkInfo.State.BLOCKED, queued.single { it.id != first.id }.state)
        assertEquals(1, started.size)
        assertFalse(first.isStopped)

        first.finish(ListenableWorker.Result.success())
        executor.drain()
        assertEquals(2, started.size)
        assertTrue(chain().all { it.state == WorkInfo.State.SUCCEEDED })
        assertEquals(setOf(original.id, addedDuringCheck.id).map { ReminderNotificationManager.tag(it!!) }.toSet(),
            notifications.activeNotifications.map { it.tag }.toSet())
    }

    @Test fun `refresh replaces failed predecessor chain and runs a fresh check`() {
        manager.setEnabled(true)
        executor.drain()
        val first = started.single()
        manager.refresh()
        assertEquals(2, chain().size)
        first.finish(ListenableWorker.Result.failure())
        assertTrue(chain().all { it.state == WorkInfo.State.FAILED })

        val addedAfterFailure = due()
        manager.refresh()
        val recovered = chain()
        assertEquals(1, recovered.count { it.state == WorkInfo.State.SUCCEEDED })
        assertTrue(recovered.all { it.state.isFinished })
        assertEquals(2, started.size)
        assertEquals(ReminderNotificationManager.tag(addedAfterFailure.id!!), notifications.activeNotifications.single().tag)
    }

    @Test fun `refresh replaces cancelled predecessor chain and runs a fresh check`() {
        manager.setEnabled(true)
        executor.drain()
        val first = started.single()
        manager.refresh()
        assertEquals(2, chain().size)
        val cancellation = work.cancelWorkById(first.id)
        executor.drain()
        cancellation.result.get(10, TimeUnit.SECONDS)
        assertTrue(chain().all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(first.isStopped)

        val addedAfterCancellation = due()
        manager.refresh()
        val recovered = chain()
        assertEquals(1, recovered.count { it.state == WorkInfo.State.SUCCEEDED })
        assertTrue(recovered.all { it.state.isFinished })
        assertEquals(2, started.size)
        assertEquals(ReminderNotificationManager.tag(addedAfterCancellation.id!!), notifications.activeNotifications.single().tag)
    }
}
