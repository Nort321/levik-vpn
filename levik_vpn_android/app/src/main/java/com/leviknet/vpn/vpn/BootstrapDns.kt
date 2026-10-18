package com.leviknet.vpn.vpn

import java.net.Inet6Address
import java.net.InetAddress

/** The resolver belongs to the physical network, independently of DNS inside the VPN. */
internal fun selectBootstrapDnsServer(dnsServers: List<InetAddress>): String? =
    dnsServers.firstNotNullOfOrNull { address ->
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) {
            return@firstNotNullOfOrNull null
        }
        val host = address.hostAddress ?: return@firstNotNullOfOrNull null
        if (address is Inet6Address) "[$host]:53" else "$host:53"
    }

/** Numeric upstreams can connect even before the network publishes its DNS configuration. */
internal fun requiresBootstrapDns(endpointHost: String?): Boolean {
    val host = endpointHost?.removeSurrounding("[", "]") ?: return true
    if (':' in host) {
        // Validate a literal without allowing InetAddress to perform a hostname lookup.
        return !host.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' } ||
            runCatching { InetAddress.getByName(host) }.isFailure
    }
    val octets = host.split('.')
    return octets.size != 4 || octets.any { octet ->
        octet.isEmpty() || !octet.all(Char::isDigit) || octet.toIntOrNull() !in 0..255
    }
}
