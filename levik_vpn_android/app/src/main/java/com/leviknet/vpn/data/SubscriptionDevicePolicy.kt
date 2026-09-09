package com.leviknet.vpn.data

import com.leviknet.vpn.core.network.ApiException
import com.leviknet.vpn.core.network.ApiProblemDetails
import com.leviknet.vpn.core.network.SubscriptionSummary
import java.io.IOException

internal fun deviceSlotProblem(subscription: SubscriptionSummary, deviceId: String): ApiProblemDetails? {
    val components = subscription.components
    val groups = if (components == null) listOf(null to subscription.devices)
    else listOf("regular" to components.regular.devices, "mobile" to components.mobile.devices)
    return groups.firstNotNullOfOrNull { (component, devices) ->
        val registered = devices.items.any {
            it.id == deviceId || (component != null && it.id == "$component:$deviceId")
        }
        if (!registered && devices.items.size >= devices.used && devices.limit > 0 && devices.used >= devices.limit) {
            ApiProblemDetails(subscription.uuid, component, devices.used, devices.limit)
        } else null
    }
}

internal fun canUseCachedProfileAfter(error: Throwable): Boolean = when (error) {
    is ApiException.Network, is IOException -> true
    is ApiException.Rejected -> error.retryable && (error.status >= 500 || error.status == 429)
    else -> false
}
