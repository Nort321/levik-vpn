package com.leviknet.vpn.core.network

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.leviknet.vpn.LevikVpnApplication
import java.util.concurrent.TimeUnit

class WhitelistMapWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? LevikVpnApplication)?.container ?: return Result.success()
        if (container.settings.whitelistMapEnabled.value) {
            container.whitelistMapReporter.collect(container.whitelistDetector)
        } else {
            container.whitelistMapReporter.clear()
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "levik_whitelist_map"
        fun configure(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) { manager.cancelUniqueWork(WORK_NAME); return }
            val request = PeriodicWorkRequestBuilder<WhitelistMapWorker>(WHITELIST_MAP_INTERVAL_MS, TimeUnit.MILLISECONDS)
                // CONNECTED also requires Android's network validation on modern Android.
                // Allowlists may block that probe; the reporter checks physical cellular
                // capabilities itself and immediately exits when there is no eligible network.
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
