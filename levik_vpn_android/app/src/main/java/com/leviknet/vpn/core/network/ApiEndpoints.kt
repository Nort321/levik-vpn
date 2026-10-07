package com.leviknet.vpn.core.network

import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal const val DEFAULT_API_ORIGIN = "https://api.leviknet.org"

internal class ApiEndpoints(baseUrl: String) {
    private val origins: List<URL>
    private var selected: URL? = null
    private var verifiedUntilNanos = 0L

    init {
        val origin = URL(baseUrl)
        require(origin.protocol == "https" && origin.host.isNotBlank() &&
            origin.userInfo == null && origin.query == null && origin.ref == null &&
            (origin.path.isEmpty() || origin.path == "/")) {
            "Mobile API requires an HTTPS origin without credentials, path, query or fragment"
        }
        origins = if (baseUrl == DEFAULT_API_ORIGIN) {
            listOf(DEFAULT_API_ORIGIN, "https://leviknet.org", "https://leviknet.com").map(::URL)
        } else {
            listOf(origin)
        }
    }

    @Synchronized
    fun invalidate() {
        selected = null
        verifiedUntilNanos = 0L
    }

    @Synchronized
    fun resolve(): URL {
        if (origins.size == 1) return origins.first()
        selected?.let { if (System.nanoTime() < verifiedUntilNanos) return it }
        for (origin in origins) {
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
                // Only the built-in HTTPS origins can receive signed requests.
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Levik API is unavailable")
    }
}
