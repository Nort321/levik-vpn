package com.leviknet.vpn.vpn

internal fun xrayTunPlan(primaryDns: String, secondaryDns: String) = TunPlan(
    mtu = XrayConfigBuilder.TUN_MTU,
    addresses = listOf(
        TunAddress("172.30.0.2", 30),
        TunAddress("2600:1900:4000:5255::2", 64),
    ),
    // Keep the IPv6 route to prevent leaks, but don't advertise resolvers whose
    // transport is intentionally blocked by XrayConfigBuilder's IPv6 rule.
    dnsServers = listOf(primaryDns, secondaryDns).distinct(),
)
