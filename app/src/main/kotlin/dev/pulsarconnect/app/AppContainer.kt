package dev.pulsarconnect.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager
import dev.pulsarconnect.app.ble.AndroidBikeDeviceStore
import dev.pulsarconnect.app.ble.AndroidBikeLinkPreferences
import dev.pulsarconnect.app.ble.AndroidBleScanner
import dev.pulsarconnect.app.ble.AndroidBleTransport
import dev.pulsarconnect.app.ble.AndroidPhoneCallSource
import dev.pulsarconnect.app.ble.AndroidPhoneStatusSource
import dev.pulsarconnect.app.ble.BikeLinkRuntime
import dev.pulsarconnect.app.ble.BikeLinkService
import dev.pulsarconnect.app.ble.PhoneCallSource
import dev.pulsarconnect.app.ble.shouldRunBikeLinkService
import dev.pulsarconnect.app.ble.BleViewModel
import dev.pulsarconnect.app.ble.PhoneStatusSource
import dev.pulsarconnect.app.calls.CallNotificationBus
import dev.pulsarconnect.app.calls.CallNotificationParser
import dev.pulsarconnect.app.data.AndroidDatabase
import dev.pulsarconnect.app.notifications.ReminderActionBus
import dev.pulsarconnect.app.notifications.ReminderActionHandler
import dev.pulsarconnect.app.notifications.ReminderNotificationManager
import dev.pulsarconnect.core.ble.BikeConnection
import dev.pulsarconnect.core.ble.BikeDeviceStore
import dev.pulsarconnect.core.ble.BikeLinkController
import dev.pulsarconnect.core.ble.BikeLinkPreferences
import dev.pulsarconnect.core.ble.BikeScanner
import dev.pulsarconnect.core.ble.BleScanSource
import dev.pulsarconnect.core.network.BajajApi
import dev.pulsarconnect.core.network.BajajSessionManager
import dev.pulsarconnect.core.session.SecretStore
import dev.pulsarconnect.core.session.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual composition root. Small enough that a DI framework would add
 * indirection without removing any work.
 *
 * The [bikeLinkController] lives for the whole process so the foreground service can keep
 * mirroring alive after the UI goes to the background. It is created lazily because it must
 * not load the Keystore at application construction time.
 */
class AppContainer(context: Context) {

    // Constructing the application/container must not load the Android Keystore.
    private val secrets = object : SecretStore {
        private val store by lazy { KeystoreSecretStore(context.applicationContext) }
        override fun read(key: String) = store.read(key)
        override fun write(key: String, value: String) = store.write(key, value)
        override fun delete(key: String) = store.delete(key)
    }

    val database = AndroidDatabase(context.applicationContext)
    val sessionRepository = SessionRepository(secrets)
    val api = BajajApi()
    val sessionManager = BajajSessionManager(api, sessionRepository)
    val reminderNotifications: ReminderNotificationManager = ReminderNotificationManager(
        context.applicationContext, sessionRepository, database.trips, database.reminders,
        database.odometers,
    )
    /** Lets a notification action tell the open UI to reload reminders. */
    val reminderActions = ReminderActionBus()
    /** Shared by the notification receiver; mirrors the in-app reminder mutations. */
    val reminderActionHandler = ReminderActionHandler(
        sessionRepository, database.reminders, reminderNotifications,
    )

    // BLE lives in the application scope so it can outlive any single view model.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val bikeDevices: BikeDeviceStore = AndroidBikeDeviceStore(context.applicationContext)
    val bleScanSource: BleScanSource = AndroidBleScanner(context.applicationContext)
    val bleTransport = AndroidBleTransport(context.applicationContext)
    val bikeConnection = BikeConnection(
        bleTransport,
        appScope,
        handshakeSettleMillis = BikeConnection.DEFAULT_HANDSHAKE_SETTLE_MILLIS,
    )
    val phoneStatusSource: PhoneStatusSource = AndroidPhoneStatusSource(context.applicationContext)

    /** Process-local call-notification hand-off; the listener service publishes into it. */
    val callNotificationBus = CallNotificationBus()
    val callNotificationParser = CallNotificationParser(dialerPackages(context.applicationContext))
    val phoneCallSource: PhoneCallSource =
        AndroidPhoneCallSource(context.applicationContext, callNotificationBus)

    /** Persistent bike-link intent (mirroring, background, boot, armed VIN). */
    val bikeLinkPreferences: BikeLinkPreferences = AndroidBikeLinkPreferences(context.applicationContext)

    /**
     * Owns connection, phone-status sending and CONTROL polling outside any screen. Started
     * lazily so the Keystore is never loaded until the signed-in UI actually needs Bluetooth.
     */
    val bikeLinkController: BikeLinkController by lazy {
        BikeLinkController(
            devices = bikeDevices,
            connection = bikeConnection,
            preferences = bikeLinkPreferences,
            statuses = phoneStatusSource.statuses(),
            scope = appScope,
            callEvents = phoneCallSource.events(),
        )
    }

    val bikeLinkRuntime: BikeLinkRuntime = object : BikeLinkRuntime {
        override fun setEnabled(enabled: Boolean) {
            val settings = bikeLinkPreferences.settings.value
            // Only keep the foreground service running when mirroring is armed for background
            // operation and a bike is armed. Otherwise the view model's own scope holds the link
            // while the app is in the foreground, and stopping the service avoids an unnecessary
            // persistent notification.
            val shouldRun = enabled && shouldRunBikeLinkService(
                settings,
                settings.armedVin?.let { bikeDevices.selected(it) } != null,
            )
            val intent = Intent(context.applicationContext, BikeLinkService::class.java).apply {
                action = if (shouldRun) BikeLinkService.ACTION_START else BikeLinkService.ACTION_STOP
            }
            if (shouldRun) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
                bikeLinkController.ensureRunning()
            } else {
                context.applicationContext.stopService(intent)
            }
        }
    }

    val bleViewModelFactory: androidx.lifecycle.ViewModelProvider.Factory = BleViewModel.factory(
        application = context.applicationContext as android.app.Application,
        devices = bikeDevices,
        scanner = BikeScanner(bleScanSource),
        transport = bleTransport,
        controller = bikeLinkController,
        runtime = bikeLinkRuntime,
    )

    private companion object {
        /**
         * Conservative dialer allowlist. The active default dialer is added at runtime; the
         * constants cover the stock dialers of the common OEMs without accepting arbitrary apps.
         */
        fun dialerPackages(context: Context): Set<String> {
            val packages = linkedSetOf(
                "com.google.android.dialer",
                "com.android.dialer",
                "com.android.phone",
                "com.oneplus.dialer",
                "com.oplus.dialer",
                "com.samsung.android.dialer",
            )
            runCatching {
                context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
            }.getOrNull()?.let { packages += it }
            return packages
        }
    }
}
