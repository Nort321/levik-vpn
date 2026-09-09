package com.leviknet.vpn.core.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.leviknet.vpn.BuildConfig
import com.leviknet.vpn.LevikVpnApplication
import com.leviknet.vpn.core.logger.AppLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class AppUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LevikVpnApplication ?: return Result.failure()
        return try {
            val notifications = UpdateNotificationManager(applicationContext)
            notifications.clearInstalledUpdate()
            checkAndNotifyUpdate(
                updateManager = app.container.updateManager,
                installedVersion = BuildConfig.VERSION_CODE,
                lastNotifiedVersion = notifications.lastNotifiedVersion,
                notify = notifications::notify,
                recordNotifiedVersion = notifications::recordNotifiedVersion,
            )
            // The release client already applies persisted backoff. Retry on the next daily run.
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.w("AppUpdateWorker", "Background update check failed")
            Result.success()
        }
    }

    companion object {
        internal const val WORK_NAME = "levik_app_update_periodic"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

internal suspend fun checkAndNotifyUpdate(
    updateManager: AppUpdateManager,
    installedVersion: Int,
    lastNotifiedVersion: Int,
    notify: (AppUpdateDto) -> Boolean,
    recordNotifiedVersion: (Int) -> Unit,
) {
    val update = updateManager.checkForUpdatesInBackground() ?: return
    if (update.latestVersionCode <= installedVersion || update.latestVersionCode <= lastNotifiedVersion) return
    if (notify(update)) recordNotifiedVersion(update.latestVersionCode)
}
