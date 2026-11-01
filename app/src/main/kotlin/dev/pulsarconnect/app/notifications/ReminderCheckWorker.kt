package dev.pulsarconnect.app.notifications

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.pulsarconnect.app.PulsarConnectApplication
import java.io.IOException

class ReminderCheckWorker internal constructor(
    context: Context,
    parameters: WorkerParameters,
    private val controller: ReminderNotificationManager,
) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) : this(
        context, parameters,
        (context.applicationContext as PulsarConnectApplication).container.reminderNotifications,
    )

    override suspend fun doWork(): Result = try {
        controller.checkDueReminders()
        Result.success()
    } catch (_: IOException) {
        storageFailure()
    } catch (_: SQLiteException) {
        storageFailure()
    }

    private fun storageFailure() = if (runAttemptCount < 2) Result.retry() else Result.failure()
}
