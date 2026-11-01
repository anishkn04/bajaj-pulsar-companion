package dev.pulsarconnect.app.ui

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import dev.pulsarconnect.app.notifications.ReminderNotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 35])
class ReminderNotificationSettingsTest {
    private lateinit var context: Application
    private lateinit var notifications: NotificationManager

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        notifications = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33) {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        shadowOf(notifications).setNotificationsEnabled(true)
    }

    @Test fun `missing channel does not count as user block`() {
        assertEquals(null, notifications.getNotificationChannel(ReminderNotificationManager.CHANNEL_ID))
        val status = readReminderNotificationStatus(context)
        assertTrue(status.canDeliver)
        assertEquals(ReminderNotificationAction.Enable, reminderNotificationAction(false, status, false))
    }

    @Test fun `enabled service reminder channel permits explicit opt in`() {
        createChannel(NotificationManager.IMPORTANCE_DEFAULT)
        val status = readReminderNotificationStatus(context)
        assertFalse(status.channelBlocked)
        assertTrue(status.canDeliver)
        assertEquals(ReminderNotificationAction.Enable, reminderNotificationAction(false, status, false))
    }

    @Test fun `blocked service reminder channel sends explicit enable to settings`() {
        createChannel(NotificationManager.IMPORTANCE_NONE)
        val status = readReminderNotificationStatus(context)
        assertTrue(status.channelBlocked)
        assertFalse(status.canDeliver)
        assertEquals(ReminderNotificationAction.OpenSettings, reminderNotificationAction(false, status, false))
    }

    @Test fun `blocked unrelated channel does not block reminders`() {
        notifications.createNotificationChannel(NotificationChannel("other", "Other", NotificationManager.IMPORTANCE_NONE))
        assertTrue(readReminderNotificationStatus(context).canDeliver)
    }

    @Test fun `global block sends explicit enable to settings`() {
        shadowOf(notifications).setNotificationsEnabled(false)
        val status = readReminderNotificationStatus(context)
        assertFalse(status.appNotificationsEnabled)
        assertFalse(status.canDeliver)
        assertEquals(ReminderNotificationAction.OpenSettings, reminderNotificationAction(false, status, false))
    }

    @Test fun `opt out remains available when Android blocks delivery`() {
        shadowOf(notifications).setNotificationsEnabled(false)
        createChannel(NotificationManager.IMPORTANCE_NONE)
        if (Build.VERSION.SDK_INT >= 33) {
            shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        assertEquals(
            ReminderNotificationAction.Disable,
            reminderNotificationAction(true, readReminderNotificationStatus(context), true),
        )
    }

    @Test
    @Config(sdk = [35])
    fun `missing runtime permission requests only on explicit enable action even if globally disabled`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(false)
        val status = readReminderNotificationStatus(context)
        assertFalse(status.permissionGranted)
        assertFalse(status.canDeliver)
        assertEquals(ReminderNotificationAction.RequestPermission, reminderNotificationAction(false, status, false))
    }

    @Test
    @Config(sdk = [35])
    fun `denied permission leaves opt in unchanged and next tap goes to settings without another prompt`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val changes = mutableListOf<Boolean>()
        applyReminderPermissionResult(false, changes::add)
        assertTrue(changes.isEmpty())
        assertEquals(
            ReminderNotificationAction.OpenSettings,
            reminderNotificationAction(false, readReminderNotificationStatus(context), true),
        )
    }

    @Test fun `granted permission opts in exactly once`() {
        val changes = mutableListOf<Boolean>()
        applyReminderPermissionResult(true, changes::add)
        assertEquals(listOf(true), changes)
    }

    @Test
    @Config(sdk = [26])
    fun `Android 26 requires no runtime prompt`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val status = readReminderNotificationStatus(context)
        assertTrue(status.permissionGranted)
        assertEquals(ReminderNotificationAction.Enable, reminderNotificationAction(false, status, false))
    }

    @Test
    @Config(sdk = [35])
    fun `rechecking after settings reflects permission revocation and preserves disable action`() {
        assertTrue(readReminderNotificationStatus(context).canDeliver)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val revoked = readReminderNotificationStatus(context)
        assertFalse(revoked.canDeliver)
        assertEquals(ReminderNotificationAction.Disable, reminderNotificationAction(true, revoked, false))
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(readReminderNotificationStatus(context).canDeliver)
    }

    @Test fun `settings intent uses only package and works from actual application context`() {
        assertTrue(openReminderNotificationSettings(context))
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(setOf(Settings.EXTRA_APP_PACKAGE), intent.extras!!.keySet())
        assertEquals(null, intent.data)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `missing notification settings falls back to package application details`() {
        val attempts = mutableListOf<Intent>()
        val fallbackContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                attempts.add(intent)
                if (intent.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) throw ActivityNotFoundException()
                super.startActivity(intent)
            }
        }
        assertTrue(openReminderNotificationSettings(fallbackContext))
        assertEquals(2, attempts.size)
        val fallback = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, fallback.action)
        assertEquals("package:${context.packageName}", fallback.data.toString())
        assertEquals(null, fallback.extras)
        assertTrue(fallback.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `missing both settings activities reports failure without crashing`() {
        val unavailableContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        assertFalse(openReminderNotificationSettings(unavailableContext))
    }

    private fun createChannel(importance: Int) {
        notifications.createNotificationChannel(
            NotificationChannel(ReminderNotificationManager.CHANNEL_ID, "Service reminders", importance),
        )
    }
}
