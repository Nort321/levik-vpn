package com.leviknet.vpn.core.telemetry

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Wire values from docs/connection-telemetry.md in the public repository.

enum class SessionTrigger(val wire: String) {
    USER("user"),
    AUTO_CONNECT("auto_connect"),
    BOOT("boot"),
    ALWAYS_ON("always_on"),
    TILE("tile"),
    WIDGET("widget"),
    UNTRUSTED_WIFI("untrusted_wifi"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): SessionTrigger = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

enum class AttemptCause(val wire: String) {
    INITIAL("initial"),
    RECONNECT("reconnect"),
    FAILOVER("failover"),
    NETWORK_CHANGE("network_change"),
    RESUME("resume"),
    SERVER_SWITCH("server_switch"),
    ROLLBACK("rollback"),
}

enum class AttemptStage(val wire: String) {
    PROFILE("profile"),
    CORE("core"),
    TUN("tun"),
    HANDSHAKE("handshake"),
    VERIFY("verify"),
}

enum class RecoveryAction(val wire: String) {
    RECONNECT_SAME("reconnect_same"),
    FAILOVER("failover"),
    ROLLBACK("rollback"),
    LOCKDOWN("lockdown"),
    GAVE_UP("gave_up"),
}

enum class PowerState(val wire: String) {
    DOZE_ON("doze_on"),
    DOZE_OFF("doze_off"),
    SCREEN_OFF("screen_off"),
    SCREEN_ON("screen_on"),
}

enum class NetworkType(val wire: String) {
    WIFI("wifi"),
    CELLULAR("cellular"),
    ETHERNET("ethernet"),
    OTHER("other"),
    UNKNOWN("unknown"),
}

enum class NetworkState(val wire: String) {
    AVAILABLE("available"),
    LOST("lost"),
    CHANGED("changed"),
}

enum class EndBy(val wire: String) {
    USER("user"),
    SYSTEM("system"),
    ERROR("error"),
    OS_KILLED("os_killed"),
    UNKNOWN("unknown"),
}

enum class TelemetryProtocol(val wire: String) {
    VLESS_REALITY("vless-reality"),
    VLESS_XHTTP("vless-xhttp"),
    VLESS_WS("vless-ws"),
    VLESS_GRPC("vless-grpc"),
    VLESS_TCP("vless-tcp"),
    HYSTERIA2("hysteria2"),
    TUIC("tuic"),
    TROJAN("trojan"),
    SHADOWSOCKS("shadowsocks"),
    RELAY("relay"),
    YANDEX("yandex"),
    OTHER("other"),
}

data class TelemetryClientInfo(
    val app: String,
    val os: String,
    val oem: String,
)

data class TelemetrySessionSettings(
    val killSwitch: Boolean,
    val autoRecovery: Boolean,
    val splitTunnel: Boolean,
    val batteryUnrestricted: Boolean,
)

data class SessionEnd(val by: EndBy, val code: String?)

const val MAX_TIMELINE_EVENTS = 200
// The first events explain how a session started; the rest keeps the latest.
private const val KEPT_HEAD_EVENTS = 120
private const val MAX_COUNTER = 1_000
const val MAX_SESSION_MS = 31L * 24 * 60 * 60 * 1_000
private val CODE = Regex("^[a-z0-9_.:-]{1,48}$")

fun safeTelemetryCode(code: String): String = if (CODE.matches(code)) code else "other"

private fun sanitized(value: String, allowed: Regex, fallback: String): String =
    value.filter { allowed.matches(it.toString()) }.take(32).ifBlank { fallback }

/** Normalizes Android build values to the formats the server accepts. */
fun telemetryClientInfo(versionName: String, osRelease: String, manufacturer: String): TelemetryClientInfo =
    TelemetryClientInfo(
        app = sanitized(versionName, Regex("[0-9A-Za-z.+_-]"), "unknown"),
        os = sanitized(osRelease.substringBefore('.'), Regex("[0-9A-Za-z._-]"), "unknown"),
        oem = sanitized(manufacturer.lowercase(java.util.Locale.ROOT), Regex("[a-z0-9._-]"), "unknown"),
    )

/** One connection session, from Connect until the tunnel is given up. Thread-safe. */
class SessionRecorder(
    private val client: TelemetryClientInfo,
    private val trigger: SessionTrigger,
    private var settings: TelemetrySessionSettings,
    private val now: () -> Long = System::currentTimeMillis,
    val sid: String = UUID.randomUUID().toString(),
) {
    /** Wall-clock start; ageS is recomputed from it when a queued report is sent. */
    val startedAt: Long = now()
    private val timeline = ArrayList<JsonObject>()
    private var seq = 0
    private var netType = NetworkType.UNKNOWN
    private var token: String? = null
    private var probeFailures = 0
    private var ended: SessionEnd? = null
    private var endedDurationS = 0L

    @get:Synchronized
    val finished: Boolean get() = ended != null

    @get:Synchronized
    val hasNetworkToken: Boolean get() = token != null

    @Synchronized
    fun setNetwork(type: NetworkType) {
        netType = type
    }

    @Synchronized
    fun setNetworkToken(value: String?) {
        token = value
    }

    @Synchronized
    fun updateSettings(value: TelemetrySessionSettings) {
        settings = value
    }

    fun attempt(node: String, protocol: TelemetryProtocol, cause: AttemptCause) = push("attempt") {
        put("node", node.take(160))
        put("proto", protocol.wire)
        put("cause", cause.wire)
    }

    @Synchronized
    fun connected() {
        probeFailures = 0
        push("connected") {}
    }

    fun attemptFailed(stage: AttemptStage, code: String) = push("attempt_failed") {
        put("stage", stage.wire)
        put("code", safeTelemetryCode(code))
    }

    @Synchronized
    fun probeFailed(codes: List<String>) {
        if (ended != null) return
        probeFailures = (probeFailures + 1).coerceAtMost(MAX_COUNTER)
        val unique = codes.map(::safeTelemetryCode).distinct().take(4).ifEmpty { listOf("other") }
        push("probe_fail") {
            put("codes", JsonArray(unique.map(::JsonPrimitive)))
            put("n", probeFailures)
        }
    }

    @Synchronized
    fun probeSucceeded() {
        if (probeFailures == 0) return
        push("probe_ok") { put("afterFailures", probeFailures) }
        probeFailures = 0
    }

    fun coreExit(code: Int?, expected: Boolean) = push("core_exit") {
        put("code", code?.let(::JsonPrimitive) ?: JsonNull)
        put("expected", expected)
    }

    fun network(type: NetworkType, state: NetworkState) = push("net") {
        put("type", type.wire)
        put("state", state.wire)
    }

    fun power(state: PowerState) = push("power") { put("state", state.wire) }

    fun recovery(action: RecoveryAction) = push("recovery") { put("action", action.wire) }

    fun pause() = push("pause") {}

    fun unpause() = push("unpause") {}

    @Synchronized
    fun end(by: EndBy, code: String?) {
        if (ended != null) return
        ended = SessionEnd(by, code?.let(::safeTelemetryCode))
        endedDurationS = elapsedSeconds()
    }

    /** A checkpoint; every call gets a higher seq so the server keeps the latest. */
    @Synchronized
    fun snapshot(): JsonObject = buildJsonObject {
        put("v", 1)
        put("sid", sid)
        put("seq", seq++)
        put("final", ended != null)
        put("ageS", elapsedSeconds())
        put("client", buildJsonObject {
            put("platform", "android")
            put("app", client.app)
            put("os", client.os)
            put("oem", client.oem)
        })
        put("net", buildJsonObject {
            put("type", netType.wire)
            put("token", token?.let(::JsonPrimitive) ?: JsonNull)
        })
        put("trigger", trigger.wire)
        put("settings", buildJsonObject {
            put("killSwitch", settings.killSwitch)
            put("autoRecovery", settings.autoRecovery)
            put("splitTunnel", settings.splitTunnel)
            put("batteryUnrestricted", settings.batteryUnrestricted)
        })
        put("timeline", JsonArray(timeline.toList()))
        ended?.let { end ->
            put("end", endObject(end, endedDurationS))
        }
    }

    private fun elapsedMs(): Long = (now() - startedAt).coerceIn(0, MAX_SESSION_MS)

    private fun elapsedSeconds(): Long = elapsedMs() / 1_000

    @Synchronized
    private fun push(event: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        if (ended != null) return
        val previous = timeline.lastOrNull()?.get("t")?.let { (it as JsonPrimitive).content.toLong() } ?: 0L
        val t = maxOf(previous, elapsedMs())
        if (timeline.size >= MAX_TIMELINE_EVENTS) timeline.removeAt(KEPT_HEAD_EVENTS)
        timeline += buildJsonObject {
            put("t", t)
            put("e", event)
            fields()
        }
    }
}

internal fun endObject(end: SessionEnd, durationS: Long): JsonObject = buildJsonObject {
    put("by", end.by.wire)
    put("code", end.code?.let(::JsonPrimitive) ?: JsonNull)
    put("durationS", durationS.coerceIn(0, MAX_SESSION_MS / 1_000))
}
