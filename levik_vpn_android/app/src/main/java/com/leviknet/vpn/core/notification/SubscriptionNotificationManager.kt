package com.leviknet.vpn.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.leviknet.vpn.MainActivity
import com.leviknet.vpn.R
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.network.SubscriptionSummary
import com.leviknet.vpn.data.AppSettings
import java.time.Duration
import java.time.Instant

object SubscriptionNotificationManager {
    const val CHANNEL_ID = "levik_subscription_alerts"
    private const val NOTIFICATION_ID_EXPIRY = 2001
    private const val NOTIFICATION_ID_TRAFFIC = 2002
    private const val NOTIFICATION_ID_REVOCATION = 2003
    private const val LOG_TAG = "SubNotifManager"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_alerts_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun checkAndNotify(
        context: Context,
        subscription: SubscriptionSummary,
        settings: AppSettings,
    ) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // 1. Expiration check
        val expireAtStr = subscription.expireAt
        if (expireAtStr != null) {
            val expireInstant = runCatching { Instant.parse(expireAtStr) }.getOrNull()
            if (expireInstant != null) {
                val now = Instant.now()
                val duration = Duration.between(now, expireInstant)
                val daysRemaining = duration.toDays()
                val isExpired = now.isAfter(expireInstant)

                val lastMilestone = settings.getLastNotifiedExpireMilestone(subscription.uuid)

                when {
                    isExpired -> {
                        if (lastMilestone != "expired") {
                            settings.setLastNotifiedExpireMilestone(subscription.uuid, "expired")
                            postNotification(
                                context = context,
                                manager = manager,
                                notificationId = NOTIFICATION_ID_EXPIRY,
                                title = context.getString(R.string.notification_expiry_title),
                                text = context.getString(R.string.notification_expired),
                            )
                        }
                    }
                    daysRemaining <= 1L && duration.seconds > 0 -> {
                        if (lastMilestone != "1d" && lastMilestone != "expired") {
                            settings.setLastNotifiedExpireMilestone(subscription.uuid, "1d")
                            postNotification(
                                context = context,
                                manager = manager,
                                notificationId = NOTIFICATION_ID_EXPIRY,
                                title = context.getString(R.string.notification_expiry_title),
                                text = context.getString(R.string.notification_expiry_1d),
                            )
                        }
                    }
                    daysRemaining <= 3L && duration.seconds > 0 -> {
                        if (lastMilestone != "3d" && lastMilestone != "1d" && lastMilestone != "expired") {
                            settings.setLastNotifiedExpireMilestone(subscription.uuid, "3d")
                            postNotification(
                                context = context,
                                manager = manager,
                                notificationId = NOTIFICATION_ID_EXPIRY,
                                title = context.getString(R.string.notification_expiry_title),
                                text = context.getString(R.string.notification_expiry_3d),
                            )
                        }
                    }
                    daysRemaining <= 7L && duration.seconds > 0 -> {
                        if (lastMilestone == null) {
                            settings.setLastNotifiedExpireMilestone(subscription.uuid, "7d")
                            postNotification(
                                context = context,
                                manager = manager,
                                notificationId = NOTIFICATION_ID_EXPIRY,
                                title = context.getString(R.string.notification_expiry_title),
                                text = context.getString(R.string.notification_expiry_7d),
                            )
                        }
                    }
                }
            }
        }

        // 2. Traffic quota check
        val limit = subscription.traffic.limitBytes
        val used = subscription.traffic.usedBytes
        if (limit > 0L) {
            val ratio = used.toDouble() / limit.toDouble()
            val lastTrafficMilestone = settings.getLastNotifiedTrafficMilestone(subscription.uuid)

            when {
                ratio >= 1.0 -> {
                    if (lastTrafficMilestone != "100") {
                        settings.setLastNotifiedTrafficMilestone(subscription.uuid, "100")
                        postNotification(
                            context = context,
                            manager = manager,
                            notificationId = NOTIFICATION_ID_TRAFFIC,
                            title = context.getString(R.string.notification_traffic_title),
                            text = context.getString(R.string.notification_traffic_100),
                        )
                    }
                }
                ratio >= 0.95 -> {
                    if (lastTrafficMilestone != "95" && lastTrafficMilestone != "100") {
                        settings.setLastNotifiedTrafficMilestone(subscription.uuid, "95")
                        postNotification(
                            context = context,
                            manager = manager,
                            notificationId = NOTIFICATION_ID_TRAFFIC,
                            title = context.getString(R.string.notification_traffic_title),
                            text = context.getString(R.string.notification_traffic_95),
                        )
                    }
                }
                ratio >= 0.80 -> {
                    if (lastTrafficMilestone == null) {
                        settings.setLastNotifiedTrafficMilestone(subscription.uuid, "80")
                        postNotification(
                            context = context,
                            manager = manager,
                            notificationId = NOTIFICATION_ID_TRAFFIC,
                            title = context.getString(R.string.notification_traffic_title),
                            text = context.getString(R.string.notification_traffic_80),
                        )
                    }
                }
            }
        }
    }

    fun notifyDeviceRevoked(context: Context) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        postNotification(
            context = context,
            manager = manager,
            notificationId = NOTIFICATION_ID_REVOCATION,
            title = context.getString(R.string.notification_profile_revoked_title),
            text = context.getString(R.string.notification_profile_revoked),
        )
    }

    fun notifySubscriptionExpired(context: Context) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        postNotification(
            context = context,
            manager = manager,
            notificationId = NOTIFICATION_ID_EXPIRY,
            title = context.getString(R.string.notification_expiry_title),
            text = context.getString(R.string.notification_expired),
        )
    }

    private fun postNotification(
        context: Context,
        manager: NotificationManager,
        notificationId: Int,
        title: String,
        text: String,
    ) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(context, notificationId, launchIntent, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            manager.notify(notificationId, notification)
        }.onFailure { error ->
            AppLogger.w(LOG_TAG, "Failed to post alert notification: ${error.message}")
        }
    }
}
