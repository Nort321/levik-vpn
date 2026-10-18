package com.leviknet.vpn.core.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal const val DEFAULT_API_ORIGIN = "https://api.cloudnote-sync.xyz"

internal class ApiEndpoints(
    baseUrl: String,
    private val refreshEndpoints: () -> Boolean = { false },
    private val openHealthConnection: (URL) -> HttpsURLConnection = {
        it.openConnection() as HttpsURLConnection
    },
    private val nanoTime: () -> Long = System::nanoTime,
    private val probeBudgetNanos: Long = PROBE_BUDGET_NANOS,
) {
    /** Null in production: the hosts come from the signed endpoints document. */
    private val fixed: URL?
    private var selected: URL? = null
    private var verifiedUntilNanos = 0L

    init {
        require(probeBudgetNanos > 0L) { "API health probe budget must be positive" }
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
        selected?.let { if (nanoTime() < verifiedUntilNanos) return it }
        probe()?.let { return it }
        // When every known host fails, a newer signed document may name working ones.
        if (refreshEndpoints()) probe()?.let { return it }
        throw IOException("Levik API is unavailable")
    }

    private fun probe(): URL? {
        val origins = ServiceEndpoints.current().apiOrigins.map(::URL)
        if (origins.isEmpty()) return null
        // Put the mobile allow-list path in the first pool, even when an older signed
        // document still lists several blocked public hosts before the built-in fallback.
        val fallback = origins.firstOrNull { it.toString() == ServiceEndpoints.ALLOWLIST_API_ORIGIN }
        val candidates = (listOfNotNull(origins.first(), fallback) + origins).distinctBy(URL::toString)
        val executor = Executors.newFixedThreadPool(minOf(MAX_CONCURRENT_PROBES, candidates.size)) { task ->
            Thread(task, "levik-api-health").apply { isDaemon = true }
        }
        val completion = ExecutorCompletionService<URL?>(executor)
        val connections = mutableListOf<HttpsURLConnection>()
        val futures = mutableListOf<Future<URL?>>()
        val deadline = System.nanoTime() + probeBudgetNanos
        var healthyFallback: URL? = null
        try {
            candidates.forEach { origin ->
                val connection = try {
                    openHealthConnection(URL(origin, "/api/health"))
                } catch (_: IOException) {
                    return@forEach
                }
                connections += connection
                futures += completion.submit(java.util.concurrent.Callable {
                    try {
                        if (healthy(connection)) origin else null
                    } catch (_: IOException) {
                        null
                    } finally {
                        connection.disconnect()
                    }
                })
            }
            for (index in futures.indices) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) break
                val finished = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                val origin = try {
                    finished.get()
                } catch (_: ExecutionException) {
                    null
                }
                if (origin != null) {
                    if (origin.toString() == ServiceEndpoints.ALLOWLIST_API_ORIGIN) {
                        // The allow-list ingress shares a forwarding IP. Prefer a reachable
                        // ordinary origin so normal sign-ins keep their individual IP limits.
                        healthyFallback = origin
                    } else {
                        return rememberVerified(origin)
                    }
                }
            }
            return healthyFallback?.let(::rememberVerified)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Levik API health check interrupted", error)
        } finally {
            futures.forEach { it.cancel(true) }
            // Interrupts alone do not reliably cancel Android's blocking HTTPS sockets.
            connections.forEach { connection -> runCatching { connection.disconnect() } }
            executor.shutdownNow()
        }
    }

    private fun rememberVerified(origin: URL): URL {
        selected = origin
        verifiedUntilNanos = nanoTime() + VERIFIED_ORIGIN_TTL_NANOS
        return origin
    }

    private fun healthy(connection: HttpsURLConnection): Boolean {
        // Only this unauthenticated GET races. Signed API requests remain single-shot.
        connection.requestMethod = "GET"
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        if (connection.responseCode != 200 ||
            !connection.contentType.orEmpty().contains("application/json", ignoreCase = true) ||
            connection.contentLengthLong > MAX_HEALTH_BYTES
        ) return false
        val body = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(256)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                if (output.size() > MAX_HEALTH_BYTES) return false
            }
            output.toByteArray()
        }
        val payload = runCatching { Json.parseToJsonElement(body.decodeToString()) as? JsonObject }.getOrNull()
        return (payload?.get("ok") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
    }

    private companion object {
        const val MAX_CONCURRENT_PROBES = 4
        const val MAX_HEALTH_BYTES = 1_024
        const val PROBE_BUDGET_NANOS = 10_000_000_000L
        const val VERIFIED_ORIGIN_TTL_NANOS = 60_000_000_000L
    }
}
