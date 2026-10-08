package com.leviknet.vpn.vpn

internal fun xrayTunPlan(primaryDns: String, secondaryDns: String, ipv6Enabled: Boolean = true) = TunPlan(
    mtu = XrayConfigBuilder.TUN_MTU,
    addresses = buildList {
        add(TunAddress("172.30.0.2", 30))
        if (ipv6Enabled) add(TunAddress("2600:1900:4000:5255::2", 64))
    },
    // IPv4-only plans must also omit IPv6 routes: Android then blocks that
    // family before a connection can enter the IPv4-only native transport.
    dnsServers = listOf(primaryDns, secondaryDns).distinct(),
    ipv6Enabled = ipv6Enabled,
)
