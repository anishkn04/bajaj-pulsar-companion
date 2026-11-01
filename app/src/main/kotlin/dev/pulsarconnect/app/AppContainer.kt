package dev.pulsarconnect.app

import android.content.Context
import dev.pulsarconnect.app.data.AndroidDatabase
import dev.pulsarconnect.app.notifications.ReminderNotificationManager
import dev.pulsarconnect.core.network.BajajApi
import dev.pulsarconnect.core.network.BajajSessionManager
import dev.pulsarconnect.core.session.SecretStore
import dev.pulsarconnect.core.session.SessionRepository

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
}
