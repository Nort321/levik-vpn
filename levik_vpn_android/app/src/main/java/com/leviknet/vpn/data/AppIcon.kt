package com.leviknet.vpn.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

enum class AppIcon(val aliasName: String) {
    LIGHT("LauncherLight"),
    DARK("LauncherDark"),
    MONOCHROME("LauncherMonochrome"),
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
        val state = packageManager.getComponentEnabledSetting(component(icon))
        state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == AppIcon.LIGHT)
    } ?: AppIcon.LIGHT

    fun select(icon: AppIcon) {
        val previous = AppIcon.entries.associateWith {
            packageManager.getComponentEnabledSetting(component(it))
        }
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
