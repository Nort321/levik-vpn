package com.leviknet.vpn.vpn

import android.content.Context
import android.net.Network

/** Guest WebView and native transport are excluded from the Play distribution. */
internal suspend fun refreshYandexGuest(context: Context, documentUrl: String, network: Network): YandexProviderAuth? = null
