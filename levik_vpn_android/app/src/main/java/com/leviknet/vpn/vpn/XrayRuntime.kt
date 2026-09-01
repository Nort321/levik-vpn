package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.security.DeviceIdentity
import libXray.DialerController
import libXray.LibXray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class XrayRuntime(
    private val json: Json,
) {
    init {
        // libXray's generated Java wrapper does not load the Go JNI library itself.
        // Load it before the first static/native method call.
        if (System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true) {
            System.loadLibrary("gojni")
        }
    }

    private val lifecycleLock = ReentrantLock()
    private val ownership = CoreOwnershipRegistry()
    private val activeController = AtomicReference<DialerController?>(null)
    private val processController = object : DialerController {
        override fun protectFd(fd: Long): Boolean =
            activeController.get()?.protectFd(fd) ?: false
    }
    private var activeOwner: Long? = null
    private var activeLease: Long? = null
    private var leaseCounter = 0L

    fun claimOwner(owner: Long) {
        ownership.claim(owner)
    }

    fun retireOwner(owner: Long) {
        ownership.retire(owner)
    }

    fun convertProfile(profile: TunnelProfile): PreparedTunnelProfile =
        lifecycleLock.withLock {
            convertProfileLocked(profile)
        }

    private fun convertProfileLocked(profile: TunnelProfile): PreparedTunnelProfile {
        val servers = profile.source?.let { source ->
            val normalizer = LegacyXhttpNormalizer(json)
            val preserveSendThrough = normalizer.isFullXrayConfig(source.content)
            val normalizedSource = normalizer.normalize(source.content)
            val request = buildJsonObject {
                put("apiVersion", 1)
                put("method", "convertShareLinksToXrayJson")
                put("payload", buildJsonObject {
                    put("text", normalizedSource)
                })
            }
            val convertedData = invokeForData(request)
            val converted = when (convertedData) {
                is JsonObject -> convertedData
                is JsonPrimitive -> json.parseToJsonElement(convertedData.content).jsonObject
                else -> throw XrayException("Converted profile has an invalid shape")
            }
            val rawOutbounds = converted["outbounds"]?.jsonArray
                ?: throw XrayException("Converted profile has no outbounds")
            prepareServers(rawOutbounds, preserveSendThrough)
        }.orEmpty()

        return PreparedTunnelProfile(
            version = profile.version,
            profileId = profile.profileId,
            subscriptionId = profile.subscriptionId,
            issuedAt = profile.issuedAt,
            subscriptionExpiresAt = profile.subscriptionExpiresAt,
            servers = servers,
            directCidrs = profile.routing?.directCidrs.orEmpty(),
            directDomains = profile.routing?.directDomains.orEmpty(),
            proxyDomains = profile.routing?.proxyDomains.orEmpty(),
        )
    }

    internal fun prepareServers(
        rawOutbounds: kotlinx.serialization.json.JsonArray,
        preserveSendThrough: Boolean = false,
    ): List<TunnelServer> {
        val usedTags = mutableSetOf<String>()
        val outboundOccurrences = mutableMapOf<String, Int>()
        val servers = rawOutbounds.mapIndexedNotNull { index, element ->
            val outbound = element.jsonObject
            val protocol = outbound["protocol"]?.jsonPrimitive?.contentOrNull?.lowercase()
                ?: return@mapIndexedNotNull null
            if (protocol !in SELECTABLE_PROTOCOLS) return@mapIndexedNotNull null

            val rawTag = outbound["tag"]?.jsonPrimitive?.contentOrNull
                ?.replace(CONTROL_CHARACTERS, "")
                ?.trim()
                ?.take(MAX_SERVER_NAME_LENGTH)
            val rawSendThrough = outbound["sendThrough"]?.jsonPrimitive?.contentOrNull
            val converterDisplayName = rawSendThrough
                ?.takeUnless { preserveSendThrough }
                ?.replace(CONTROL_CHARACTERS, "")
                ?.trim()
                ?.take(MAX_SERVER_NAME_LENGTH)
            val cleanedOutbound = repairRealityServerNames(JsonObject(outbound.toMutableMap().apply {
                if (rawSendThrough != null && !preserveSendThrough) {
                    remove("sendThrough")
                }
            }))
            val contentHash = DeviceIdentity.sha256Hex(
                json.encodeToString(JsonObject.serializer(), cleanedOutbound).encodeToByteArray(),
            )
            val occurrence = outboundOccurrences.getOrDefault(contentHash, 0)
            outboundOccurrences[contentHash] = occurrence + 1
            val id = DeviceIdentity.sha256Hex("$contentHash:$occurrence".encodeToByteArray())
            val preservedTag = rawTag
                ?.takeIf { it.matches(SAFE_TAG) }
                ?.takeIf { it.lowercase() !in GENERIC_TAGS }
                ?.takeIf { it !in APP_RESERVED_TAGS }
                ?.takeIf(usedTags::add)
            val tag = preservedTag ?: "levik-${index}-${id.take(10)}".also(usedTags::add)
            val taggedOutbound = JsonObject(
                cleanedOutbound.toMutableMap().apply { put("tag", JsonPrimitive(tag)) },
            )
            val displayName = extractDisplayName(
                stripLeadingFlagEmojis(converterDisplayName ?: rawTag),
                index,
            )
            TunnelServer(
                id = id,
                tag = tag,
                name = displayName,
                countryCode = extractCountryCode(converterDisplayName ?: rawTag, tag),
                outbound = taggedOutbound,
                engine = TunnelEngineKind.XRAY,
                category = legacyServerCategory(displayName, tag),
                networkRequirement = TunnelNetworkRequirement.ANY,
            )
        }

        require(servers.isNotEmpty()) { "Converted profile has no supported proxy outbounds" }
        require(servers.size <= MAX_SERVERS) { "Converted profile has too many servers" }
        return servers
    }

    fun start(
        owner: Long,
        configJson: String,
        controller: DialerController,
        dnsServer: String,
    ): Long = lifecycleLock.withLock {
        check(ownership.isCurrent(owner)) { "VPN core owner is stale" }
        if (activeLease != null) {
            stopActiveCore()
        }

        val request = buildJsonObject {
            put("apiVersion", 1)
            put("method", "runXrayFromJson")
            put("payload", buildJsonObject {
                put("configJSON", configJson)
            })
        }
        try {
            activeController.set(controller)
            LibXray.registerDialerController(processController)
            LibXray.registerListenerController(processController)
            LibXray.setDNS(processController, dnsServer)
            invokeForData(request)
            if (!ownership.isCurrent(owner)) {
                stopUnownedCore()
                error("VPN core owner was retired during startup")
            }
            (++leaseCounter).also { lease ->
                activeOwner = owner
                activeLease = lease
            }
        } catch (error: Throwable) {
            activeController.compareAndSet(controller, null)
            runCatching { stopUnownedCore() }
            LibXray.resetDNS()
            throw error
        }
    }

    fun stop(owner: Long, lease: Long? = null) = lifecycleLock.withLock {
        if (!ownership.canStop(owner, lease, activeOwner, activeLease)) return@withLock
        stopActiveCore()
    }

    private fun stopActiveCore() {
        try {
            stopUnownedCore()
        } finally {
            activeOwner = null
            activeLease = null
        }
    }

    private fun stopUnownedCore() {
        val request = buildJsonObject {
            put("apiVersion", 1)
            put("method", "stopXray")
            put("payload", buildJsonObject {})
        }
        try {
            invokeForData(request)
        } finally {
            activeController.set(null)
            LibXray.resetDNS()
        }
    }

    private fun invokeForData(request: JsonObject): JsonElement {
        val responseText = try {
            LibXray.invoke(json.encodeToString(JsonObject.serializer(), request))
        } catch (error: UnsatisfiedLinkError) {
            throw XrayException("Verified libXray native core is unavailable", error)
        }
        val response = try {
            json.parseToJsonElement(responseText).jsonObject
        } catch (error: Exception) {
            throw XrayException("Invalid libXray response", error)
        }
        if (response["success"]?.jsonPrimitive?.booleanOrNull != true) {
            val message = response["error"]?.jsonPrimitive?.contentOrNull
                ?: "libXray rejected the request"
            throw XrayException(message)
        }
        return response["data"] ?: JsonObject(emptyMap())
    }

    private fun extractDisplayName(rawTag: String?, index: Int): String {
        val tagName = rawTag
            ?.trim()
            ?.takeIf {
                it.length in 2..MAX_SERVER_NAME_LENGTH &&
                    it.lowercase() !in GENERIC_TAGS
            }
        return tagName ?: "Server ${index + 1}"
    }

    private fun extractCountryCode(name: String?, tag: String): String {
        countryCodeFromFlag(name.orEmpty())?.let { return it }
        val text = "${name.orEmpty()} $tag".uppercase()
        COUNTRY_TOKEN.find(text)?.groupValues?.get(1)?.let { return it }
        COUNTRY_NAMES.entries.firstOrNull { (countryName, _) ->
            text.contains(countryName)
        }?.value?.let { return it }
        return "XX"
    }

    internal fun repairRealityServerNames(outbound: JsonObject): JsonObject =
        RealityRepair.repair(outbound)

    private fun stripLeadingFlagEmojis(value: String?): String? {
        var rawValue = value ?: return null
        while (true) {
            var index = 0
            var strippedAny = false
            while (index < rawValue.length) {
                val codePoint = rawValue.codePointAt(index)
                if (codePoint in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END) {
                    index += Character.charCount(codePoint)
                    strippedAny = true
                } else {
                    break
                }
            }
            if (!strippedAny) break
            rawValue = rawValue.substring(index).trimStart()
        }
        return rawValue
    }

    private fun countryCodeFromFlag(value: String): String? {
        val codePoints = value.codePoints().toArray()
        for (index in 0 until codePoints.lastIndex) {
            val first = codePoints[index]
            val second = codePoints[index + 1]
            if (first in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END &&
                second in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END
            ) {
                return buildString(2) {
                    append('A' + (first - REGIONAL_INDICATOR_START))
                    append('A' + (second - REGIONAL_INDICATOR_START))
                }
            }
        }
        return null
    }

    companion object {
        private const val MAX_SERVERS = 200
        private const val MAX_SERVER_NAME_LENGTH = 80
        private val SAFE_TAG = Regex("[A-Za-z0-9._:-]{1,128}")
        private val CONTROL_CHARACTERS = Regex("\\p{C}")
        private val COUNTRY_TOKEN = Regex("(?:^|[^A-Z])([A-Z]{2})(?:[^A-Z]|$)")
        private val SELECTABLE_PROTOCOLS = setOf(
            "vless",
            "vmess",
            "trojan",
            "shadowsocks",
            "hysteria",
            "hysteria2",
        )
        private val GENERIC_TAGS = setOf(
            "proxy",
            "direct",
            "block",
            "dns",
            "outbound",
        )
        private val APP_RESERVED_TAGS = setOf(
            "levik-tun-in",
            "levik-direct",
            "levik-block",
        )
        private const val REGIONAL_INDICATOR_START = 0x1F1E6
        private const val REGIONAL_INDICATOR_END = 0x1F1FF
        private val COUNTRY_NAMES = mapOf(
            "GERMANY" to "DE",
            "NETHERLANDS" to "NL",
            "FINLAND" to "FI",
            "SWEDEN" to "SE",
            "FRANCE" to "FR",
            "UNITED KINGDOM" to "GB",
            "UNITED STATES" to "US",
            "RUSSIA" to "RU",
            "KAZAKHSTAN" to "KZ",
            "POLAND" to "PL",
            "TURKEY" to "TR",
            "ГЕРМАНИЯ" to "DE",
            "НИДЕРЛАНДЫ" to "NL",
            "ФИНЛЯНДИЯ" to "FI",
            "ШВЕЦИЯ" to "SE",
            "ФРАНЦИЯ" to "FR",
            "ВЕЛИКОБРИТАНИЯ" to "GB",
            "США" to "US",
            "РОССИЯ" to "RU",
            "КАЗАХСТАН" to "KZ",
            "ПОЛЬША" to "PL",
            "ТУРЦИЯ" to "TR",
        )
    }
}

class XrayException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class CoreOwnershipRegistry {
    private val newestOwner = AtomicLong(0)
    private val retiredOwnerFloor = AtomicLong(0)

    fun claim(owner: Long) {
        require(owner > 0)
        newestOwner.updateAndGet { current -> maxOf(current, owner) }
    }

    fun retire(owner: Long) {
        require(owner > 0)
        retiredOwnerFloor.updateAndGet { current -> maxOf(current, owner) }
    }

    fun isCurrent(owner: Long): Boolean =
        owner == newestOwner.get() && owner > retiredOwnerFloor.get()

    fun canStop(
        requestingOwner: Long,
        requestedLease: Long?,
        activeOwner: Long?,
        activeLease: Long?,
    ): Boolean =
        activeOwner == requestingOwner &&
            activeLease != null &&
            (requestedLease == null || requestedLease == activeLease)
}
