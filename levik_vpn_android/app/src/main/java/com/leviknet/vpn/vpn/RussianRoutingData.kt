package com.leviknet.vpn.vpn

import android.content.Context

class RussianRoutingData(private val context: Context) {
    val cidrs: List<String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        listOf(IPV4_ASSET, IPV6_ASSET).flatMap(::readCidrs).also { routes ->
            check(routes.isNotEmpty()) { "Russian routing data is empty" }
        }
    }

    private fun readCidrs(assetName: String): List<String> =
        context.assets.open(assetName).bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotEmpty).toList()
        }

    companion object {
        private const val IPV4_ASSET = "ru_ipv4.cidr"
        private const val IPV6_ASSET = "ru_ipv6.cidr"
    }
}
