package com.leviknet.vpn.core.network

import com.leviknet.vpn.core.security.DeviceIdentity
import java.security.SecureRandom
import java.time.Clock

class RequestSigner(
    private val identity: DeviceIdentity,
    private val clock: Clock = Clock.systemUTC(),
    private val nonceFactory: () -> ByteArray = {
        ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
    },
) {
    fun sign(
        method: String,
        path: String,
        accessToken: String?,
        body: ByteArray,
    ): SignedRequest {
        require(method.matches(HTTP_METHOD)) { "Invalid HTTP method" }
        require(path.startsWith("/") && !path.contains('?') && !path.contains('#')) {
            "Only an encoded URL path can be signed"
        }
        require(body.size <= MAX_SIGNED_BODY_BYTES) { "Request body is too large" }

        val timestamp = clock.instant().epochSecond
        val nonce = DeviceIdentity.base64Url(nonceFactory())
        val deviceId = identity.deviceId()
        val tokenHash = DeviceIdentity.sha256Hex(accessToken.orEmpty().encodeToByteArray())
        val bodyHash = DeviceIdentity.sha256Hex(body)
        val canonical = canonicalPayload(
            method = method,
            path = path,
            timestamp = timestamp,
            nonce = nonce,
            deviceId = deviceId,
            tokenHash = tokenHash,
            bodyHash = bodyHash,
        )
        val canonicalBytes = canonical.encodeToByteArray()

        return SignedRequest(
            deviceId = deviceId,
            timestamp = timestamp,
            nonce = nonce,
            signature = identity.sign(canonicalBytes),
            requestHash = DeviceIdentity.base64Url(
                java.security.MessageDigest.getInstance("SHA-256").digest(canonicalBytes),
            ),
        )
    }

    companion object {
        private const val NONCE_BYTES = 16
        private const val MAX_SIGNED_BODY_BYTES = 1024 * 1024
        private val HTTP_METHOD = Regex("[A-Z]{3,10}")

        fun canonicalPayload(
            method: String,
            path: String,
            timestamp: Long,
            nonce: String,
            deviceId: String,
            tokenHash: String,
            bodyHash: String,
        ): String = listOf(
            "v1",
            method,
            path,
            timestamp.toString(),
            nonce,
            deviceId,
            tokenHash,
            bodyHash,
        ).joinToString(separator = "\n")
    }
}

data class SignedRequest(
    val deviceId: String,
    val timestamp: Long,
    val nonce: String,
    val signature: String,
    val requestHash: String,
)
