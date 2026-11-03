package dev.pulsarconnect.app

import android.content.Context
import dev.pulsarconnect.app.ble.AndroidBikeDeviceStore
import dev.pulsarconnect.app.ble.AndroidBleScanner
import dev.pulsarconnect.app.ble.AndroidBleTransport
import dev.pulsarconnect.app.ble.AndroidPhoneStatusSource
import dev.pulsarconnect.app.ble.PhoneStatusSource
import dev.pulsarconnect.app.data.AndroidDatabase
import dev.pulsarconnect.app.notifications.ReminderNotificationManager
import dev.pulsarconnect.core.ble.BikeConnection
import dev.pulsarconnect.core.ble.BikeDeviceStore
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

    // BLE is scoped to the app process but only runs while the signed-in UI is on screen.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val bikeDevices: BikeDeviceStore = AndroidBikeDeviceStore(context.applicationContext)
    val bleScanSource: BleScanSource = AndroidBleScanner(context.applicationContext)
    val bleTransport = AndroidBleTransport(context.applicationContext)
    val bikeConnection = BikeConnection(bleTransport, appScope)
    val phoneStatusSource: PhoneStatusSource = AndroidPhoneStatusSource(context.applicationContext)
}
