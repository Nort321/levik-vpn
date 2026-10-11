package com.leviknet.vpn.core.network

import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal const val DEFAULT_API_ORIGIN = "https://api.cloudnote-sync.xyz"

internal class ApiEndpoints(
    baseUrl: String,
    private val refreshEndpoints: () -> Boolean = { false },
) {
    /** Null in production: the hosts come from the signed endpoints document. */
    private val fixed: URL?
    private var selected: URL? = null
    private var verifiedUntilNanos = 0L

    init {
        val origin = URL(baseUrl)
        require(origin.protocol == "https" && origin.host.isNotBlank() &&
            origin.userInfo == null && origin.query == null && origin.ref == null &&
            (origin.path.isEmpty() || origin.path == "/")) {
            "Mobile API requires an HTTPS origin without credentials, path, query or fragment"
        }
        // An explicit development override must never receive production
        // credentials through an implicit cross-origin retry.
        fixed = if (baseUrl == DEFAULT_API_ORIGIN) null else origin
    }

    @Synchronized
    fun invalidate() {
        selected = null
        verifiedUntilNanos = 0L
    }

    @Synchronized
    fun resolve(): URL {
        fixed?.let { return it }
        selected?.let { if (System.nanoTime() < verifiedUntilNanos) return it }
        probe()?.let { return it }
        // When every known host fails, a newer signed document may name working ones.
        if (refreshEndpoints()) probe()?.let { return it }
        throw IOException("Levik API is unavailable")
    }

    private fun probe(): URL? {
        for (origin in ServiceEndpoints.current().apiOrigins.map(::URL)) {
            val connection = URL(origin, "/api/health").openConnection() as HttpsURLConnection
            try {
                // Probe without session credentials before sending a mutation.
                // A POST is never replayed after an ambiguous network failure.
                connection.requestMethod = "GET"
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode == 200 &&
                    connection.contentType.orEmpty().contains("application/json", ignoreCase = true)) {
                    selected = origin
                    verifiedUntilNanos = System.nanoTime() + 60_000_000_000L
                    return origin
                }
            } catch (_: IOException) {
                // Only signed or built-in HTTPS origins can receive signed requests.
            } finally {
                connection.disconnect()
            }
        }
        return null
    }
}
