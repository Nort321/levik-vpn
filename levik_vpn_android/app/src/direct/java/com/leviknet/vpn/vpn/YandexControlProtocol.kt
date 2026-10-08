package com.leviknet.vpn.vpn

import java.net.InetAddress
import java.net.URI
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement

internal const val YANDEX_CONTROL_VERSION = 2
internal const val YANDEX_CONTROL_MAGIC = "LEVIK_YANDEX_ANDROID"
internal const val YANDEX_MAX_CONTROL_BYTES = 32 * 1024
internal const val YANDEX_MAX_NETWORK_BYTES = 8 * 1024

internal class YandexProtocolException(val stableCode: String) : IllegalStateException(stableCode)

internal data class YandexNativeInit(
    val bootstrap: YandexBootstrap,
    val protectFdSocket: String,
    val proxyUsername: String,
    val proxyPassword: String,
)

internal sealed interface YandexNetworkRequest {
    val requestId: Long
    data class Protect(override val requestId: Long) : YandexNetworkRequest
    data class Resolve(override val requestId: Long, val host: String) : YandexNetworkRequest
}

internal sealed interface YandexControlAction {
    data object Continue : YandexControlAction
    data object ConnectNetwork : YandexControlAction
    data class PreparedProxy(val port: Int) : YandexControlAction
    data object Running : YandexControlAction
    data class NativeFailure(val code: String) : YandexControlAction
    data class Refreshed(val result: YandexRefreshResult) : YandexControlAction
}

@Serializable
internal data class YandexRefreshResult(
    val requestId: Long,
    val ok: Boolean,
    val leaseExpiresAt: Long? = null,
    val validUntil: Long? = null,
    val code: String? = null,
)

@Serializable
internal data class YandexEventWire(
    val magic: String,
    val version: Int,
    val type: String,
    val phase: String? = null,
    val code: String? = null,
    val data: JsonElement? = null,
)

internal class YandexControlCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = false
        encodeDefaults = true
    }

    fun encodeInit(init: YandexNativeInit): String {
        val bootstrap = init.bootstrap
        YandexContract.validateDocumentUrl(bootstrap.documentUrl)
        YandexContract.validateProviderAuth(bootstrap.providerAuth)
        if (!SOCKET_NAME.matches(init.protectFdSocket) || !PROXY_USER.matches(init.proxyUsername) ||
            !PROXY_PASSWORD.matches(init.proxyPassword) || !bootstrap.sharedKey.matches(Regex("[0-9a-f]{64}")) ||
            !bootstrap.leaseRef.matches(Regex("[A-Za-z0-9_-]{43}")) ||
            bootstrap.providerAuth.validUntil > bootstrap.expiresAt
        ) failure("yandex_init_invalid")
        return encode(
            YandexInitWire(
                documentUrl = bootstrap.documentUrl,
                leaseRef = bootstrap.leaseRef,
                sharedKey = bootstrap.sharedKey,
                leaseExpiresAt = bootstrap.expiresAt,
                providerAuth = bootstrap.providerAuth,
                protectFdSocket = init.protectFdSocket,
                proxyUsername = init.proxyUsername,
                proxyPassword = init.proxyPassword,
            ),
        )
    }

    fun encodeStop(): String = encode(YandexCommandWire(type = "STOP"))
    fun encodeStatus(): String = encode(YandexCommandWire(type = "STATUS"))
    fun status(event: YandexEventWire): YandexStatusWire = element(event.data)
    fun encodeRefresh(requestId: Long, bootstrap: YandexBootstrap): String {
        val now = Instant.now().epochSecond
        YandexContract.validateProviderAuth(bootstrap.providerAuth)
        if (requestId !in 1..9_007_199_254_740_992L || bootstrap.expiresAt <= now ||
            bootstrap.expiresAt > now + 3600 || bootstrap.providerAuth.validUntil > bootstrap.expiresAt
        ) failure("yandex_refresh_invalid")
        return encode(YandexRefreshWire(requestId = requestId, leaseExpiresAt = bootstrap.expiresAt, providerAuth = bootstrap.providerAuth))
    }

    fun decodeEvent(payload: String): YandexEventWire {
        val event = decode<YandexEventWire>(payload, YANDEX_MAX_CONTROL_BYTES)
        envelope(event.magic, event.version)
        when (event.type) {
            "ready" -> {
                if (event.code != null || event.phase !in READY_PHASES) failure("yandex_protocol_ready")
                if (event.phase == "RUNNING") {
                    if (element<YandexRunningWire>(event.data).protocolVersion != YANDEX_CONTROL_VERSION) failure("yandex_protocol_version")
                } else if (event.data != null && event.data !is JsonNull) failure("yandex_protocol_ready")
            }
            "proxy_plan" -> {
                if (event.phase != "PREPARED" || event.code != null) failure("yandex_protocol_proxy")
                val plan = element<YandexProxyWire>(event.data)
                if (plan.address != "127.0.0.1" || plan.port !in 1..65_535) failure("yandex_protocol_proxy")
            }
            "stats", "status" -> {
                if (event.phase != null || event.code != null) failure("yandex_protocol_status")
                val status = element<YandexStatusWire>(event.data)
                if (status.state !in setOf("connecting", "running") || status.bytesUp < 0 || status.bytesDown < 0 ||
                    status.protectedExternalSockets < 0 || status.resolvedProviderHosts < 0 ||
                    status.carrierPacketsUp < 0 || status.carrierPacketsDown < 0 ||
                    status.proxy.let { it.connections < 0 || it.rejected < 0 || it.udpAssociations < 0 } ||
                    status.dataPath.let { it.dataSent < 0 || it.dataReceived < 0 || it.dataDropped < 0 ||
                        it.decodeErrors < 0 || it.cryptoRejected < 0 || it.batchSendErrors < 0 }
                ) failure("yandex_protocol_status")
            }
            "error" -> {
                if (event.phase != null || event.data != null || event.code !in ERROR_CODES) failure("yandex_protocol_error")
            }
            "refreshed" -> {
                if (event.phase != null || event.code != null) failure("yandex_protocol_refresh")
                val result = refreshResult(event)
                if (result.requestId !in 1..9_007_199_254_740_992L || if (result.ok) {
                    result.code != null || result.leaseExpiresAt == null || result.validUntil == null ||
                        result.validUntil <= 0 || result.leaseExpiresAt < result.validUntil
                } else result.code !in REFRESH_CODES || result.leaseExpiresAt != null || result.validUntil != null
                ) failure("yandex_protocol_refresh")
            }
            else -> failure("yandex_protocol_event")
        }
        return event
    }

    fun proxyPort(event: YandexEventWire): Int = element<YandexProxyWire>(event.data).port
    fun refreshResult(event: YandexEventWire): YandexRefreshResult = element(event.data)

    fun decodeNetworkRequest(payload: String): YandexNetworkRequest {
        val request = decode<YandexNetworkWire>(payload, YANDEX_MAX_NETWORK_BYTES)
        envelope(request.magic, request.version)
        if (request.requestId !in 1..9_007_199_254_740_992L) failure("yandex_protocol_request_id")
        return when (request.type) {
            "PROTECT_SOCKET" -> {
                if (request.host != null || request.network !in setOf("tcp4", "tcp6") ||
                    request.address == null || request.address.length > 128
                ) failure("yandex_protocol_protect")
                val uri = runCatching { URI("https://${request.address}/") }.getOrNull()
                    ?: failure("yandex_protocol_protect")
                val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: failure("yandex_protocol_protect")
                if (uri.port != 443 || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                    uri.rawPath != "/" || uri.rawAuthority != request.address ||
                    !literalAddress(host) || (request.network == "tcp4") != !host.contains(':')
                ) failure("yandex_protocol_protect")
                YandexNetworkRequest.Protect(request.requestId)
            }
            "RESOLVE_HOST" -> {
                if (request.network != null || request.address != null || request.host == null ||
                    !PROVIDER_HOST.matches(request.host)
                ) failure("yandex_protocol_resolve")
                YandexNetworkRequest.Resolve(request.requestId, request.host)
            }
            else -> failure("yandex_protocol_network_type")
        }
    }

    fun encodeProtectAck(requestId: Long, success: Boolean): String = encode(
        YandexProtectAckWire(requestId = requestId, ok = success, code = if (success) null else "protect_bind_failed"),
    )

    fun encodeResolveAck(requestId: Long, addresses: List<String>?): String {
        val valid = addresses != null && addresses.size in 1..64 && addresses.all(::literalAddress)
        return encode(
            YandexResolveAckWire(
                requestId = requestId,
                ok = valid,
                addresses = if (valid) addresses.orEmpty() else emptyList(),
                code = if (valid) null else "resolve_failed",
            ),
        )
    }

    private fun envelope(magic: String, version: Int) {
        if (magic != YANDEX_CONTROL_MAGIC || version != YANDEX_CONTROL_VERSION) failure("yandex_protocol_version")
    }

    private inline fun <reified T> encode(value: T): String = json.encodeToString(value).also {
        if (it.encodeToByteArray().size + 1 > YANDEX_MAX_CONTROL_BYTES) failure("yandex_protocol_size")
    }

    private inline fun <reified T> decode(payload: String, maximum: Int): T {
        if (payload.encodeToByteArray().size + 1 > maximum || !uniqueKeys(payload)) failure("yandex_protocol_json")
        return try { json.decodeFromString(payload) } catch (_: Exception) { failure("yandex_protocol_json") }
    }

    private inline fun <reified T> element(element: JsonElement?): T = try {
        json.decodeFromJsonElement(element ?: failure("yandex_protocol_data"))
    } catch (_: Exception) { failure("yandex_protocol_data") }

    private fun uniqueKeys(text: String): Boolean = runCatching {
        var position = 0
        var items = 0
        fun whitespace() { while (position < text.length && text[position] in " \t\r\n") position++ }
        fun stringValue(): String {
            whitespace()
            require(position < text.length && text[position] == '"')
            val start = position++
            var escaped = false
            while (position < text.length) {
                val char = text[position++]
                if (!escaped && char == '"') return json.decodeFromString<String>(text.substring(start, position))
                escaped = !escaped && char == '\\'
            }
            error("unterminated string")
        }
        fun value(depth: Int) {
            whitespace()
            require(depth <= 16 && ++items <= 4096 && position < text.length)
            when (text[position]) {
                '{' -> {
                    position++
                    whitespace()
                    val keys = mutableSetOf<String>()
                    if (position < text.length && text[position] != '}') while (true) {
                        require(keys.add(stringValue()))
                        whitespace()
                        require(position < text.length && text[position++] == ':')
                        value(depth + 1)
                        whitespace()
                        if (position < text.length && text[position] == ',') { position++; continue }
                        break
                    }
                    require(position < text.length && text[position++] == '}')
                }
                '[' -> {
                    position++
                    whitespace()
                    if (position < text.length && text[position] != ']') while (true) {
                        value(depth + 1)
                        whitespace()
                        if (position < text.length && text[position] == ',') { position++; continue }
                        break
                    }
                    require(position < text.length && text[position++] == ']')
                }
                '"' -> stringValue()
                else -> {
                    val start = position
                    while (position < text.length && text[position] !in ",]} \t\r\n") position++
                    require(position > start)
                }
            }
        }
        value(0)
        whitespace()
        require(position == text.length)
    }.isSuccess

    private companion object {
        val SOCKET_NAME = Regex("@levik_ydx_[A-Za-z0-9_-]{16,90}")
        val PROXY_USER = Regex("[A-Za-z0-9_-]{16,64}")
        val PROXY_PASSWORD = Regex("[A-Za-z0-9_-]{32,128}")
        val PROVIDER_HOST = Regex("(?:[a-z0-9_-]{1,63}\\.)?onlyoffice\\.disk\\.yandex\\.net")
        val READY_PHASES = setOf("control", "PROTECT_CHANNEL_LISTENING", "PROTECT_CHANNEL_READY", "RUNNING", "STOPPED")
        val ERROR_CODES = setOf("invalid_init", "lease_expired", "provider_auth_expired", "invalid_provider_auth", "network_channel_failed", "provider_authorization_failed", "session_configuration_failed", "strict_handshake_failed", "proxy_failed", "control_channel_failed", "bad_command", "transport_failed")
        val REFRESH_CODES = setOf("invalid_refresh", "refresh_busy", "refresh_not_running", "refresh_authorization_failed", "refresh_expired")

        fun literalAddress(value: String): Boolean {
            if (value.length !in 3..45 || !value.matches(Regex("[0-9a-fA-F:.]+"))) return false
            if (!value.contains(':')) return value.split('.').let { parts ->
                parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && (it.length == 1 || it[0] != '0') && it.toIntOrNull() in 0..255 }
            }
            return runCatching { InetAddress.getByName(value) }.isSuccess
        }
    }
}

internal class YandexControlStateMachine(private val codec: YandexControlCodec) {
    @Volatile var running = false
        private set
    private var phase = 0
    @Synchronized fun accept(event: YandexEventWire): YandexControlAction {
        if (event.type == "error") return YandexControlAction.NativeFailure(requireNotNull(event.code))
        if (event.type == "refreshed") {
            if (!running || phase != 5) throw YandexProtocolException("yandex_protocol_state")
            return YandexControlAction.Refreshed(codec.refreshResult(event))
        }
        if (event.type in setOf("stats", "status")) {
            if (phase < 3) throw YandexProtocolException("yandex_protocol_state")
            return YandexControlAction.Continue
        }
        val action = when (phase) {
            0 -> if (event.type == "ready" && event.phase == "control") YandexControlAction.Continue else null
            1 -> if (event.type == "ready" && event.phase == "PROTECT_CHANNEL_LISTENING") YandexControlAction.ConnectNetwork else null
            2 -> if (event.type == "ready" && event.phase == "PROTECT_CHANNEL_READY") YandexControlAction.Continue else null
            3 -> if (event.type == "proxy_plan" && event.phase == "PREPARED") YandexControlAction.PreparedProxy(codec.proxyPort(event)) else null
            4 -> if (event.type == "ready" && event.phase == "RUNNING") YandexControlAction.Running else null
            else -> null
        } ?: throw YandexProtocolException("yandex_protocol_state")
        phase++
        if (action == YandexControlAction.Running) running = true
        return action
    }
}

private fun failure(code: String): Nothing = throw YandexProtocolException(code)

@Serializable private data class YandexInitWire(
    val magic: String = YANDEX_CONTROL_MAGIC,
    val version: Int = YANDEX_CONTROL_VERSION,
    val type: String = "init",
    val documentUrl: String,
    val leaseRef: String,
    val sharedKey: String,
    val leaseExpiresAt: Long,
    val providerAuth: YandexProviderAuth,
    val protectFdSocket: String,
    val proxyUsername: String,
    val proxyPassword: String,
)
@Serializable private data class YandexCommandWire(val magic: String = YANDEX_CONTROL_MAGIC, val version: Int = YANDEX_CONTROL_VERSION, val type: String)
@Serializable private data class YandexRefreshWire(val magic: String = YANDEX_CONTROL_MAGIC, val version: Int = YANDEX_CONTROL_VERSION, val type: String = "REFRESH", val requestId: Long, val leaseExpiresAt: Long, val providerAuth: YandexProviderAuth)
@Serializable private data class YandexProxyWire(val address: String, val port: Int)
@Serializable private data class YandexRunningWire(val protocolVersion: Int)
@Serializable internal data class YandexStatusWire(val state: String, val strictSession: Boolean, val bytesUp: Long, val bytesDown: Long, val protectedExternalSockets: Long, val resolvedProviderHosts: Long, val carrierPacketsUp: Long = 0, val carrierPacketsDown: Long = 0, val dataPath: YandexDataPathWire = YandexDataPathWire(), val proxy: YandexProxyDiagnosticsWire = YandexProxyDiagnosticsWire())
@Serializable internal data class YandexProxyDiagnosticsWire(val connections: Long = 0, val rejected: Long = 0, val udpAssociations: Long = 0)
@Serializable internal data class YandexDataPathWire(val dataSent: Long = 0, val dataReceived: Long = 0, val dataDropped: Long = 0, val decodeErrors: Long = 0, val cryptoRejected: Long = 0, val batchSendErrors: Long = 0)
@Serializable private data class YandexNetworkWire(val magic: String, val version: Int, val type: String, val requestId: Long, val network: String? = null, val address: String? = null, val host: String? = null)
@Serializable private data class YandexProtectAckWire(val magic: String = YANDEX_CONTROL_MAGIC, val version: Int = YANDEX_CONTROL_VERSION, val type: String = "PROTECT_SOCKET_ACK", val requestId: Long, val ok: Boolean, val code: String? = null)
@Serializable private data class YandexResolveAckWire(val magic: String = YANDEX_CONTROL_MAGIC, val version: Int = YANDEX_CONTROL_VERSION, val type: String = "RESOLVE_HOST_ACK", val requestId: Long, val ok: Boolean, val addresses: List<String>, val code: String? = null)
