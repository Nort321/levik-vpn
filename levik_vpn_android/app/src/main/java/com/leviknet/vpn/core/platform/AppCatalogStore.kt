package com.leviknet.vpn.core.platform

import com.leviknet.vpn.core.network.EndpointsFetcher
import com.leviknet.vpn.core.network.ServiceEndpoints
import com.leviknet.vpn.core.network.httpsFetcher
import java.io.File
import java.io.IOException
import java.security.PublicKey
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A signed catalog as published: the exact bytes and their signature. */
class SignedCatalog(val body: ByteArray, val signature: String)

/**
 * The newest correctly signed catalog: the copy built into the app, or a newer
 * one from the mirrors of the service host list. The downloaded bytes are kept
 * and verified again on load, so an edited copy is never used. Blocking calls:
 * use a background thread.
 */
class AppCatalogStore(
    private val file: File,
    private val builtIn: () -> SignedCatalog?,
    private val fetcher: EndpointsFetcher = httpsFetcher,
    /** Tests only; the apps use the Levik configuration key. */
    private val key: PublicKey? = null,
) {
    private val mutableCatalog = MutableStateFlow<AppCatalog?>(null)
    val catalog: StateFlow<AppCatalog?> = mutableCatalog.asStateFlow()

    private var checkedAtMs = 0L

    @Synchronized
    fun load() {
        val bundled = builtIn()?.let { AppCatalogPolicy.verify(it.body, it.signature, key) }
        val stored = readStored()
        checkedAtMs = (stored?.get("checkedAt") as? JsonPrimitive)?.longOrNull ?: 0L
        val saved = stored?.let(::signedFrom)?.let { AppCatalogPolicy.verify(it.body, it.signature, key) }
        mutableCatalog.value = listOfNotNull(bundled, saved).maxByOrNull(AppCatalog::sequence)
    }

    /** Looks for a newer catalog when the last check is older than [intervalMs]; true when it changed. */
    fun refreshIfDue(intervalMs: Long): Boolean {
        val now = System.currentTimeMillis()
        if (synchronized(this) { checkedAtMs } in (now - intervalMs + 1)..now) return false
        return refresh()
    }

    @Synchronized
    fun refresh(): Boolean {
        checkedAtMs = System.currentTimeMillis()
        val current = mutableCatalog.value?.sequence ?: 0L
        var newer: Pair<AppCatalog, SignedCatalog>? = null
        for (url in AppCatalogPolicy.urls(ServiceEndpoints.current().configUrls)) {
            try {
                val body = fetcher.get(url, AppCatalogPolicy.MAX_BYTES) ?: continue
                val signature = fetcher.get("$url.sig", ServiceEndpoints.MAX_SIGNATURE_BYTES)
                    ?.toString(Charsets.UTF_8) ?: continue
                val catalog = AppCatalogPolicy.verify(body, signature, key) ?: continue
                // An older copy is a stale mirror (jsDelivr caches for hours): ask the next one.
                if (catalog.sequence < current) continue
                if (catalog.sequence > current) newer = catalog to SignedCatalog(body, signature.trim())
                break
            } catch (_: IOException) {
                // A blocked or broken mirror: try the next one.
            }
        }
        newer?.let { mutableCatalog.value = it.first }
        save(newer?.second)
        return newer != null
    }

    private fun readStored(): JsonObject? =
        runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()

    private fun signedFrom(stored: JsonObject): SignedCatalog? {
        val body = (stored["body"] as? JsonPrimitive)?.content ?: return null
        val signature = (stored["signature"] as? JsonPrimitive)?.content ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(body) }.getOrNull() ?: return null
        return SignedCatalog(bytes, signature)
    }

    private fun save(fetched: SignedCatalog?) {
        val previous = readStored()
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
