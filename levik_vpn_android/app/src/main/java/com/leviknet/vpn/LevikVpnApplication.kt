package com.leviknet.vpn

import android.app.Application

class LevikVpnApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= 28 && getProcessName().endsWith(":yandex_guest")) {
            android.webkit.WebView.setDataDirectorySuffix("yandex_guest")
            return
        }
        com.leviknet.vpn.core.logger.AppLogger.attachDiskLog(
            com.leviknet.vpn.core.logger.DiskLog(java.io.File(noBackupFilesDir, "logs")),
        )
        // Eagerly initialize the container so the Wi-Fi auto-connect monitor
        // and subscription refresh loop run even before the first activity.
        container
        com.leviknet.vpn.core.notification.SubscriptionNotificationManager.ensureChannel(this)
        com.leviknet.vpn.vpn.SubscriptionSyncWorker.enqueuePeriodic(this)
        com.leviknet.vpn.core.update.scheduleBackgroundUpdateChecks(this)
    }
}
