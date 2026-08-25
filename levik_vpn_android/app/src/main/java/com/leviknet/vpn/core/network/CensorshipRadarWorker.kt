package com.leviknet.vpn.core.network

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.leviknet.vpn.LevikVpnApplication
import java.util.concurrent.TimeUnit

class CensorshipRadarWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LevikVpnApplication ?: return Result.success()
        val container = app.container
        if (!container.settings.anonymousTelemetryEnabled.value) return Result.success()
        return runCatching {
            NetworkDiagnostics.runDiagnostics(
                vpnSnapshot = container.vpnController.state.value,
                apiClient = container.apiClient,
                sendTelemetry = true,
            )
            Result.success()
        }.getOrElse {
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "levik_censorship_radar"

        fun configure(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<CensorshipRadarWorker>(
                6,
                TimeUnit.HOURS,
                1,
                TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
