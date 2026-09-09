package com.leviknet.vpn

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

internal fun Activity.openDistributionBatterySettings() {
    runCatching {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }.onFailure {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            })
        }
    }
}
