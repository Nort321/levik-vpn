package com.leviknet.vpn.core.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.leviknet.vpn.BuildConfig
import com.leviknet.vpn.MainActivity
import com.leviknet.vpn.R
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.notification.AppIconArtwork
import com.leviknet.vpn.data.AppIconManager

internal class UpdateNotificationManager(private val context: Context) {
    private val preferences = context.getSharedPreferences("app_update_notifications", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)

    val lastNotifiedVersion: Int
        get() = preferences.getInt(KEY_LAST_NOTIFIED_VERSION, 0)

    fun recordNotifiedVersion(versionCode: Int) {
        preferences.edit().putInt(KEY_LAST_NOTIFIED_VERSION, versionCode).apply()
    }

    fun clearInstalledUpdate() {
        if (lastNotifiedVersion <= BuildConfig.VERSION_CODE) manager?.cancel(NOTIFICATION_ID)
    }

    fun notify(update: AppUpdateDto): Boolean {
        val manager = manager ?: return false
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_updates),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_updates_desc)
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        ) return false

        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_UPDATE
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.notification_update_available, update.latestVersionName)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(AppIconArtwork.smallIcon(context, AppIconManager(context).current()))
            .setContentTitle(context.getString(R.string.update_available_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (error: SecurityException) {
            // Permission can be revoked between the check and posting the notification.
            AppLogger.w("UpdateNotification", "Update notification permission denied")
            false
        }
    }

    companion object {
        internal const val ACTION_OPEN_UPDATE = "com.leviknet.vpn.action.OPEN_UPDATE"
        private const val CHANNEL_ID = "levik_app_updates"
        private const val NOTIFICATION_ID = 2004
        private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
    }
}
