package com.leviknet.vpn.core.network

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Where the app finds the Levik servers. A document signed with the Levik
 * configuration key (config/levik-config-v1.json in the main repository) replaces
 * the built-in host names, so a blocked domain is swapped without an app update.
 * Only a correctly signed document that is not older than the stored one is used.
 */
data class ServiceEndpointsDocument(
    val sequence: Long,
    val apiOrigins: List<String>,
    val webOrigin: String,
    val subscriptionOrigins: List<String>,
    val configUrls: List<String>,
)

object ServiceEndpoints {
    internal const val ALLOWLIST_API_ORIGIN = "https://leva.levikfartik.ru"

    val BUILT_IN = ServiceEndpointsDocument(
        sequence = 0,
        apiOrigins = listOf(
            "https://api.cloudnote-sync.xyz",
            "https://api.leviknet.org",
            "https://leviknet.org",
            "https://leviknet.com",
            ALLOWLIST_API_ORIGIN,
        ),
        webOrigin = "https://leviknet.org",
        subscriptionOrigins = listOf("https://sub.cloudnote-sync.xyz", "https://sub.leviknet.org"),
        configUrls = listOf(
            "https://api.cloudnote-sync.xyz/downloads/config/levik-config-v1.json",
            "https://api.leviknet.org/downloads/config/levik-config-v1.json",
            "https://raw.githubusercontent.com/Nort321/levik-vpn/main/config/levik-config-v1.json",
            "https://cdn.jsdelivr.net/gh/Nort321/levik-vpn@main/config/levik-config-v1.json",
        ),
    )

    /** ECDSA P-256 public key (SPKI, base64). */
    private const val CONFIG_PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEBxwZK9Ledu9WcstW72cqoVTP9AjtB5dzNIhqmvTlm0lXRaqspdY1KbwCnzhjgCY10hAi9CL37PxhY/GGaIdAdQ=="
    internal const val MAX_DOCUMENT_BYTES = 16 * 1024
    internal const val MAX_SIGNATURE_BYTES = 512
    private const val MAX_ITEMS = 8
    private const val MAX_VALUE_LENGTH = 300
    private val HOST = Regex(
        "^(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$",
    )
    private val SIGNATURE = Regex("^[A-Za-z0-9+/]+={0,2}$")
    private val LEGACY_WEB_HOSTS = setOf("leviknet.org", "leviknet.com")

    private val configKey: PublicKey by lazy {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(CONFIG_PUBLIC_KEY)))
    }

    @Volatile
    private var current: ServiceEndpointsDocument = merged(BUILT_IN)

    fun current(): ServiceEndpointsDocument = current

    fun use(document: ServiceEndpointsDocument?) {
        current = merged(document ?: BUILT_IN)
    }

    /** An absolute address on the current Levik website. */
    fun webUrl(pathAndQuery: String): String = current.webOrigin + pathAndQuery

    /** Levik website hosts: the original domains and the signed website, if it moved. */
    fun websiteHosts(): Set<String> = LEGACY_WEB_HOSTS + URI(current.webOrigin).host

    /** True when [signature] was made with the Levik configuration key over exactly these bytes. */
    internal fun signatureValid(body: ByteArray, signature: String, key: PublicKey? = null): Boolean {
        if (body.isEmpty() || signature.length > MAX_SIGNATURE_BYTES) return false
        val encoded = signature.trim()
        if (!SIGNATURE.matches(encoded)) return false
        return runCatching {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key ?: configKey)
            verifier.update(body)
            verifier.verify(Base64.getDecoder().decode(encoded))
        }.getOrDefault(false)
    }

    /** Verifies the signature over the exact bytes, then the content. Null when anything is off. */
    fun verify(body: ByteArray, signature: String, key: PublicKey? = null): ServiceEndpointsDocument? {
        if (body.size > MAX_DOCUMENT_BYTES || !signatureValid(body, signature, key)) return null
        return runCatching {
            val document = Json.parseToJsonElement(String(body, Charsets.UTF_8)) as? JsonObject ?: return null
            if ((document["schema"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull != 1) return null
            val sequence = (document["sequence"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            if (sequence == null || sequence < 1) return null
            val apiOrigins = list(document["apiOrigins"], ::cleanOrigin)
            val webOrigin = cleanOrigin(document["webOrigin"])
            if (apiOrigins.isEmpty() || webOrigin == null) return null
            ServiceEndpointsDocument(
                sequence = sequence,
                apiOrigins = apiOrigins,
                webOrigin = webOrigin,
                subscriptionOrigins = list(document["subscriptionOrigins"], ::cleanOrigin),
                configUrls = list(document["configUrls"], ::cleanConfigUrl),
            )
        }.getOrNull()
    }

    /** The document's hosts come first; the built-in ones stay as a last resort. */
    private fun merged(document: ServiceEndpointsDocument) = ServiceEndpointsDocument(
        sequence = document.sequence,
        apiOrigins = (document.apiOrigins + BUILT_IN.apiOrigins).distinct(),
        webOrigin = document.webOrigin,
        subscriptionOrigins = (document.subscriptionOrigins + BUILT_IN.subscriptionOrigins).distinct(),
        configUrls = (document.configUrls + BUILT_IN.configUrls).distinct(),
    )

    private fun list(value: Any?, clean: (Any?) -> String?): List<String> =
        (value as? JsonArray).orEmpty().mapNotNull(clean).distinct().take(MAX_ITEMS)

    private fun text(value: Any?): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= MAX_VALUE_LENGTH }

    private fun httpsUri(value: Any?): URI? {
        val uri = text(value)?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        val host = uri.host ?: return null
        if (uri.scheme != "https" || host != host.lowercase(Locale.ROOT) || !HOST.matches(host) ||
            uri.port != -1 || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null
        ) {
            return null
        }
        return uri
    }

    private fun cleanOrigin(value: Any?): String? {
        val uri = httpsUri(value) ?: return null
        if (uri.rawPath.orEmpty() !in setOf("", "/")) return null
        return "https://${uri.host}"
    }

    private fun cleanConfigUrl(value: Any?): String? {
        val uri = httpsUri(value) ?: return null
        if (!uri.rawPath.orEmpty().endsWith(".json")) return null
        return uri.toString()
    }
}

/** Reads a small file over HTTPS; null when it is missing, too large or unreachable. */
fun interface EndpointsFetcher {
    fun get(url: String, limit: Int): ByteArray?
}

internal val httpsFetcher = httpsFetcherWithDeadline(null)

private fun httpsFetcherWithDeadline(deadlineNanos: Long?) = EndpointsFetcher { url, limit ->
    fun remainingTimeoutMillis(): Int {
        if (deadlineNanos == null) return 10_000
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0L) throw SocketTimeoutException("Service endpoints discovery timed out")
        return minOf(10_000L, (remaining / 1_000_000L).coerceAtLeast(1L)).toInt()
    }
    val connection = URL(url).openConnection() as HttpsURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = remainingTimeoutMillis()
        connection.readTimeout = remainingTimeoutMillis()
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Cache-Control", "no-store")
        connection.connect()
        connection.readTimeout = remainingTimeoutMillis()
        if (connection.responseCode != 200 || connection.contentLengthLong > limit) return@EndpointsFetcher null
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            while (true) {
                connection.readTimeout = remainingTimeoutMillis()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                if (output.size() > limit) return@EndpointsFetcher null
            }
            output.toByteArray()
        }
    } finally {
        connection.disconnect()
    }
}

data class FetchedEndpoints(val document: ServiceEndpointsDocument, val body: ByteArray, val signature: String)

/** Downloads the first valid document that is at least as new as [minSequence]. */
fun fetchEndpointsDocument(
    urls: List<String>,
    minSequence: Long,
    fetcher: EndpointsFetcher = httpsFetcher,
): FetchedEndpoints? {
    // A mobile allow-list can block every configuration mirror. Keep discovery within
    // one budget instead of waiting a full connect/read timeout for each body/signature.
    val deadline = System.nanoTime() + 12_000_000_000L
    val boundedFetcher = if (fetcher === httpsFetcher) httpsFetcherWithDeadline(deadline) else fetcher
    for (url in urls) {
        if (System.nanoTime() >= deadline) return null
        try {
            val body = boundedFetcher.get(url, ServiceEndpoints.MAX_DOCUMENT_BYTES) ?: continue
            val signature = boundedFetcher.get("$url.sig", ServiceEndpoints.MAX_SIGNATURE_BYTES)
                ?.toString(Charsets.UTF_8) ?: continue
            val document = ServiceEndpoints.verify(body, signature) ?: continue
            if (document.sequence >= minSequence) return FetchedEndpoints(document, body, signature)
        } catch (_: IOException) {
            // A blocked or broken mirror: try the next one.
        }
    }
    return null
}

/**
 * Keeps the signed bytes on disk and verifies them again on load, so an edited
 * copy cannot redirect the app. Blocking calls: use a background thread.
 */
class ServiceEndpointsStore(
    private val file: File,
    private val fetcher: EndpointsFetcher = httpsFetcher,
) {
    private var lastAttemptNanos = 0L
    private var checkedAtMs = 0L

    @Synchronized
    fun load() {
        val stored = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return
        checkedAtMs = (stored["checkedAt"] as? JsonPrimitive)?.longOrNull ?: 0L
        val body = (stored["body"] as? JsonPrimitive)?.content ?: return
        val signature = (stored["signature"] as? JsonPrimitive)?.content ?: return
        val bytes = runCatching { Base64.getDecoder().decode(body) }.getOrNull() ?: return
        ServiceEndpoints.use(ServiceEndpoints.verify(bytes, signature))
    }

    /** Looks for a newer document at most once per [minIntervalMs]; true when the hosts changed. */
    @Synchronized
    fun refresh(minIntervalMs: Long = 0L): Boolean {
        val now = System.nanoTime()
        if (lastAttemptNanos != 0L && now - lastAttemptNanos < minIntervalMs * 1_000_000L) return false
        lastAttemptNanos = now
        checkedAtMs = System.currentTimeMillis()
        val before = ServiceEndpoints.current()
        val fetched = fetchEndpointsDocument(before.configUrls, maxOf(1L, before.sequence), fetcher)
        val newer = fetched?.takeUnless { it.document.sequence == before.sequence && before.sequence > 0 }
        newer?.let { ServiceEndpoints.use(it.document) }
        save(newer)
        return newer != null
    }

    /** The twice-daily check; the time of the last one survives process restarts. */
    fun refreshIfDue(intervalMs: Long): Boolean {
        val checked = synchronized(this) { checkedAtMs }
        val now = System.currentTimeMillis()
        if (checked in (now - intervalMs + 1)..now) return false
        return refresh()
    }

    private fun save(fetched: FetchedEndpoints?) {
        val previous = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        val content = buildJsonObject {
            if (fetched != null) {
                put("body", Base64.getEncoder().encodeToString(fetched.body))
                put("signature", fetched.signature)
            } else {
                previous?.get("body")?.let { put("body", it) }
                previous?.get("signature")?.let { put("signature", it) }
            }
            put("checkedAt", checkedAtMs)
        }
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(content.toString())
            if (!temporary.renameTo(file)) temporary.delete()
        }
    }
}
