package com.leviknet.vpn

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri

internal fun Activity.openDistributionBatterySettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
            val settingsIntent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            runCatching { startActivity(settingsIntent) }.onFailure {
                val appDetailsIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = "package:$packageName".toUri()
                }
                runCatching { startActivity(appDetailsIntent) }
            }
        } else {
            val requestIntent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
            runCatching { startActivity(requestIntent) }.onFailure {
                val settingsIntent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                runCatching { startActivity(settingsIntent) }.onFailure {
                    val appDetailsIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = "package:$packageName".toUri()
                    }
                    runCatching { startActivity(appDetailsIntent) }
                }
            }
        }
    }
}
