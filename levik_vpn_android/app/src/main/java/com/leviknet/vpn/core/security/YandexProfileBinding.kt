package com.leviknet.vpn.core.security

import com.leviknet.vpn.vpn.TunnelProfile
import java.util.Base64

internal fun validateYandexProfileBinding(encodedAad: String, profile: TunnelProfile, deviceId: String) {
    require(encodedAad.length in 1..2731 && encodedAad.matches(Regex("[A-Za-z0-9_-]+")))
    val decoded = Base64.getUrlDecoder().decode(encodedAad)
    try {
        val fields = decoded.decodeToString(throwOnInvalidSequence = true).split('\n')
        val leaseRef = profile.telemostBootstrap?.leaseRef ?: requireNotNull(profile.yandexBootstrap).leaseRef
        val marker = if (profile.version == 4) "levik-mobile-telemost-profile-v4" else "levik-mobile-yandex-profile-v3"
        require(fields.size == 6 && fields[0] == marker &&
            fields[1] == deviceId &&
            fields[2].matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) &&
            fields[3] == profile.subscriptionId && fields[4] == leaseRef &&
            fields[5] == profile.profileId
        ) { "Yandex profile binding does not match the request" }
    } finally {
        decoded.fill(0)
    }
}
