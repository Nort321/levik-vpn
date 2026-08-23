package com.leviknet.vpn

import android.app.Application

class LevikVpnApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Eagerly initialize the container so the Wi-Fi auto-connect monitor
        // and subscription refresh loop run even before the first activity.
        container
        com.leviknet.vpn.core.notification.SubscriptionNotificationManager.ensureChannel(this)
        com.leviknet.vpn.vpn.SubscriptionSyncWorker.enqueuePeriodic(this)
    }
}
