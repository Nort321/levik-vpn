package com.leviknet.vpn.vpn

import java.time.Clock
import java.time.Instant
import java.net.InetAddress
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class TunnelProfileParser(
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
    private val supportedEngines: Set<TunnelEngineKind> = TunnelEngineKind.entries.toSet(),
) {
    private val strictRelayJson = Json(json) {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    fun parse(
        plaintext: ByteArray,
        expectedSubscriptionId: String,
        expectedDeviceId: String? = null,
    ): TunnelProfile {
        require(plaintext.size in 2..MAX_PROFILE_BYTES) { "Invalid tunnel profile size" }
        val encodedProfile = plaintext.decodeToString()
        val profile = try {
            val compatible = json.decodeFromString<TunnelProfile>(encodedProfile)
            if (compatible.version == RELAY_VERSION) {
                strictRelayJson.decodeFromString<TunnelProfile>(encodedProfile).also {
                    validateRelayEnvelopeShape(
                        strictRelayJson.parseToJsonElement(encodedProfile).jsonObject,
                    )
                }
            } else {
                compatible
            }
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Invalid tunnel profile JSON", error)
        }

        require(profile.version in SUPPORTED_VERSIONS) { "Unsupported tunnel profile version" }
        require(profile.engine in supportedEngines) {
            "Tunnel profile requires an unsupported engine"
        }
        require(profile.profileId.matches(SAFE_ID)) { "Invalid tunnel profile identifier" }
        require(profile.subscriptionId == expectedSubscriptionId) {
            "Tunnel profile subscription does not match the request"
        }

        val issuedAt = parseInstant(profile.issuedAt, "issuedAt")
        val now = clock.instant()
        require(!issuedAt.isAfter(now.plusSeconds(MAX_CLOCK_SKEW_SECONDS))) {
            "Tunnel profile was issued in the future"
        }
        val subscriptionExpiresAt = profile.subscriptionExpiresAt?.let { value ->
            parseInstant(value, "subscriptionExpiresAt").also { expiresAt ->
                require(expiresAt.isAfter(issuedAt)) { "Invalid subscription expiry" }
            }
        }

        validatePayload(
            profile = profile,
            expectedDeviceId = expectedDeviceId,
            issuedAt = issuedAt,
            subscriptionExpiresAt = subscriptionExpiresAt,
            now = now,
        )
        validateRouting(profile.routing)
        return profile
    }

    private fun validatePayload(
        profile: TunnelProfile,
        expectedDeviceId: String?,
        issuedAt: Instant,
        subscriptionExpiresAt: Instant?,
        now: Instant,
    ) {
        when (profile.version) {
            LEGACY_VERSION -> {
                require(profile.engine == TunnelEngineKind.XRAY) {
                    "Version 1 tunnel profiles must use Xray"
                }
                require(profile.source != null && profile.bootstrap == null) {
                    "Version 1 tunnel profiles require one legacy source"
                }
                validateSource(profile.source)
            }
            RELAY_VERSION -> {
                require(profile.engine == TunnelEngineKind.LEVIK_RELAY) {
                    "Version 2 tunnel profiles must use Levik Relay"
                }
                require(profile.source == null) {
                    "Version 2 relay profiles must not contain an Xray source"
                }
                validateRelayBootstrap(
                    bootstrap = requireNotNull(profile.bootstrap) {
                        "Version 2 relay profile has no bootstrap"
                    },
                    expectedDeviceId = requireNotNull(expectedDeviceId) {
                        "Expected device identity is required for a relay profile"
                    },
                    issuedAt = issuedAt,
                    subscriptionExpiresAt = subscriptionExpiresAt,
                    now = now,
                )
            }
        }
    }

    private fun validateRelayEnvelopeShape(envelope: JsonObject) {
        require(REQUIRED_RELAY_ENVELOPE_FIELDS.all(envelope::containsKey)) {
            "Version 2 relay profile is incomplete"
        }
        require("source" !in envelope) {
            "Version 2 relay profiles must omit the legacy source"
        }
        require(envelope["routing"] is JsonObject) {
            "Version 2 relay profiles require routing"
        }
    }

    private fun validateSource(source: TunnelProfileSource) {
        require(source.mediaType.length in 1..MAX_MEDIA_TYPE_LENGTH) {
            "Invalid tunnel profile media type"
        }
        require(source.content.length in 1..MAX_SOURCE_CHARS) {
            "Invalid tunnel profile source"
        }
    }

    private fun validateRelayBootstrap(
        bootstrap: RelayBootstrap,
        expectedDeviceId: String,
        issuedAt: Instant,
        subscriptionExpiresAt: Instant?,
        now: Instant,
    ) {
        require(bootstrap.version == RELAY_BOOTSTRAP_VERSION) {
            "Unsupported relay bootstrap version"
        }
        require(bootstrap.protocol == RELAY_PROTOCOL) { "Unsupported relay protocol" }
        require(bootstrap.policy.version == RELAY_POLICY_VERSION) {
            "Unsupported relay policy version"
        }
        require(bootstrap.policy.deviceSlots == RelayDeviceSlotsPolicy.REMNAWAVE_HWID_SHARED &&
            bootstrap.policy.trafficAccounting == RelayTrafficAccountingPolicy.RELAY_SEPARATE
        ) {
            "Unsupported relay entitlement policy"
        }
        require(bootstrap.entitlementId.matches(UUID)) { "Invalid relay entitlement identifier" }
        require(bootstrap.deviceId.matches(DEVICE_ID) && bootstrap.deviceId == expectedDeviceId) {
            "Relay profile device does not match this installation"
        }
        require(bootstrap.credentialId.matches(SAFE_ID)) {
            "Invalid relay credential identifier"
        }
        require(bootstrap.accessToken.matches(RELAY_ACCESS_TOKEN)) {
            "Invalid relay access token"
        }
        val credentialExpiresAt = parseInstant(bootstrap.expiresAt, "bootstrap.expiresAt")
        require(credentialExpiresAt.isAfter(issuedAt) && credentialExpiresAt.isAfter(now)) {
            "Relay credential is expired"
        }
        require(!credentialExpiresAt.isAfter(issuedAt.plusSeconds(MAX_RELAY_LIFETIME_SECONDS))) {
            "Relay credential lifetime is too long"
        }
        subscriptionExpiresAt?.let { subscriptionExpiry ->
            require(!credentialExpiresAt.isAfter(subscriptionExpiry)) {
                "Relay credential exceeds subscription expiry"
            }
        }

        require(bootstrap.nodes.size in 1..MAX_RELAY_NODES) {
            "Invalid relay node count"
        }
        val ids = mutableSetOf<String>()
        bootstrap.nodes.forEach { node ->
            require(node.id.matches(SAFE_ID) && ids.add(node.id)) {
                "Invalid or duplicate relay node identifier"
            }
            require(node.displayName.length in 1..MAX_SERVER_NAME_LENGTH &&
                node.displayName.none(Char::isISOControl)
            ) {
                "Invalid relay node display name"
            }
            require(node.countryCode.matches(COUNTRY_CODE)) {
                "Invalid relay node country code"
            }
            require(isValidHost(node.host)) { "Invalid relay node host" }
            require(node.port in 1..65_535) { "Invalid relay node port" }
            require(isValidDnsName(node.turnFrontSni)) { "Invalid relay TURN front SNI" }
            require(node.transport == RelayTransport.TURN_DTLS) {
                "Unsupported relay transport"
            }
            require(node.serverPublicKey.matches(RELAY_PUBLIC_KEY)) {
                "Invalid relay server public key"
            }
            require(node.turnHashes.size in 1..MAX_TURN_HASHES &&
                node.turnHashes.distinct().size == node.turnHashes.size &&
                node.turnHashes.all { it.matches(TURN_HASH) }
            ) {
                "Invalid relay TURN credentials"
            }
        }
    }

    private fun validateRouting(routing: TunnelRouting?) {
        val directCidrs = routing?.directCidrs.orEmpty()
        require(directCidrs.size <= MAX_DIRECT_CIDRS) { "Too many direct routes" }
        directCidrs.forEach { cidr ->
            require(cidr.length in 3..MAX_CIDR_LENGTH && cidr.matches(SAFE_CIDR)) {
                "Invalid direct route"
            }
        }
        require((routing?.policyVersion ?: 0) in 0..MAX_POLICY_VERSION) {
            "Invalid routing policy version"
        }
        listOf(
            routing?.directDomains.orEmpty(),
            routing?.proxyDomains.orEmpty(),
        ).forEach { rules ->
            require(rules.size <= MAX_DOMAIN_RULES) { "Too many domain rules" }
            rules.forEach { rule ->
                require(rule.length in 3..MAX_DOMAIN_RULE_LENGTH && rule.matches(SAFE_DOMAIN_RULE)) {
                    "Invalid domain rule"
                }
            }
        }
    }

    private fun isValidHost(value: String): Boolean {
        if (value.length !in 1..MAX_HOST_LENGTH) return false
        return isValidIpv4(value) || isValidIpv6(value) || isValidDnsName(value)
    }

    private fun isValidIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                part.toIntOrNull() in 0..255
        }
    }

    private fun isValidIpv6(value: String): Boolean {
        if (!value.contains(':') || value.length > 45 || value.contains(":::")) return false
        if (!value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) {
            return false
        }
        return runCatching { InetAddress.getByName(value).hostAddress?.contains(':') == true }
            .getOrDefault(false)
    }

    private fun isValidDnsName(value: String): Boolean {
        if (value.length !in 1..MAX_HOST_LENGTH || value.endsWith('.')) return false
        val labels = value.split('.')
        return labels.all { label ->
            label.length in 1..63 &&
                label.first().isAsciiLetterOrDigit() &&
                label.last().isAsciiLetterOrDigit() &&
                label.all { it.isAsciiLetterOrDigit() || it == '-' }
        }
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun parseInstant(value: String, field: String): Instant =
        try {
            Instant.parse(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid tunnel profile $field", error)
        }

    companion object {
        private const val LEGACY_VERSION = 1
        private const val RELAY_VERSION = 2
        private val SUPPORTED_VERSIONS = setOf(LEGACY_VERSION, RELAY_VERSION)
        private const val RELAY_BOOTSTRAP_VERSION = 1
        private const val RELAY_POLICY_VERSION = 1
        private const val RELAY_PROTOCOL = "levik-relay-v1"
        private val REQUIRED_RELAY_ENVELOPE_FIELDS = setOf(
            "version",
            "engine",
            "profileId",
            "subscriptionId",
            "issuedAt",
            "subscriptionExpiresAt",
            "bootstrap",
            "routing",
        )
        private const val MAX_PROFILE_BYTES = 4 * 1024 * 1024
        private const val MAX_SOURCE_CHARS = 3 * 1024 * 1024
        private const val MAX_MEDIA_TYPE_LENGTH = 128
        private const val MAX_CLOCK_SKEW_SECONDS = 5 * 60L
        private const val MAX_RELAY_LIFETIME_SECONDS = 7 * 24 * 60 * 60L
        private const val MAX_DIRECT_CIDRS = 64
        private const val MAX_CIDR_LENGTH = 64
        private const val MAX_POLICY_VERSION = 1_000_000
        private const val MAX_DOMAIN_RULES = 500
        private const val MAX_DOMAIN_RULE_LENGTH = 253
        private const val MAX_RELAY_NODES = 16
        private const val MAX_TURN_HASHES = 4
        private const val MAX_SERVER_NAME_LENGTH = 80
        private const val MAX_HOST_LENGTH = 253
        private val SAFE_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        private val DEVICE_ID = Regex("[0-9a-f]{64}")
        private val COUNTRY_CODE = Regex("[A-Z]{2}")
        private val RELAY_ACCESS_TOKEN =
            Regex("[ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789]{16}")
        private val RELAY_PUBLIC_KEY = Regex("[A-Za-z0-9_-]{43}")
        private val TURN_HASH = Regex("[A-Za-z0-9_-]{16,256}")
        private val SAFE_CIDR = Regex("[0-9A-Fa-f:.]+/[0-9]{1,3}")
        private val SAFE_DOMAIN_RULE = Regex("(?:domain|full):[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
    }
}
