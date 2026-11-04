package com.leviknet.vpn.core.network

import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal const val ALLOWLIST_PROBES_PATH = "/api/mobile/v1/allowlist-probes"

/**
 * Keeps the newest signed probe catalog. The signed bytes stay on disk and are verified
 * again on every load, so an edited file cannot change the probes. Blocking calls: use a
 * background thread. Downloads go through every API host, including the LTE CDN path that
 * answers under allowlists, in one bounded budget.
 */
class AllowlistProbeCatalogStore(
    private val file: File,
    private val fetcher: EndpointsFetcher = httpsFetcher,
    private val origins: () -> List<String> = { ServiceEndpoints.current().apiOrigins },
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val verify: (ByteArray, String) -> AllowlistProbeCatalog? = { payload, signature ->
        AllowlistProbeCatalog.verify(payload, signature)
    },
) {
    @Volatile
    private var stored: AllowlistProbeCatalog? = null
    private var nextAttemptElapsedMs = 0L
    private var loaded = false

    /** The catalog in force: the stored one while it is valid, otherwise the built-in one. */
    fun current(): AllowlistProbeCatalog {
        val catalog = stored ?: return AllowlistProbeCatalog.BUILT_IN
        return if (catalog.expired(wallMillis())) AllowlistProbeCatalog.BUILT_IN else catalog
    }

    @Synchronized
    fun load() {
        loaded = true
        val saved = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return
        val payload = (saved["payload"] as? JsonPrimitive)?.content ?: return
        val signature = (saved["signature"] as? JsonPrimitive)?.content ?: return
        val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return
        stored = verify(bytes, signature)
    }

    /**
     * Looks for a newer catalog when one is due: every 6 hours after a success, 30 minutes
     * after a failure. True when a newer catalog was stored.
     */
    @Synchronized
    fun refreshIfDue(): Boolean {
        if (!loaded) load()
        val now = elapsedMillis()
        if (nextAttemptElapsedMs != 0L && now < nextAttemptElapsedMs) return false
        val fetched = download()
        nextAttemptElapsedMs = now + if (fetched != null) SUCCESS_INTERVAL_MS else FAILURE_INTERVAL_MS
        if (fetched == null) return false
        val (catalog, payload, signature) = fetched
        val previous = stored
        if (previous != null && catalog.sequence <= previous.sequence) return false
        val text = buildJsonObject {
            put("payload", Base64.getEncoder().encodeToString(payload))
            put("signature", signature)
        }.toString()
        // Write-then-rename: a crash never leaves a half-written catalog behind.
        val temporary = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            temporary.writeText(text)
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        }
        stored = catalog
        return true
    }

    private fun download(): Triple<AllowlistProbeCatalog, ByteArray, String>? {
        val deadline = elapsedMillis() + BUDGET_MS
        // The LTE CDN host goes second: it is the one that answers under allowlists.
        val hosts = origins().let { all ->
            (listOfNotNull(all.firstOrNull(), all.firstOrNull { it == ServiceEndpoints.ALLOWLIST_API_ORIGIN }) + all).distinct()
        }
        for (origin in hosts) {
            if (elapsedMillis() >= deadline) return null
            try {
                val body = fetcher.get(origin + ALLOWLIST_PROBES_PATH, ENVELOPE_LIMIT) ?: continue
                val envelope = Json.parseToJsonElement(String(body, Charsets.UTF_8)) as? JsonObject ?: continue
                if ((envelope["schema"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull != 1L) continue
                val payload = (envelope["payload"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val signature = (envelope["signature"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: continue
                val catalog = verify(bytes, signature) ?: continue
                return Triple(catalog, bytes, signature)
            } catch (_: IOException) {
                // A blocked host: try the next one.
            } catch (_: IllegalArgumentException) {
                // Not JSON: a captive portal or an error page.
            }
        }
        return null
    }

    private companion object {
        const val SUCCESS_INTERVAL_MS = 6 * 60 * 60_000L
        const val FAILURE_INTERVAL_MS = 30 * 60_000L
        const val BUDGET_MS = 20_000L
        const val ENVELOPE_LIMIT = AllowlistProbeCatalog.MAX_BYTES * 2
    }
}

/**
 * The region and operator of the cellular network, learned from the map's region token and
 * kept per mobile operator code (MCC+MNC) for a day, so the detector uses the regional set
 * right after a network change. Stored only on the device.
 */
class AllowlistScopeMemory(
    private val file: File,
    private val wallMillis: () -> Long = System::currentTimeMillis,
) {
    private var entries: MutableMap<String, Pair<AllowlistProbeScope, Long>>? = null

    @Synchronized
    fun remember(operatorCode: String?, scope: AllowlistProbeScope) {
        val key = operatorCode?.takeIf(OPERATOR::matches) ?: return
        val map = read()
        map[key] = scope to wallMillis()
        map.entries.removeAll { wallMillis() - it.value.second > TTL_MS }
        runCatching {
            file.writeText(buildJsonObject {
                map.forEach { (code, value) ->
                    put(code, buildJsonObject {
                        put("region", value.first.region)
                        value.first.asn?.let { put("asn", it) }
                        put("at", value.second)
                    })
                }
            }.toString())
        }
    }

    @Synchronized
    fun scopeFor(operatorCode: String?): AllowlistProbeScope? {
        val key = operatorCode?.takeIf(OPERATOR::matches) ?: return null
        val (scope, at) = read()[key] ?: return null
        val age = wallMillis() - at
        return scope.takeIf { age in 0..TTL_MS }
    }

    private fun read(): MutableMap<String, Pair<AllowlistProbeScope, Long>> {
        entries?.let { return it }
        val parsed = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        val map = mutableMapOf<String, Pair<AllowlistProbeScope, Long>>()
        parsed?.forEach { (code, value) ->
            val item = value as? JsonObject ?: return@forEach
            val region = (item["region"] as? JsonPrimitive)?.content ?: return@forEach
            val at = (item["at"] as? JsonPrimitive)?.longOrNull ?: return@forEach
            val asn = (item["asn"] as? JsonPrimitive)?.longOrNull
            if (OPERATOR.matches(code) && REGION.matches(region)) map[code] = AllowlistProbeScope(region, asn) to at
        }
        entries = map
        return map
    }

    private companion object {
        const val TTL_MS = 24 * 60 * 60_000L
        val OPERATOR = Regex("^[0-9]{5,6}$")
        val REGION = Regex("^RU-[A-Z]{2,3}$")
    }
}
