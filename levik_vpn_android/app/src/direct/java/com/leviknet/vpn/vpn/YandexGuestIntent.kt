package com.leviknet.vpn.vpn

import android.content.Context
import android.content.Intent
import android.os.Build

internal fun createYandexGuestIntent(context: Context, documentUrl: String): Intent? {
    if (Build.VERSION.SDK_INT < 28) return null
    val network = selectYandexGuestNetwork(context) ?: return null
    return Intent(context, YandexGuestActivity::class.java)
        .putExtra("documentUrl", YandexContract.validateDocumentUrl(documentUrl))
        .putExtra("networkHandle", network.networkHandle)
}
