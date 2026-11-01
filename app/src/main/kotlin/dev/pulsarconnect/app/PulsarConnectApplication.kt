package dev.pulsarconnect.app

import android.app.Application

class PulsarConnectApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.reminderNotifications.initialize()
    }
}
