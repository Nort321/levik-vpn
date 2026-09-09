package com.leviknet.vpn.core.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.drawable.IconCompat
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.data.AppIcon

/** Uses the selected artwork everywhere, with an alpha silhouette for the status bar. */
internal object AppIconArtwork {
    private val smallIcons = mutableMapOf<AppIcon, Bitmap>()
    private val largeIcons = mutableMapOf<AppIcon, Bitmap>()

    @Synchronized
    fun smallIcon(context: Context, icon: AppIcon): IconCompat = IconCompat.createWithBitmap(
        smallIcons.getOrPut(icon) {
            val source = requireNotNull(BitmapFactory.decodeResource(context.resources, icon.foregroundResource))
            // Adaptive foregrounds contain launcher safe-zone padding. Remove only
            // transparent pixels so the same logo stays legible at status-bar size.
            val pixels = IntArray(source.width * source.height)
            source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
            var left = source.width
            var top = source.height
            var right = -1
            var bottom = -1
            pixels.forEachIndexed { index, pixel ->
                if (pixel ushr 24 != 0) {
                    val x = index % source.width
                    val y = index / source.width
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
            check(right >= left && bottom >= top) { "App icon has no visible foreground" }
            Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1)
        },
    )

    @Synchronized
    fun largeIcon(context: Context, icon: AppIcon): Bitmap = largeIcons.getOrPut(icon) {
        requireNotNull(BitmapFactory.decodeResource(context.resources, icon.previewResource))
    }

    fun updateNotification(context: Context, notification: Notification, icon: AppIcon): Notification =
        Notification.Builder.recoverBuilder(context, notification)
            .setSmallIcon(smallIcon(context, icon).toIcon(context))
            .setLargeIcon(largeIcon(context, icon))
            .setOnlyAlertOnce(true)
            .build()

    fun refreshNotifications(context: Context, icon: AppIcon) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.activeNotifications.forEach { active ->
                val updated = updateNotification(context, active.notification, icon)
                manager.notify(active.tag, active.id, updated)
            }
        }.onFailure {
            AppLogger.w("AppIcon", "Could not refresh notification artwork")
        }
    }
}
