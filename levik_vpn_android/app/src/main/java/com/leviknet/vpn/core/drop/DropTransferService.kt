package com.leviknet.vpn.core.drop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.leviknet.vpn.LevikVpnApplication
import com.leviknet.vpn.MainActivity
import com.leviknet.vpn.R
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.notification.AppIconArtwork
import com.leviknet.vpn.data.AppIconManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a transfer the user started or accepted going with the app in the
 * background, docs/levik-drop.md "Platforms": a data-sync foreground service
 * with a progress notification. It also holds the read access to files shared
 * from other apps for as long as it runs.
 */
class DropTransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as LevikVpnApplication).container.appPlatform.drop
        if (engine == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foreground) {
            // The system wants the notification within seconds of the start.
            if (!show(DropNotifications.progress(this, engine.snapshot.value.transfers.firstOrNull { it.held }))) {
                stopSelf()
                return START_NOT_STICKY
            }
            foreground = true
            scope.launch {
                engine.snapshot.map { snapshot -> snapshot.transfers.firstOrNull { it.held } }
                    .distinctUntilChanged()
                    .collect { transfer ->
                        if (transfer == null) {
                            stopForegroundCompat()
                            stopSelf()
                        } else {
                            runCatching { NotificationManagerCompat.from(this@DropTransferService).notify(NOTIFICATION_ID, DropNotifications.progress(this@DropTransferService, transfer)) }
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun show(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (error: Exception) {
        AppLogger.w(TAG, "LevikDrop could not keep running: ${error.javaClass.simpleName}")
        false
    }

    /** Android 15 limits data sync to six hours a day: the transfer pauses until the app is opened. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (!foreground) return
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LevikDrop"
        const val NOTIFICATION_ID = 5701

        /**
         * Starts (or refreshes) the service. [shared] are content:// files another
         * app shared: the service keeps the read grant while it runs.
         */
        fun start(context: Context, shared: List<Uri> = emptyList()) {
            val intent = Intent(context, DropTransferService::class.java)
            if (shared.isNotEmpty()) {
                val clip = ClipData.newRawUri(null, shared.first())
                shared.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                intent.clipData = clip
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: Exception) {
                // Started from the background: the VPN (if on) keeps the app running meanwhile.
                AppLogger.w(TAG, "LevikDrop service not started: ${error.javaClass.simpleName}")
            }
        }
    }
}

/** LevikDrop's notifications: progress, an offer, files received. */
object DropNotifications {
    private const val CHANNEL_ID = "levik_drop"
    private const val PROGRESS_CHANNEL_ID = "levik_drop_progress"
    private const val NOTICE_ID_BASE = 5710

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.drop_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.drop_channel_desc)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL_ID, context.getString(R.string.drop_progress_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.drop_progress_channel_desc)
                setShowBadge(false)
            },
        )
    }

    private fun openApp(context: Context, requestCode: Int): PendingIntent {
        val open = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, requestCode, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The app was opened from a LevikDrop notification: the profile shows the transfer. */
    const val ACTION_OPEN = "com.leviknet.vpn.action.OPEN_DROP"

    fun progress(context: Context, transfer: DropTransferView?): Notification {
        ensureChannels(context)
        val title = when {
            transfer == null -> context.getString(R.string.drop_title)
            transfer.sender -> context.getString(R.string.drop_progress_sending, transfer.peerName)
            else -> context.getString(R.string.drop_progress_receiving, transfer.peerName)
        }
        val builder = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(AppIconArtwork.smallIcon(context, AppIconManager(context).current()))
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context, NOTICE_ID_BASE))
        if (transfer != null && transfer.totalBytes > 0) {
            val percent = (transfer.doneBytes * 100 / transfer.totalBytes).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, transfer.link != LinkStatus.SESSION)
            builder.setContentText(
                context.getString(R.string.drop_progress_text, DropFormat.bytes(context, transfer.doneBytes), DropFormat.bytes(context, transfer.totalBytes)),
            )
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun notice(context: Context, notice: DropNotice) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannels(context)
        val (title, body) = when (notice) {
            is DropNotice.Offer -> context.getString(R.string.drop_notice_offer_title, notice.peerName) to
                context.getString(
                    R.string.drop_notice_offer_body,
                    notice.names.singleOrNull()?.let { "«$it»" } ?: DropFormat.fileCount(context, notice.names.size),
                )
            is DropNotice.Received -> context.getString(R.string.drop_notice_received_title) to
                context.getString(R.string.drop_notice_received_body, DropFormat.fileCount(context, notice.fileCount), notice.peerName)
        }
        val id = NOTICE_ID_BASE + 1 + (notice.transferId.hashCode() and 0xff)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(AppIconArtwork.smallIcon(context, AppIconManager(context).current()))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (notice is DropNotice.Offer) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (notice is DropNotice.Offer) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, id))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .onFailure { AppLogger.w("LevikDrop", "Could not show a LevikDrop notice") }
    }
}

/** Sizes and counts the way the screens show them. */
object DropFormat {
    fun bytes(context: Context, bytes: Long): String {
        if (bytes < 1024) return context.getString(R.string.drop_size_bytes, bytes)
        val units = intArrayOf(R.string.drop_size_kb, R.string.drop_size_mb, R.string.drop_size_gb)
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val shown = if (value >= 100 || value % 1.0 == 0.0) {
            Math.round(value).toString()
        } else {
            String.format(java.util.Locale.getDefault(), "%.1f", value)
        }
        return context.getString(units[unit], shown)
    }

    fun fileCount(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.drop_file_count, count, count)
}
