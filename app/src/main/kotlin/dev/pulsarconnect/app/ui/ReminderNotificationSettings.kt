package dev.pulsarconnect.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pulsarconnect.app.notifications.ReminderNotificationManager

internal data class ReminderNotificationStatus(
    val permissionGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val channelBlocked: Boolean,
) {
    val canDeliver: Boolean get() = permissionGranted && appNotificationsEnabled && !channelBlocked
}

internal fun readReminderNotificationStatus(context: Context): ReminderNotificationStatus {
    val notifications = context.getSystemService(NotificationManager::class.java)
    return ReminderNotificationStatus(
        permissionGranted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        appNotificationsEnabled = notifications.areNotificationsEnabled(),
        // A missing channel is not a user block; the application creates it at startup.
        channelBlocked = notifications.getNotificationChannel(ReminderNotificationManager.CHANNEL_ID)
            ?.importance == NotificationManager.IMPORTANCE_NONE,
    )
}

internal enum class ReminderNotificationAction { Enable, Disable, RequestPermission, OpenSettings }

internal fun reminderNotificationAction(
    enabled: Boolean,
    status: ReminderNotificationStatus,
    permissionDenied: Boolean,
): ReminderNotificationAction = when {
    enabled -> ReminderNotificationAction.Disable
    status.channelBlocked -> ReminderNotificationAction.OpenSettings
    !status.permissionGranted && !permissionDenied -> ReminderNotificationAction.RequestPermission
    !status.canDeliver -> ReminderNotificationAction.OpenSettings
    else -> ReminderNotificationAction.Enable
}

internal fun applyReminderPermissionResult(granted: Boolean, onEnabledChange: (Boolean) -> Unit) {
    if (granted) onEnabledChange(true)
}

internal fun openReminderNotificationSettings(context: Context): Boolean {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

@Composable
internal fun ReminderNotificationSettings(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onNotificationsRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnEnabledChange by rememberUpdatedState(onEnabledChange)
    val latestRefresh by rememberUpdatedState(onNotificationsRefresh)
    var status by remember(context) { mutableStateOf(readReminderNotificationStatus(context)) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var settingsUnavailable by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        status = readReminderNotificationStatus(context)
        applyReminderPermissionResult(granted, latestOnEnabledChange)
        latestRefresh()
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = readReminderNotificationStatus(context)
                latestRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val openSettings = {
        settingsUnavailable = !openReminderNotificationSettings(context)
    }
    PulsarCard {
        Text(
            "SERVICE-REMINDER ALERTS",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Service-reminder alerts",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            val (pillText, pillTone) = when {
                !enabled -> "Off" to StatusTone.Neutral
                !status.canDeliver -> "Blocked" to StatusTone.Warning
                else -> "On" to StatusTone.Active
            }
            StatusPill(text = pillText, tone = pillTone)
        }
        Text(
            when {
                !enabled -> "Alerts: off (opt-in only)"
                !status.canDeliver -> "Alerts: on · delivery blocked by Android"
                else -> "Alerts: on · Android allows delivery"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            "Signing out stops alerts, but keeps your opt-in preference on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!status.permissionGranted) {
            Text(
                when {
                    enabled -> "Notification permission is not granted. Your opt-in remains on, but delivery is blocked. " +
                        "Allow notifications in Android settings to restore delivery, or disable alerts below."
                    permissionDenied -> "Notification permission was not granted. Alerts remain off. " +
                        "You can allow notifications in Android settings; we will not prompt again automatically."
                    else -> "Notification permission is not granted. Tap Enable alerts to request it."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!status.appNotificationsEnabled) {
            Text(
                "Android is blocking app notifications. Allow notifications in Android settings to receive alerts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (status.channelBlocked) {
            Text(
                "The Service reminders notification channel is blocked. Enable it in Android settings to receive alerts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (settingsUnavailable) {
            Text(
                "Could not open Android settings. Open Settings manually and find this app’s notification settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        PillButton(
            label = if (enabled) "Disable alerts" else "Enable alerts",
            onClick = {
                status = readReminderNotificationStatus(context)
                when (reminderNotificationAction(enabled, status, permissionDenied)) {
                    ReminderNotificationAction.Enable -> latestOnEnabledChange(true)
                    ReminderNotificationAction.Disable -> latestOnEnabledChange(false)
                    ReminderNotificationAction.RequestPermission -> {
                        if (Build.VERSION.SDK_INT >= 33) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    ReminderNotificationAction.OpenSettings -> openSettings()
                }
            },
            style = if (enabled) PillStyle.Outlined else PillStyle.Filled,
        )
        if (!status.canDeliver) {
            CardLink(label = "Open notification settings", onClick = openSettings)
        }
    }
}
