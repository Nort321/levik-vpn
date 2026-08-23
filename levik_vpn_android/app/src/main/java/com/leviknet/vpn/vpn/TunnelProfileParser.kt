package com.leviknet.vpn.vpn

import java.time.Clock
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class TunnelProfileParser(
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun parse(plaintext: ByteArray, expectedSubscriptionId: String): TunnelProfile {
        require(plaintext.size in 2..MAX_PROFILE_BYTES) { "Invalid tunnel profile size" }
        val profile = try {
            json.decodeFromString<TunnelProfile>(plaintext.decodeToString())
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Invalid tunnel profile JSON", error)
        }

        require(profile.version == SUPPORTED_VERSION) { "Unsupported tunnel profile version" }
        require(profile.profileId.matches(SAFE_ID)) { "Invalid tunnel profile identifier" }
        require(profile.subscriptionId == expectedSubscriptionId) {
            "Tunnel profile subscription does not match the request"
        }
        require(profile.source.mediaType.length in 1..MAX_MEDIA_TYPE_LENGTH) {
            "Invalid tunnel profile media type"
        }
        require(profile.source.content.length in 1..MAX_SOURCE_CHARS) {
            "Invalid tunnel profile source"
        }

        val issuedAt = parseInstant(profile.issuedAt, "issuedAt")
        val now = clock.instant()
        require(!issuedAt.isAfter(now.plusSeconds(MAX_CLOCK_SKEW_SECONDS))) {
            "Tunnel profile was issued in the future"
        }
        profile.subscriptionExpiresAt?.let { value ->
            val subscriptionExpiresAt = parseInstant(value, "subscriptionExpiresAt")
            require(subscriptionExpiresAt.isAfter(issuedAt)) {
                "Invalid subscription expiry"
            }
        }

        val directCidrs = profile.routing?.directCidrs.orEmpty()
        require(directCidrs.size <= MAX_DIRECT_CIDRS) { "Too many direct routes" }
        directCidrs.forEach { cidr ->
            require(cidr.length in 3..MAX_CIDR_LENGTH && cidr.matches(SAFE_CIDR)) {
                "Invalid direct route"
            }
        }

        return profile
    }

    private fun parseInstant(value: String, field: String): Instant =
        try {
            Instant.parse(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid tunnel profile $field", error)
        }

    companion object {
        private const val SUPPORTED_VERSION = 1
        private const val MAX_PROFILE_BYTES = 4 * 1024 * 1024
        private const val MAX_SOURCE_CHARS = 3 * 1024 * 1024
        private const val MAX_MEDIA_TYPE_LENGTH = 128
        private const val MAX_CLOCK_SKEW_SECONDS = 5 * 60L
        private const val MAX_DIRECT_CIDRS = 64
        private const val MAX_CIDR_LENGTH = 64
        private val SAFE_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        private val SAFE_CIDR = Regex("[0-9A-Fa-f:.]+/[0-9]{1,3}")
    }
}
