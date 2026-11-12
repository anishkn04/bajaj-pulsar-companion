package dev.pulsarconnect.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.pulsarconnect.app.ble.BleUiState
import dev.pulsarconnect.app.ble.NotificationAccess

/** A single real setting that decides whether background mirroring can work. */
internal enum class SetupHealthAction {
    BIKE_PERMISSIONS,
    SELECTED_BIKE,
    NOTIFICATION_ACCESS,
    NOTIFICATION_PERMISSION,
    BATTERY_OPTIMIZATION,
    BACKGROUND_MODE,
    BOOT_RESUME,
}

internal data class SetupHealthItem(
    val action: SetupHealthAction,
    val title: String,
    val status: String,
    val detail: String,
    val needsAttention: Boolean,
)

internal data class SetupHealth(val items: List<SetupHealthItem>) {
    val attentionCount: Int get() = items.count { it.needsAttention }
    val ready: Boolean get() = attentionCount == 0

    val summary: String
        get() = when (attentionCount) {
            0 -> "Ready to mirror"
            1 -> "1 item needs attention"
            else -> "$attentionCount items need attention"
        }
}

/**
 * Evaluates the link setup without caching anything: runtime permissions and system grants are
 * read from the platform at call time, and the rider's preferences come from the live BLE state.
 */
internal fun setupHealth(
    bleState: BleUiState,
    notificationPermissionGranted: Boolean,
    batteryOptimizationIgnored: Boolean,
): SetupHealth {
    val settings = bleState.settings
    val bleReady = bleState.missingPermissions.isEmpty()
    val notificationAccessNeeded = settings.mirrorsAnyCall
    val notificationAccessReady = bleState.notificationAccessGranted || !notificationAccessNeeded
    val selected = bleState.selected

    val items = listOf(
        SetupHealthItem(
            action = SetupHealthAction.BIKE_PERMISSIONS,
            title = "Bluetooth permissions",
            status = if (bleReady) {
                "Granted"
            } else {
                "${bleState.missingPermissions.size} missing"
            },
            detail = if (bleReady) {
                "Nearby-device access is granted for this Android version."
            } else {
                "Grant nearby-device access to scan for and connect to the bike."
            },
            needsAttention = !bleReady,
        ),
        SetupHealthItem(
            action = SetupHealthAction.SELECTED_BIKE,
            title = "Selected bike",
            status = selected?.name ?: if (selected != null) "Selected" else "None",
            detail = if (selected != null) {
                "The bike link is remembered across launches."
            } else {
                "Scan for your bike and select the link target."
            },
            needsAttention = selected == null,
        ),
        SetupHealthItem(
            action = SetupHealthAction.NOTIFICATION_PERMISSION,
            title = "Notification permission",
            status = if (notificationPermissionGranted) "Granted" else "Not granted",
            detail = if (notificationPermissionGranted) {
                "The background mirroring notification and stop control can be shown."
            } else {
                "Background mirroring still runs, but its notification and stop control stay hidden."
            },
            needsAttention = !notificationPermissionGranted,
        ),
        SetupHealthItem(
            action = SetupHealthAction.NOTIFICATION_ACCESS,
            title = "Notification access",
            status = when {
                bleState.notificationAccessGranted -> "Granted"
                notificationAccessNeeded -> "Not granted"
                else -> "Not needed"
            },
            detail = when {
                bleState.notificationAccessGranted ->
                    "Used only while caller-name or missed-call mirroring is on."
                notificationAccessNeeded ->
                    "Caller-name and missed-call mirroring cannot work without notification access."
                else ->
                    "Only needed if you turn on caller-name or missed-call mirroring."
            },
            needsAttention = !notificationAccessReady,
        ),
        SetupHealthItem(
            action = SetupHealthAction.BATTERY_OPTIMIZATION,
            title = "Battery optimization",
            status = if (batteryOptimizationIgnored) "Unrestricted" else "Optimized",
            detail = if (batteryOptimizationIgnored) {
                "Android will not stop background mirroring to save power."
            } else {
                "Android may stop background mirroring shortly after you leave the app."
            },
            needsAttention = !batteryOptimizationIgnored,
        ),
        SetupHealthItem(
            action = SetupHealthAction.BACKGROUND_MODE,
            title = "Background mirroring",
            status = if (settings.runsInBackground) "On" else "Off",
            detail = if (settings.runsInBackground) {
                "Mirroring stays armed while you ride without the app open."
            } else {
                "Turn on mirroring and keep it in the background so the link survives leaving the app."
            },
            needsAttention = !settings.runsInBackground,
        ),
        SetupHealthItem(
            action = SetupHealthAction.BOOT_RESUME,
            title = "Boot resume",
            status = if (settings.resumesAfterBoot) "On" else "Off",
            detail = when {
                settings.resumesAfterBoot -> "The armed bike link resumes after a reboot."
                !settings.startOnBoot -> "Turn on resume mirroring after reboot in Settings."
                !settings.runsInBackground -> "Background mirroring must be on for boot resume to work."
                settings.armedVin == null -> "Select a bike to arm boot resume."
                else -> "Boot resume is off."
            },
            needsAttention = !settings.resumesAfterBoot,
        ),
    )
    return SetupHealth(items)
}

internal fun notificationPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

internal fun batteryOptimizationIgnored(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true

internal fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}

internal fun openNotificationAccessSettings(context: Context) {
    runCatching { context.startActivity(NotificationAccess.settingsIntent()) }
}

internal fun openBatteryOptimizationSettings(context: Context) {
    val intent = Intent().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
        } else {
            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            data = android.net.Uri.parse("package:${context.packageName}")
        }
    }
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}
