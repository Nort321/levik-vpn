package com.leviknet.vpn.core.network

import com.leviknet.vpn.vpn.ServiceRouteEndpoint
import com.leviknet.vpn.vpn.ServiceRouteProfile
import kotlinx.serialization.Serializable

@Serializable
data class ServiceRouteProfileRequest(val subscriptionId: String, val endpoint: ServiceRouteEndpoint)

@Serializable
data class ServiceRouteProfileResponse(
    val ok: Boolean,
    val applicable: Boolean,
    val profile: ServiceRouteProfile? = null,
    val reason: String? = null,
)
