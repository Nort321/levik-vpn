package com.leviknet.vpn.vpn

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement

internal const val RELAY_CONTROL_VERSION = 2
internal const val RELAY_MAX_CONTROL_MESSAGE_BYTES = 64 * 1024

internal class RelayProtocolException(
    val stableCode: String,
) : IllegalStateException(stableCode)

internal data class RelayNativeInit(
    val peer: String,
    val turnHashes: List<String>,
    val accessToken: String,
    val deviceId: String,
    val workers: Int,
    val turnFrontSni: String,
    val protectFdSocket: String,
    val serverPublicKey: String,
    val vkAuthMode: String,
    val proxyUsername: String,
    val proxyPassword: String,
)

internal data class RelayControlEvent(
    val type: String,
    val phase: String?,
    val code: String?,
    val data: JsonElement?,
)

internal data class RelayProtectRequest(
    val requestId: Long,
)

internal data class RelayVkAuthRequest(
    val requestId: String,
    val hash: String,
)

internal data class RelayTurnCredentials(
    val username: String,
    val password: String,
    val urls: List<String>,
)

internal sealed interface RelayControlAction {
    data object Continue : RelayControlAction
    data object ConnectProtectChannel : RelayControlAction
    data class PreparedProxy(val address: String, val port: Int) : RelayControlAction
    data object Running : RelayControlAction
    data class NativeFailure(val code: String) : RelayControlAction
    data class Diagnostic(val code: String) : RelayControlAction
    data class RequestVkAuth(val request: RelayVkAuthRequest) : RelayControlAction
}

internal enum class RelayControlState {
    CREATED,
    WAIT_CONTROL_READY,
    WAIT_PROTECT_LISTENING,
    WAIT_PROTECT_READY,
    WAIT_PROXY_PLAN,
    PREPARED,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED,
}

/** Strict v2 native-control codec. It never forwards native free-form messages to the UI/logs. */
internal class RelayControlCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = false
        encodeDefaults = true
        allowSpecialFloatingPointValues = false
    }

    fun encodeInit(init: RelayNativeInit): String {
        validateInit(init)
        return json.encodeToString(
            RelayControlInitWire(
                peer = init.peer,
                vkHashes = init.turnHashes,
                password = init.accessToken,
                deviceId = init.deviceId,
                workers = init.workers,
                turnSni = init.turnFrontSni,
                protectFdSocket = init.protectFdSocket,
                serverPublicKey = init.serverPublicKey,
                vkAuthMode = init.vkAuthMode,
                proxyUsername = init.proxyUsername,
                proxyPassword = init.proxyPassword,
            ),
        )
    }

    fun encodeStop(): String = json.encodeToString(
        RelayControlCommandWire(type = "STOP"),
    )

    fun encodeTurnCredentials(
        request: RelayVkAuthRequest,
        credentials: RelayTurnCredentials,
    ): String {
        validateTurnCredentials(credentials)
        return json.encodeToString(
            RelayControlCommandWire(
                type = "TURN_CREDS",
                requestId = request.requestId,
                hash = request.hash,
                username = credentials.username,
                password = credentials.password,
                urls = credentials.urls,
            ),
        )
    }

    fun decodeEvent(payload: String): RelayControlEvent {
        val wire = decodeStrict<RelayControlEventWire>(payload)
        if (wire.version != RELAY_CONTROL_VERSION) protocolFailure("relay_protocol_version")
        if (wire.type !in EVENT_TYPES) protocolFailure("relay_protocol_event_type")
        when (wire.type) {
            "ready" -> validateReady(wire)
            "proxy_plan" -> validateProxyPlanEnvelope(wire)
            "stats" -> validateStats(wire)
            "error" -> validateError(wire)
            "vk_auth_required" -> validateVkAuthRequired(wire)
            "diagnostic" -> validateDiagnostic(wire)
        }
        return RelayControlEvent(
            type = wire.type,
            phase = wire.phase,
            code = wire.code,
            data = wire.data,
        )
    }

    fun decodeProxyPlan(event: RelayControlEvent): Pair<String, Int> {
        if (event.type != "proxy_plan" || event.phase != "PREPARED") {
            protocolFailure("relay_protocol_proxy_plan_state")
        }
        val wire = decodeElement<RelayProxyPlanWire>(event.data)
        if (wire.address != "127.0.0.1" || wire.port !in 1..65_535) {
            protocolFailure("relay_protocol_proxy_plan")
        }
        return wire.address to wire.port
    }

    fun decodeProtectRequest(payload: String): RelayProtectRequest {
        val wire = decodeStrict<RelayProtectRequestWire>(payload)
        if (wire.version != RELAY_CONTROL_VERSION ||
            wire.type != "PROTECT_SOCKET" ||
            wire.requestId <= 0L ||
            wire.network.isBlank() ||
            wire.network.length > MAX_METADATA_LENGTH ||
            wire.address.isBlank() ||
            wire.address.length > MAX_METADATA_LENGTH ||
            wire.network.hasControlCharacters() ||
            wire.address.hasControlCharacters()
        ) {
            protocolFailure("relay_protocol_protect_request")
        }
        return RelayProtectRequest(wire.requestId)
    }

    fun decodeVkAuthRequest(event: RelayControlEvent): RelayVkAuthRequest {
        if (event.type != "vk_auth_required" || event.phase != null || event.code != null) {
            protocolFailure("relay_protocol_vk_auth")
        }
        val wire = decodeElement<RelayVkAuthRequestWire>(event.data)
        if (!wire.requestId.matches(REQUEST_ID) || !wire.hash.matches(TURN_HASH)) {
            protocolFailure("relay_protocol_vk_auth")
        }
        return RelayVkAuthRequest(wire.requestId, wire.hash)
    }

    fun encodeProtectAck(
        requestId: Long,
        success: Boolean,
    ): String = json.encodeToString(
        RelayProtectAckWire(
            requestId = requestId,
            ok = success,
            code = if (success) null else "protect_bind_failed",
        ),
    )

    private fun validateInit(init: RelayNativeInit) {
        if (init.peer.isBlank() || init.peer.length > 255 || init.peer.hasControlCharacters()) {
            protocolFailure("relay_init_peer")
        }
        if (init.turnHashes.isEmpty() || init.turnHashes.size > 4 ||
            init.turnHashes.any { !it.matches(TURN_HASH) }
        ) {
            protocolFailure("relay_init_turn_hashes")
        }
        if (!init.accessToken.matches(ACCESS_TOKEN) || !init.deviceId.matches(DEVICE_ID)) {
            protocolFailure("relay_init_credentials")
        }
        if (init.workers !in MIN_WORKERS..MAX_WORKERS || init.workers % MIN_WORKERS != 0) {
            protocolFailure("relay_init_workers")
        }
        if (!init.turnFrontSni.matches(DNS_NAME) ||
            !init.protectFdSocket.matches(SOCKET_NAME) ||
            !init.serverPublicKey.matches(SERVER_PUBLIC_KEY)
        ) {
            protocolFailure("relay_init_contract")
        }
        if (init.vkAuthMode !in setOf("anonymous", "account")) {
            protocolFailure("relay_init_vk_auth")
        }
        if (!init.proxyUsername.matches(PROXY_USERNAME) ||
            !init.proxyPassword.matches(PROXY_PASSWORD)
        ) {
            protocolFailure("relay_init_proxy_auth")
        }
    }

    private fun validateReady(wire: RelayControlEventWire) {
        if (wire.code != null || wire.message != null || wire.phase !in READY_PHASES) {
            protocolFailure("relay_protocol_ready")
        }
        when (wire.phase) {
            "TRANSPORT_LISTENING" -> {
                val data = decodeElement<RelayTransportListeningWire>(wire.data)
                val port = data.localPort.toIntOrNull()
                if (port == null || port !in 1..65_535) {
                    protocolFailure("relay_protocol_transport_port")
                }
            }
            "RUNNING" -> {
                val data = decodeElement<RelayRunningWire>(wire.data)
                if (data.protocolVersion != RELAY_CONTROL_VERSION) {
                    protocolFailure("relay_protocol_running_version")
                }
            }
            else -> if (wire.data != null && wire.data !is JsonNull) {
                protocolFailure("relay_protocol_ready_data")
            }
        }
    }

    private fun validateProxyPlanEnvelope(wire: RelayControlEventWire) {
        if (wire.phase != "PREPARED" || wire.code != null || wire.message != null || wire.data == null) {
            protocolFailure("relay_protocol_proxy_plan")
        }
        decodeElement<RelayProxyPlanWire>(wire.data)
    }

    private fun validateStats(wire: RelayControlEventWire) {
        if (wire.phase != null || wire.code != null || wire.message != null) {
            protocolFailure("relay_protocol_stats")
        }
        val data = decodeElement<RelayStatsWire>(wire.data)
        if (runCatching { Instant.parse(data.at) }.isFailure ||
            data.activeConnections < 0L ||
            data.bytesUp < 0L ||
            data.bytesDown < 0L ||
            data.protectedExternalSockets < 0L ||
            data.rejectedUnprotectedSockets < 0L
        ) {
            protocolFailure("relay_protocol_stats")
        }
    }

    private fun validateDiagnostic(wire: RelayControlEventWire) {
        if (wire.phase != null || wire.message != null || wire.data != null ||
            wire.code !in DIAGNOSTIC_CODES
        ) {
            protocolFailure("relay_protocol_diagnostic")
        }
    }

    private fun validateError(wire: RelayControlEventWire) {
        if (wire.phase != null || wire.data != null ||
            wire.code == null || !wire.code.matches(NATIVE_ERROR_CODE) ||
            wire.message?.let { it.length > MAX_NATIVE_MESSAGE_LENGTH || it.hasControlCharacters() } == true
        ) {
            protocolFailure("relay_protocol_error")
        }
    }

    private fun validateVkAuthRequired(wire: RelayControlEventWire) {
        if (wire.phase != null || wire.code != null || wire.message != null || wire.data == null) {
            protocolFailure("relay_protocol_vk_auth")
        }
        decodeElement<RelayVkAuthRequestWire>(wire.data)
    }

    private fun validateTurnCredentials(credentials: RelayTurnCredentials) {
        if (credentials.username.isBlank() || credentials.username.length > 512 ||
            credentials.password.isBlank() || credentials.password.length > 512 ||
            credentials.urls.isEmpty() || credentials.urls.size > 16 ||
            credentials.urls.any { it.isBlank() || it.length > 512 || it.hasControlCharacters() }
        ) {
            protocolFailure("relay_turn_credentials")
        }
    }

    private inline fun <reified T> decodeStrict(payload: String): T = try {
        json.decodeFromString(payload)
    } catch (_: SerializationException) {
        protocolFailure("relay_protocol_json")
    } catch (_: IllegalArgumentException) {
        protocolFailure("relay_protocol_json")
    }

    private inline fun <reified T> decodeElement(element: JsonElement?): T {
        if (element == null || element is JsonNull) protocolFailure("relay_protocol_data")
        return try {
            json.decodeFromJsonElement(element)
        } catch (_: SerializationException) {
            protocolFailure("relay_protocol_data")
        } catch (_: IllegalArgumentException) {
            protocolFailure("relay_protocol_data")
        }
    }

    private fun String.hasControlCharacters(): Boolean = any { it.code < 0x20 || it.code == 0x7f }

    private companion object {
        const val MIN_WORKERS = 9
        const val MAX_WORKERS = 108
        const val MAX_METADATA_LENGTH = 512
        const val MAX_NATIVE_MESSAGE_LENGTH = 128
        val EVENT_TYPES = setOf(
            "ready",
            "proxy_plan",
            "stats",
            "error",
            "vk_auth_required",
            "diagnostic",
        )
        val DIAGNOSTIC_CODES = setOf(
            "turn_credentials_received",
            "turn_tls_attempt",
            "turn_tcp_attempt",
            "turn_udp_attempt",
            "turn_tls_failed",
            "turn_tcp_failed",
            "turn_udp_failed",
            "turn_allocation_ready",
            "dtls_handshake_started",
            "dtls_handshake_failed",
            "dtls_handshake_ready",
            "relay_config_received",
        )
        val READY_PHASES = setOf(
            "control",
            "PROTECT_CHANNEL_LISTENING",
            "PROTECT_CHANNEL_READY",
            "TRANSPORT_LISTENING",
            "RUNNING",
        )
        val TURN_HASH = Regex("^[A-Za-z0-9_-]{16,256}$")
        val ACCESS_TOKEN = Regex("^[ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789]{16}$")
        val DEVICE_ID = Regex("^[0-9a-f]{64}$")
        val DNS_NAME = Regex("^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$|^(?=.{1,63}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
        val SOCKET_NAME = Regex("^@levik_wlr_[A-Za-z0-9_-]{12,95}$")
        val SERVER_PUBLIC_KEY = Regex("^[A-Za-z0-9_-]{43}$")
        val PROXY_USERNAME = Regex("^[A-Za-z0-9_-]{16,64}$")
        val PROXY_PASSWORD = Regex("^[A-Za-z0-9_-]{32,128}$")
        val NATIVE_ERROR_CODE = Regex("^[a-z0-9_]{1,64}$")
        val REQUEST_ID = Regex("^[0-9]{1,10}-[0-9]{1,20}$")
    }
}

internal class RelayControlStateMachine(
    private val codec: RelayControlCodec,
) {
    var state: RelayControlState = RelayControlState.CREATED
        private set

    @Synchronized
    fun markInitSent() {
        requireState(RelayControlState.CREATED)
        state = RelayControlState.WAIT_CONTROL_READY
    }

    @Synchronized
    fun accept(event: RelayControlEvent): RelayControlAction {
        if (event.type == "error") {
            if (state in TERMINAL_STATES) protocolFailure("relay_protocol_error_state")
            state = RelayControlState.FAILED
            return RelayControlAction.NativeFailure(requireNotNull(event.code))
        }
        if (event.type == "stats") {
            if (state !in STATS_STATES) protocolFailure("relay_protocol_stats_state")
            return RelayControlAction.Continue
        }
        if (event.type == "vk_auth_required") {
            if (state !in setOf(RelayControlState.WAIT_PROXY_PLAN, RelayControlState.RUNNING)) {
                protocolFailure("relay_protocol_vk_auth_state")
            }
            return RelayControlAction.RequestVkAuth(codec.decodeVkAuthRequest(event))
        }
        if (event.type == "diagnostic") {
            if (state in TERMINAL_STATES) protocolFailure("relay_protocol_diagnostic_state")
            return RelayControlAction.Diagnostic(requireNotNull(event.code))
        }
        return when (state) {
            RelayControlState.WAIT_CONTROL_READY -> {
                requireReady(event, "control")
                state = RelayControlState.WAIT_PROTECT_LISTENING
                RelayControlAction.Continue
            }
            RelayControlState.WAIT_PROTECT_LISTENING -> {
                requireReady(event, "PROTECT_CHANNEL_LISTENING")
                state = RelayControlState.WAIT_PROTECT_READY
                RelayControlAction.ConnectProtectChannel
            }
            RelayControlState.WAIT_PROTECT_READY -> {
                requireReady(event, "PROTECT_CHANNEL_READY")
                state = RelayControlState.WAIT_PROXY_PLAN
                RelayControlAction.Continue
            }
            RelayControlState.WAIT_PROXY_PLAN -> when {
                event.type == "ready" && event.phase == "TRANSPORT_LISTENING" ->
                    RelayControlAction.Continue
                event.type == "proxy_plan" && event.phase == "PREPARED" -> {
                    val (address, port) = codec.decodeProxyPlan(event)
                    state = RelayControlState.PREPARED
                    RelayControlAction.PreparedProxy(address, port)
                }
                else -> protocolFailure("relay_protocol_prepare_state")
            }
            RelayControlState.PREPARED -> {
                requireReady(event, "RUNNING")
                state = RelayControlState.RUNNING
                RelayControlAction.Running
            }
            RelayControlState.RUNNING -> protocolFailure("relay_protocol_running_event")
            else -> protocolFailure("relay_protocol_state")
        }
    }

    @Synchronized
    fun beginStop(): Boolean {
        if (state == RelayControlState.STOPPED) return false
        if (state == RelayControlState.STOPPING) return false
        val shouldSendCommand = state !in setOf(
            RelayControlState.CREATED,
            RelayControlState.FAILED,
        )
        state = RelayControlState.STOPPING
        return shouldSendCommand
    }

    @Synchronized
    fun markStopped() {
        state = RelayControlState.STOPPED
    }

    private fun requireReady(event: RelayControlEvent, phase: String) {
        if (event.type != "ready" || event.phase != phase) {
            protocolFailure("relay_protocol_phase")
        }
    }

    private fun requireState(expected: RelayControlState) {
        if (state != expected) protocolFailure("relay_protocol_state")
    }

    private companion object {
        val TERMINAL_STATES = setOf(
            RelayControlState.STOPPING,
            RelayControlState.STOPPED,
            RelayControlState.FAILED,
        )
        val STATS_STATES = setOf(
            RelayControlState.WAIT_PROXY_PLAN,
            RelayControlState.PREPARED,
            RelayControlState.RUNNING,
        )
    }
}

private fun protocolFailure(code: String): Nothing = throw RelayProtocolException(code)

@Serializable
private data class RelayControlInitWire(
    val version: Int = RELAY_CONTROL_VERSION,
    val type: String = "init",
    val peer: String,
    val vkHashes: List<String>,
    val password: String,
    val deviceId: String,
    val workers: Int,
    val turnStreamFirst: Boolean = true,
    val turnSni: String,
    val fingerprint: String = "android",
    val protectFdSocket: String,
    val serverPublicKey: String,
    val vkAuthMode: String,
    val proxyUsername: String,
    val proxyPassword: String,
)

@Serializable
private data class RelayControlCommandWire(
    val version: Int = RELAY_CONTROL_VERSION,
    val type: String,
    val requestId: String? = null,
    val hash: String? = null,
    val username: String? = null,
    val password: String? = null,
    val urls: List<String>? = null,
)

@Serializable
private data class RelayVkAuthRequestWire(
    val requestId: String,
    val hash: String,
)

@Serializable
private data class RelayControlEventWire(
    val version: Int,
    val type: String,
    val phase: String? = null,
    val code: String? = null,
    val message: String? = null,
    val data: JsonElement? = null,
)

@Serializable
private data class RelayProxyPlanWire(
    val address: String,
    val port: Int,
)

@Serializable
private data class RelayTransportListeningWire(
    val localPort: String,
)

@Serializable
private data class RelayRunningWire(
    val protocolVersion: Int,
)

@Serializable
private data class RelayStatsWire(
    val at: String,
    val activeConnections: Long,
    val bytesUp: Long,
    val bytesDown: Long,
    val protectedExternalSockets: Long,
    val rejectedUnprotectedSockets: Long,
)

@Serializable
private data class RelayProtectRequestWire(
    val version: Int,
    val type: String,
    val requestId: Long,
    val network: String,
    val address: String,
)

@Serializable
private data class RelayProtectAckWire(
    val version: Int = RELAY_CONTROL_VERSION,
    val type: String = "PROTECT_SOCKET_ACK",
    val requestId: Long,
    val ok: Boolean,
    val code: String? = null,
)
