package com.leviknet.vpn.core.network

import java.net.URI
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** One site the detector measures, docs/whitelist-probes.md. */
data class AllowlistProbe(
    val id: String,
    val url: String,
    val domestic: Boolean,
    /** A body of at least this many bytes counts; 0 = any HTTP answer. */
    val minBytes: Int,
)

data class AllowlistProbeSet(val domestic: List<String>, val foreign: List<String>)

/** Region ("RU-KOS") and, when known, the operator network of the physical connection. */
data class AllowlistProbeScope(val region: String, val asn: Long?) {
    /** Most specific first: the operator's own set, then the region's. */
    fun keys(): List<String> = listOfNotNull(asn?.let { "$region/$it" }, region)

    companion object {
        private val REGION = Regex("^RU-[A-Z]{2,3}$")

        /** Reads the claims of a map region token; the server signs them, the app only selects probes by them. */
        fun fromRegionToken(token: String): AllowlistProbeScope? = runCatching {
            val body = token.substringBefore('.', missingDelimiterValue = "")
            if (body.isEmpty() || body.length > 512) return null
            val claims = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8)) as? JsonObject
                ?: return null
            val region = (claims["region"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (!REGION.matches(region)) return null
            val asn = (claims["asn"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                ?.takeIf { it in 1L..4_294_967_295L }
            AllowlistProbeScope(region, asn)
        }.getOrNull()
    }
}

/**
 * The signed probe catalog. The server learns per region and operator which sites open
 * under allowlists and which foreign sites an operator caches; the app only selects.
 * Every rule that keeps detection safe is enforced here as well, so a broken or hostile
 * catalog falls back to [BUILT_IN] instead of weakening the detector.
 */
class AllowlistProbeCatalog internal constructor(
    val sequence: Long,
    val expiresAtMs: Long,
    private val probes: Map<String, AllowlistProbe>,
    private val default: AllowlistProbeSet,
    private val scopes: Map<String, AllowlistProbeSet>,
    private val candidates: List<String>,
) {
    /** The probes that decide the mode: the most specific set known for [scope]. */
    fun probesFor(scope: AllowlistProbeScope?): List<AllowlistProbe> {
        val set = scope?.keys()?.firstNotNullOfOrNull(scopes::get) ?: default
        return (set.domestic + set.foreign).mapNotNull(probes::get)
    }

    fun setNameFor(scope: AllowlistProbeScope?): String =
        scope?.keys()?.firstOrNull { it in scopes } ?: "default"

    /**
     * Up to [count] other sites, measured on the side for learning. They never change the
     * answer. Random, so every candidate gets measured in every region over time.
     */
    fun candidatesFor(scope: AllowlistProbeScope?, count: Int, random: Random = Random.Default): List<AllowlistProbe> {
        if (count <= 0) return emptyList()
        val used = probesFor(scope).mapTo(HashSet()) { it.id }
        return candidates.filterNot(used::contains).shuffled(random).take(count).mapNotNull(probes::get)
    }

    fun expired(nowMs: Long): Boolean = nowMs >= expiresAtMs

    companion object {
        /** ECDSA P-256 (SPKI, base64). Separate from the endpoints key: the server signs hourly. */
        private const val PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE2/aEq61oVXvLp58jQiYCTM9EuUcqZCXsj+hj3R1oWzHOcIrpytIjDSYbStmjvwc4qrIiecXusAxkFDB7uKSOAw=="
        internal const val MAX_BYTES = 64 * 1024
        internal const val MAX_PROBES = 64
        private const val MAX_SCOPES = 400
        /** Beeline passes handshakes and a few kilobytes under allowlists; a foreign site must deliver more. */
        internal const val MIN_FOREIGN_BYTES = 16 * 1024
        private const val FOREIGN_BYTES = 32 * 1024
        private val DOMESTIC_SIZE = 3..8
        private val FOREIGN_SIZE = 4..8
        private val ID = Regex("^[a-z][a-z0-9-]{1,23}$")
        private val SCOPE = Regex("^RU-[A-Z]{2,3}(/[1-9][0-9]{0,9})?$")
        private val HOST = Regex(
            "^(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$",
        )
        private val PATH = Regex("^/[A-Za-z0-9._~%/=&?@-]*$")

        private val publicKey: PublicKey by lazy {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)))
        }

        private fun probe(id: String, url: String, domestic: Boolean) =
            AllowlistProbe(id, url, domestic, if (domestic) 0 else FOREIGN_BYTES)

        /** The probes of Android 2.8.x: used until a signed catalog arrives, and after it expires. */
        val BUILT_IN = AllowlistProbeCatalog(
            sequence = 0,
            expiresAtMs = Long.MAX_VALUE,
            probes = listOf(
                probe("ozon", "https://www.ozon.ru/", domestic = true),
                probe("yandex", "https://ya.ru/", domestic = true),
                probe("avito", "https://www.avito.ru/", domestic = true),
                probe("vk", "https://vk.com/", domestic = true),
                probe("cloudflare", "https://speed.cloudflare.com/__down?bytes=$FOREIGN_BYTES", domestic = false),
                probe("mozilla", "https://www.mozilla.org/en-US/", domestic = false),
                probe("ovh", "https://proof.ovh.net/files/1Mb.dat", domestic = false),
                probe("github", "https://github.com/", domestic = false),
                probe("wikipedia", "https://www.wikipedia.org/", domestic = false),
            ).associateBy { it.id },
            default = AllowlistProbeSet(
                domestic = listOf("ozon", "yandex", "avito", "vk"),
                foreign = listOf("cloudflare", "mozilla", "ovh", "github", "wikipedia"),
            ),
            scopes = emptyMap(),
            candidates = emptyList(),
        )

        /** Verifies [signature] over exactly [payload], then the content. Null when anything is off. */
        fun verify(payload: ByteArray, signature: String, key: PublicKey? = null): AllowlistProbeCatalog? {
            if (payload.isEmpty() || payload.size > MAX_BYTES) return null
            if (!ServiceEndpoints.signatureValid(payload, signature, key ?: publicKey)) return null
            return parse(payload)
        }

        internal fun parse(payload: ByteArray): AllowlistProbeCatalog? = runCatching {
            val document = Json.parseToJsonElement(String(payload, Charsets.UTF_8)) as? JsonObject ?: return null
            if (number(document["schema"])?.toInt() != 1) return null
            val sequence = number(document["sequence"])?.takeIf { it >= 1 } ?: return null
            val expiresAt = number(document["expiresAt"])?.takeIf { it > 0 } ?: return null
            val probeObjects = document["probes"] as? JsonObject ?: return null
            if (probeObjects.size > MAX_PROBES) return null
            val probes = LinkedHashMap<String, AllowlistProbe>()
            for ((id, value) in probeObjects) {
                probes[id] = probeOf(id, value as? JsonObject ?: return null) ?: return null
            }
            val default = setOf(document["default"], probes) ?: return null
            val scopes = LinkedHashMap<String, AllowlistProbeSet>()
            (document["scopes"] as? JsonObject).orEmpty().entries.take(MAX_SCOPES).forEach { (name, value) ->
                // One malformed region set is skipped; that region keeps the default.
                if (SCOPE.matches(name)) setOf(value, probes)?.let { scopes[name] = it }
            }
            val candidates = (document["candidates"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                .filter { it in probes }
                .distinct()
                .take(MAX_PROBES)
            AllowlistProbeCatalog(sequence, expiresAt, probes, default, scopes, candidates)
        }.getOrNull()

        private fun number(value: Any?): Long? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

        private fun probeOf(id: String, value: JsonObject): AllowlistProbe? {
            if (!ID.matches(id)) return null
            val url = (value["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (!safeUrl(url)) return null
            val domestic = when ((value["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                "domestic" -> true
                "foreign" -> false
                else -> return null
            }
            val minBytes = (value["minBytes"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null
            if (minBytes !in 0..65_536 || (!domestic && minBytes < MIN_FOREIGN_BYTES)) return null
            return AllowlistProbe(id, url, domestic, minBytes)
        }

        private fun setOf(value: Any?, probes: Map<String, AllowlistProbe>): AllowlistProbeSet? {
            val set = value as? JsonObject ?: return null
            fun ids(name: String, domestic: Boolean): List<String>? {
                val list = (set[name] as? JsonArray ?: return null).map {
                    (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return null
                }
                if (list.distinct().size != list.size || list.any { probes[it]?.domestic != domestic }) return null
                return list
            }
            val domestic = ids("domestic", domestic = true)?.takeIf { it.size in DOMESTIC_SIZE } ?: return null
            val foreign = ids("foreign", domestic = false)?.takeIf { it.size in FOREIGN_SIZE } ?: return null
            return AllowlistProbeSet(domestic, foreign)
        }

        internal fun safeUrl(value: String): Boolean {
            if (value.length > 200) return false
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            val host = uri.host ?: return false
            val pathAndQuery = uri.rawPath.orEmpty() + (uri.rawQuery?.let { "?$it" } ?: "")
            return uri.scheme == "https" && host == host.lowercase(Locale.ROOT) && HOST.matches(host) &&
                !host.all { it.isDigit() || it == '.' } && uri.port == -1 && uri.rawUserInfo == null &&
                uri.rawFragment == null && PATH.matches(pathAndQuery)
        }
    }
}
