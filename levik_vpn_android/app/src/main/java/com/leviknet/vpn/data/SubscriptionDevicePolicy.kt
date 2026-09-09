package com.leviknet.vpn.data

import com.leviknet.vpn.core.network.ApiException
import java.io.IOException

internal fun canUseCachedProfileAfter(error: Throwable): Boolean = when (error) {
    is ApiException.Network, is IOException -> true
    is ApiException.Rejected -> error.retryable && (error.status >= 500 || error.status == 429)
    else -> false
}
