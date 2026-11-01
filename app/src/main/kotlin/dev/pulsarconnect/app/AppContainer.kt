package dev.pulsarconnect.app

import android.content.Context
import dev.pulsarconnect.app.data.AndroidDatabase
import dev.pulsarconnect.core.network.BajajApi
import dev.pulsarconnect.core.network.BajajSessionManager
import dev.pulsarconnect.core.session.SessionRepository

/**
 * Manual composition root. Small enough that a DI framework would add
 * indirection without removing any work.
 */
class AppContainer(context: Context) {

    private val secrets = KeystoreSecretStore(context.applicationContext)

    val database = AndroidDatabase(context.applicationContext)
    val sessionRepository = SessionRepository(secrets)
    val api = BajajApi()
    val sessionManager = BajajSessionManager(api, sessionRepository)
}
