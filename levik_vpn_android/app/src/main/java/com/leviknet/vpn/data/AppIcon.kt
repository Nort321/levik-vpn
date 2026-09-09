package com.leviknet.vpn.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.DrawableRes
import com.leviknet.vpn.R

enum class AppIcon(
    val aliasName: String,
    @param:DrawableRes val launcherResource: Int,
    @param:DrawableRes val foregroundResource: Int,
    @param:DrawableRes val previewResource: Int,
) {
    LIGHT("LauncherLight", R.mipmap.ic_launcher, R.drawable.ic_launcher_foreground, R.drawable.logo_light),
    DARK("LauncherDark", R.mipmap.ic_launcher_dark, R.drawable.ic_launcher_dark_foreground, R.drawable.logo_dark),
    MONOCHROME("LauncherMonochrome", R.mipmap.ic_launcher_mono, R.drawable.ic_launcher_mono_foreground, R.drawable.logo_mono),
}

/** PackageManager persists the selection across restarts and app updates. */
internal class AppIconManager(context: Context) {
    private val packageManager = context.packageManager
    private val packageName = context.packageName

    private fun component(icon: AppIcon) = ComponentName(
        packageName,
        "com.leviknet.vpn.${icon.aliasName}",
    )

    fun current(): AppIcon = AppIcon.entries.firstOrNull { icon ->
        packageManager.getComponentEnabledSetting(component(icon)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } ?: AppIcon.LIGHT

    @Synchronized
    fun select(icon: AppIcon) {
        val previous = AppIcon.entries.associateWith {
            packageManager.getComponentEnabledSetting(component(it))
        }
        // Re-selecting the current icon must not invalidate launcher shortcuts again.
        val enabled = previous.filter { (entry, state) ->
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && entry == AppIcon.LIGHT)
        }.keys
        if (enabled == setOf(icon)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(AppIcon.entries.map {
                PackageManager.ComponentEnabledSetting(
                    component(it),
                    if (it == icon) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            })
        } else {
            // Enable the new launcher first so the app always remains launchable.
            try {
                packageManager.setComponentEnabledSetting(
                    component(icon), PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
                AppIcon.entries.filter { it != icon }.forEach {
                    packageManager.setComponentEnabledSetting(
                        component(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            } catch (error: RuntimeException) {
                // Restore originally enabled entries before disabling the new entry.
                previous.entries.sortedBy { (entry, state) ->
                    if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                        (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && entry == AppIcon.LIGHT)
                    ) 0 else 1
                }.forEach { (entry, state) ->
                    runCatching {
                        packageManager.setComponentEnabledSetting(component(entry), state, PackageManager.DONT_KILL_APP)
                    }.exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            }
        }
    }
}
