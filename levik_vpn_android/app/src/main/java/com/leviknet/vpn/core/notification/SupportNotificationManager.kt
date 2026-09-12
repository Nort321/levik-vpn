package com.leviknet.vpn.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.leviknet.vpn.R
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.network.MobileSupportReply
import com.leviknet.vpn.core.network.MobileSupportSummary
import com.leviknet.vpn.data.AppIconManager
import com.leviknet.vpn.data.AppSettings

object SupportNotificationManager {
    const val CHANNEL_ID = "levik_support_alerts"
    const val NOTIFICATION_ID = 2005
    const val DEFAULT_SUPPORT_URL = "https://leviknet.com/dashboard/support"
    private const val LOG_TAG = "SupportNotifManager"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_support),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_support_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun shouldNotify(support: MobileSupportSummary, lastNotifiedReplyId: String?): Boolean {
        val reply = support.latestReply ?: return false
        if (support.unreadCount <= 0) return false
        if (lastNotifiedReplyId == reply.replyId) return false
        return true
    }

    fun resolveSupportUrl(reply: MobileSupportReply): String =
        reply.ticketUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_SUPPORT_URL

    fun checkAndNotify(
        context: Context,
        support: MobileSupportSummary,
        settings: AppSettings,
    ) {
        val reply = support.latestReply ?: return
        if (support.unreadCount <= 0) return
        if (settings.getLastNotifiedSupportReplyId() == reply.replyId) return

        settings.setLastNotifiedSupportReplyId(reply.replyId)

        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val targetUrl = resolveSupportUrl(reply)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, flags)

        val appIcon = AppIconManager(context).current()
        val title = context.getString(R.string.notification_support_reply_title)
        val text = context.getString(
            R.string.notification_support_reply_text,
            reply.subject,
            reply.messageSnippet,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(AppIconArtwork.smallIcon(context, appIcon))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            manager.notify(NOTIFICATION_ID, notification)
        }.onFailure { error ->
            AppLogger.w(LOG_TAG, "Failed to post support notification: ${error.message}")
        }
    }
}
