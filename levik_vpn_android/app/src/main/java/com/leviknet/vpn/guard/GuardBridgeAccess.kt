package com.leviknet.vpn.guard

import android.content.Context
import androidx.core.content.edit

/** Explicit local consent for sharing aggregate VPN status with Levik Guard. */
object GuardBridgeAccess {
    private const val PREFERENCES = "guard_bridge"
    private const val ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putBoolean(ENABLED, enabled)
        }
    }
}
