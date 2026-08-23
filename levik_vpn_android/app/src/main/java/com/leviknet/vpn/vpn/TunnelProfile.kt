package com.leviknet.vpn.vpn

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class TunnelProfile(
    val version: Int,
    val profileId: String,
    val subscriptionId: String,
    val issuedAt: String,
    val subscriptionExpiresAt: String? = null,
    val source: TunnelProfileSource,
    val routing: TunnelRouting? = null,
)

@Serializable
data class TunnelProfileSource(
    val mediaType: String,
    val content: String,
)

@Serializable
data class TunnelRouting(
    val directCidrs: List<String> = emptyList(),
)

@Serializable
data class PreparedTunnelProfile(
    val version: Int,
    val profileId: String,
    val subscriptionId: String,
    val issuedAt: String,
    val subscriptionExpiresAt: String? = null,
    val servers: List<TunnelServer>,
    val directCidrs: List<String> = emptyList(),
)

@Serializable
data class TunnelServer(
    val id: String,
    val tag: String,
    val name: String,
    val countryCode: String,
    val outbound: JsonObject,
)
