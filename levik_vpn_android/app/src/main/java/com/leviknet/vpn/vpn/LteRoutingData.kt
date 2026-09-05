package com.leviknet.vpn.vpn

import android.content.Context

/** Immutable, release-pinned allow-list used only by the system LTE routing profile. */
class LteRoutingData(private val context: Context) {
    val cidrs: List<String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        readAsset(CIDR_ASSET, ::isValidIpv4Cidr).also { routes ->
            check(routes.size >= MIN_CIDR_COUNT) { "LTE CIDR routing data is incomplete" }
        }
    }

    val domains: List<String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        readAsset(DOMAIN_ASSET, ::isValidDomain).map { "domain:$it" }.also { domains ->
            check(domains.size >= MIN_DOMAIN_COUNT) { "LTE domain routing data is incomplete" }
        }
    }

    private fun readAsset(
        assetName: String,
        validator: (String) -> Boolean,
    ): List<String> = context.assets.open(assetName).bufferedReader().useLines { lines ->
        lines
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .onEach { value -> check(validator(value)) { "Invalid LTE routing entry" } }
            .distinct()
            .toList()
    }

    private fun isValidIpv4Cidr(value: String): Boolean {
        val (address, prefix) = value.split('/', limit = 2).takeIf { it.size == 2 }
            ?: return false
        val octets = address.split('.')
        return octets.size == 4 &&
            octets.all { octet ->
                octet.isNotEmpty() &&
                    (octet == "0" || !octet.startsWith('0')) &&
                    octet.toIntOrNull() in 0..255
            } &&
            prefix.toIntOrNull() in 0..32
    }

    private fun isValidDomain(value: String): Boolean =
        value.length in 1..253 &&
            value == value.lowercase() &&
            !value.startsWith('.') &&
            !value.endsWith('.') &&
            value.split('.').all { label ->
                label.length in 1..63 &&
                    label.first().isLetterOrDigit() &&
                    label.last().isLetterOrDigit() &&
                    label.all { it.isLetterOrDigit() || it == '-' }
            }

    private companion object {
        const val CIDR_ASSET = "lte_whitelist_ipv4.cidr"
        const val DOMAIN_ASSET = "lte_whitelist_domains.txt"
        const val MIN_CIDR_COUNT = 25_000
        const val MIN_DOMAIN_COUNT = 700
    }
}
