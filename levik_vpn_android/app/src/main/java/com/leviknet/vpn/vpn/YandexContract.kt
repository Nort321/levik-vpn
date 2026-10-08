package com.leviknet.vpn.vpn

import java.net.URI
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** Provider claims are untrusted here; OnlyOffice verifies the JWT on admission. */
object YandexContract {
    private val documentPath = Regex("/i/[A-Za-z0-9_-]{8,200}")
    private val balancerHost = Regex("(?:[a-z0-9_-]{1,63}\\.)?onlyoffice\\.disk\\.yandex\\.net")
    private val compactJwt = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}")
    private val strictJson = Json { ignoreUnknownKeys = false; isLenient = false }

    fun validateDocumentUrl(value: String): String {
        require(value.length in 30..256) { "Invalid Yandex document link" }
        val uri = URI(value)
        require(uri.scheme == "https" && uri.rawAuthority == "disk.yandex.ru" &&
            uri.rawPath.matches(documentPath) && uri.rawQuery == null && uri.rawFragment == null
        ) { "Use a public disk.yandex.ru/i/ document link" }
        return value
    }

    fun validateProviderAuth(auth: YandexProviderAuth, now: Instant = Instant.now()) {
        require(auth.balancerUrl.length in 32..256) { "Invalid Yandex editor endpoint" }
        val uri = URI(auth.balancerUrl)
        require(uri.scheme == "https" && uri.rawAuthority?.matches(balancerHost) == true &&
            uri.rawPath in setOf("", "/") &&
            uri.rawQuery == null && uri.rawFragment == null
        ) { "Invalid Yandex editor endpoint" }
        require(auth.token.length in 64..8192 && auth.token.matches(compactJwt)) {
            "Invalid guest authorization"
        }
        require(auth.validUntil > now.epochSecond && auth.validUntil <= now.epochSecond + 900) {
            "Guest authorization is expired or too long"
        }
        val parts = auth.token.split('.')
        val header = decodeClaims(parts[0])
        require((header["alg"] as? JsonPrimitive)?.content == "HS256") {
            "Invalid guest authorization algorithm"
        }
        val payload = decodeClaims(parts[1])
        val document = payload["document"] as? JsonObject
            ?: throw IllegalArgumentException("Missing guest document")
        val permissions = document["permissions"] as? JsonObject
        require((permissions?.get("edit") as? JsonPrimitive)?.booleanOrNull == true) {
            "The document must allow editing by link"
        }
        val documentKey = (document["key"] as? JsonPrimitive)?.content.orEmpty()
        require(documentKey.length in 1..256 && documentKey.matches(Regex("[A-Za-z0-9_.-]+={0,2}"))
        ) { "Invalid guest document identity" }
        val expiry = (payload["exp"] as? JsonPrimitive)?.content?.toLongOrNull()
        if (payload.containsKey("exp")) {
            require(expiry != null && expiry >= auth.validUntil) { "Guest authorization is expired" }
        }
    }

    internal fun guestCaptureDeadline(token: String, now: Instant): Long {
        require(token.length in 64..8192 && token.matches(compactJwt))
        val payload = decodeClaims(token.split('.')[1])
        val expiry = (payload["exp"] as? JsonPrimitive)?.content?.toLongOrNull()
        require(!payload.containsKey("exp") || expiry != null)
        return minOf(now.epochSecond + 600, expiry ?: Long.MAX_VALUE)
    }

    private fun decodeClaims(value: String): JsonObject {
        val decoded = Base64.getUrlDecoder().decode(value)
        return try {
            strictJson.parseToJsonElement(decoded.decodeToString(throwOnInvalidSequence = true)).jsonObject
        } finally {
            decoded.fill(0)
        }
    }

    fun validateBootstrap(
        bootstrap: YandexBootstrap,
        expectedDeviceId: String,
        issuedAt: Instant,
        subscriptionExpiresAt: Instant?,
        now: Instant = Instant.now(),
    ) {
        require(bootstrap.version == 1) { "Unsupported Yandex bootstrap" }
        require(bootstrap.deviceId.matches(Regex("[0-9a-f]{64}")) &&
            bootstrap.deviceId == expectedDeviceId
        ) { "Yandex profile belongs to another installation" }
        validateDocumentUrl(bootstrap.documentUrl)
        require(bootstrap.leaseRef.matches(Regex("[A-Za-z0-9_-]{43}")) &&
            Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(bootstrap.leaseRef)) == bootstrap.leaseRef &&
            bootstrap.sharedKey.matches(Regex("[0-9a-f]{64}"))
        ) { "Invalid Yandex session binding" }
        require(bootstrap.expiresAt > now.epochSecond && bootstrap.expiresAt > issuedAt.epochSecond &&
            bootstrap.expiresAt <= issuedAt.epochSecond + 3600 &&
            (subscriptionExpiresAt == null || bootstrap.expiresAt <= subscriptionExpiresAt.epochSecond)
        ) { "Invalid Yandex session expiry" }
        validateProviderAuth(bootstrap.providerAuth, now)
        require(bootstrap.providerAuth.validUntil <= bootstrap.expiresAt) {
            "Guest authorization exceeds the VPN session"
        }
    }
}

internal enum class YandexProfileRefreshMode { REJECT, HOT_REFRESH, RECONNECT }

internal const val YANDEX_AUTH_REFRESH_LEAD_SECONDS = 180L

/** A short-lived cached credential needs an immediate attempt, not a skipped refresh. */
internal fun yandexAuthRefreshDelayMillis(remainingMillis: Long, attempted: Boolean): Long? = when {
    remainingMillis <= 0 -> null
    remainingMillis > YANDEX_AUTH_REFRESH_LEAD_SECONDS * 1000 ->
        remainingMillis - YANDEX_AUTH_REFRESH_LEAD_SECONDS * 1000
    attempted -> minOf(30_000L, remainingMillis)
    else -> 0L
}

/** Provider sessions may change; authenticated VPN ownership/routing may not. */
internal fun yandexProfileRefreshMode(current: YandexServerConfig, next: YandexServerConfig): YandexProfileRefreshMode {
    val first = current.bootstrap
    val second = next.bootstrap
    if (first.version != second.version || first.deviceId != second.deviceId ||
        first.documentUrl != second.documentUrl || first.leaseRef != second.leaseRef || current.routing != next.routing
    ) return YandexProfileRefreshMode.REJECT
    return if (first.sharedKey == second.sharedKey) YandexProfileRefreshMode.HOT_REFRESH else YandexProfileRefreshMode.RECONNECT
}
