package dev.pulsarconnect.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.pulsarconnect.app.ble.AndroidBikeDeviceStore
import dev.pulsarconnect.app.ble.AndroidBikeLinkPreferences
import dev.pulsarconnect.app.ble.AndroidBleScanner
import dev.pulsarconnect.app.ble.AndroidBleTransport
import dev.pulsarconnect.app.ble.AndroidPhoneStatusSource
import dev.pulsarconnect.app.ble.BikeLinkRuntime
import dev.pulsarconnect.app.ble.BikeLinkService
import dev.pulsarconnect.app.ble.BleViewModel
import dev.pulsarconnect.app.ble.PhoneStatusSource
import dev.pulsarconnect.app.data.AndroidDatabase
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
    )

    // BLE lives in the application scope so it can outlive any single view model.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val bikeDevices: BikeDeviceStore = AndroidBikeDeviceStore(context.applicationContext)
    val bleScanSource: BleScanSource = AndroidBleScanner(context.applicationContext)
    val bleTransport = AndroidBleTransport(context.applicationContext)
    val bikeConnection = BikeConnection(bleTransport, appScope)
    val phoneStatusSource: PhoneStatusSource = AndroidPhoneStatusSource(context.applicationContext)

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
        )
    }

    val bikeLinkRuntime: BikeLinkRuntime = object : BikeLinkRuntime {
        override fun setEnabled(enabled: Boolean) {
            val intent = Intent(context.applicationContext, BikeLinkService::class.java).apply {
                action = if (enabled) BikeLinkService.ACTION_START else BikeLinkService.ACTION_STOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
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
}
