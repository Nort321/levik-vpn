package com.leviknet.vpn.vpn

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.leviknet.vpn.LevikVpnApplication
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.network.ApiException
import com.leviknet.vpn.core.notification.SubscriptionNotificationManager
import com.leviknet.vpn.data.SessionStatus
import com.leviknet.vpn.data.containsActiveSubscription
import com.leviknet.vpn.data.isActiveAt
import java.time.Instant
import java.util.concurrent.TimeUnit

class SubscriptionSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? LevikVpnApplication ?: return Result.success()
        val container = app.container
        AppLogger.i(TAG, "Starting background subscription sync...")

        if (container.repository.session.value == SessionStatus.SignedOut) {
            AppLogger.d(TAG, "Session is signed out; skipping sync")
            return Result.success()
        }

        try {
            val account = container.repository.refreshAccount()
            val now = Instant.now()
            val active = account.subscriptions.filter { it.isActiveAt(now) }
            val selected = active.firstOrNull {
                it.uuid == container.settings.selectedSubscriptionId.value
            } ?: active.firstOrNull()

            val cachedProfile = container.repository.cachedTunnel()

            if (cachedProfile != null) {
                val stillValid = account.subscriptions.containsActiveSubscription(
                    subscriptionId = cachedProfile.subscriptionId,
                    now = now,
                )
                if (!stillValid) {
                    AppLogger.w(TAG, "Cached profile subscription is no longer active or was revoked. Disconnecting.")
                    if (container.vpnController.state.value.state !in setOf(
                            VpnConnectionState.DISCONNECTED,
                            VpnConnectionState.ERROR,
                        )
                    ) {
                        container.vpnController.disconnect()
                    }
                    SubscriptionNotificationManager.notifySubscriptionExpired(applicationContext)
                }
            }

            if (selected != null) {
                container.settings.setSelectedSubscriptionId(selected.uuid)
                // Refresh decrypted profile and cache on disk
                runCatching { container.repository.prepareTunnel(selected.uuid) }
                // Check and post warnings for 7/3/1 day and 80/95/100% traffic
                SubscriptionNotificationManager.checkAndNotify(
                    context = applicationContext,
                    subscription = selected,
                    settings = container.settings,
                )
            } else if (cachedProfile != null) {
                if (container.vpnController.state.value.state !in setOf(
                        VpnConnectionState.DISCONNECTED,
                        VpnConnectionState.ERROR,
                    )
                ) {
                    container.vpnController.disconnect()
                }
            }

            AppLogger.i(TAG, "Background subscription sync finished successfully")
            return Result.success()
        } catch (error: ApiException.Unauthorized) {
            AppLogger.w(TAG, "Session unauthorized during sync: ${error.message}")
            if (container.vpnController.state.value.state !in setOf(
                    VpnConnectionState.DISCONNECTED,
                    VpnConnectionState.ERROR,
                )
            ) {
                container.vpnController.disconnect()
            }
            SubscriptionNotificationManager.notifyDeviceRevoked(applicationContext)
            return Result.success()
        } catch (error: Exception) {
            AppLogger.e(TAG, "Subscription sync worker failed: ${error.message}")
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "SubscriptionSyncWorker"
        private const val UNIQUE_PERIODIC_WORK_NAME = "levik_subscription_sync_periodic"
        private const val UNIQUE_ONE_TIME_WORK_NAME = "levik_subscription_sync_immediate"

        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<SubscriptionSyncWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 15,
                flexTimeIntervalUnit = TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest,
            )
        }

        fun enqueueImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<SubscriptionSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )
        }
    }
}
