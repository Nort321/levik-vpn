package com.leviknet.vpn.core.telemetry

import com.leviknet.vpn.core.network.WhitelistDetectionDiagnostic
import com.leviknet.vpn.core.network.WhitelistMode
import com.leviknet.vpn.core.network.WhitelistProbeDiagnostic
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
private val NODE_ID = Regex("^n1[0-9a-f]{20}$")

/** The website accepts check times up to a minute. */
private const val MAX_CHECK_MS = 60_000L
private const val WHITELIST_SUMMARY_INTERVAL_MS = 5 * 60_000L
private val WHITELIST_PROBES = setOf("ozon", "yandex", "avito", "vk", "cloudflare", "mozilla", "ovh", "github", "wikipedia")

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
    private var lastWhitelistMode: WhitelistMode? = null
    private var lastWhitelistSummaryMs: Long? = null
    private val lastWhitelistProbes = mutableMapOf<String, String>()
    private var attemptResultRecorded = false
    private var pendingWhitelistDiagnostic: WhitelistDetectionDiagnostic? = null

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
        publishPendingWhitelistDiagnostic()
    }

    @Synchronized
    fun attemptFailed(stage: AttemptStage, code: String) {
        push("attempt_failed") {
            put("stage", stage.wire)
            put("code", safeTelemetryCode(code))
        }
        publishPendingWhitelistDiagnostic()
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

    /**
     * A 64 KB traffic check through the tunnel to node [nodeId], docs/connection-telemetry.md:
     * the website ranks nodes per operator by it, docs/app-platform.md "Node quality".
     */
    fun trafficCheck(nodeId: String, ms: Long, ok: Boolean) {
        if (!NODE_ID.matches(nodeId)) return
        push("check") {
            put("nid", nodeId)
            put("ms", ms.coerceIn(0L, MAX_CHECK_MS))
            put("ok", ok)
        }
    }

    /** Fixed diagnostic codes reuse the existing wire contract, without addresses or raw logs. */
    @Synchronized
    fun whitelistDetection(diagnostic: WhitelistDetectionDiagnostic) {
        if (ended != null) return
        // The deployed server uses a core_log code to explain a never-connected
        // session. A cancelled attempt must not acquire a diagnostic as its error.
        if (!attemptResultRecorded) {
            pendingWhitelistDiagnostic = diagnostic
            return
        }
        val probes = diagnostic.probes.filter { it.id in WHITELIST_PROBES }.distinctBy { it.id }
        // Time jitter alone must not fill the bounded timeline with repeated scans.
        val changed = probes.filter { lastWhitelistProbes[it.id] != whitelistProbeState(it) }
        val elapsed = elapsedMs()
        val lastSummary = lastWhitelistSummaryMs
        if (diagnostic.mode == lastWhitelistMode && changed.isEmpty() &&
            lastSummary != null && elapsed - lastSummary < WHITELIST_SUMMARY_INTERVAL_MS
        ) return
        diagnosticCode("wl.mode.${diagnostic.mode.name.lowercase(java.util.Locale.ROOT)}.t${diagnosticTimeBucket(diagnostic.elapsedMs)}")
        changed.forEach { probe ->
            val status = probe.httpStatus?.takeIf { it in 100..599 }?.let { ".h$it" }.orEmpty()
            diagnosticCode(
                "wl.${probe.id}.${probe.outcome.wire}$status" +
                    ".t${diagnosticTimeBucket(probe.elapsedMs)}.b${diagnosticByteBucket(probe.receivedBytes)}",
            )
        }
        probes.forEach { lastWhitelistProbes[it.id] = whitelistProbeState(it) }
        lastWhitelistMode = diagnostic.mode
        lastWhitelistSummaryMs = elapsed
    }

    private fun publishPendingWhitelistDiagnostic() {
        attemptResultRecorded = true
        val diagnostic = pendingWhitelistDiagnostic ?: return
        pendingWhitelistDiagnostic = null
        whitelistDetection(diagnostic)
    }

    /** Mobile checks download 32 KiB, so they must not affect the regular 64 KiB node ranking. */
    fun mobileTrafficCheck(nodeId: String?, protocol: TelemetryProtocol, ms: Long?) {
        val node = nodeId?.takeIf(NODE_ID::matches) ?: protocol.wire
        val result = ms?.let { "ok.t${diagnosticTimeBucket(it)}" } ?: "timeout"
        diagnosticCode("mobile_check.$node.$result")
    }

    fun mobileNetworkOffline() = diagnosticCode("mobile_check.network_offline")

    private fun diagnosticCode(code: String) = push("core_log") {
        put("code", safeTelemetryCode(code))
        put("count", 1)
    }

    fun pause() = push("pause") {}

    fun unpause() = push("unpause") {}

    @Synchronized
    fun end(by: EndBy, code: String?) {
        if (ended != null) return
        ended = SessionEnd(by, code?.let(::safeTelemetryCode))
        pendingWhitelistDiagnostic = null
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

private fun whitelistProbeState(probe: WhitelistProbeDiagnostic): String =
    "${probe.outcome.wire}:${probe.httpStatus?.takeIf { it in 100..599 }}:${diagnosticByteBucket(probe.receivedBytes)}"

private fun diagnosticTimeBucket(ms: Long): Int = when {
    ms < 100L -> 0
    ms < 500L -> 1
    ms < 2_000L -> 2
    ms < 6_000L -> 3
    else -> 4
}

private fun diagnosticByteBucket(bytes: Int): Int = when {
    bytes <= 0 -> 0
    bytes < 1_024 -> 1
    bytes < 16 * 1_024 -> 2
    bytes < 32 * 1_024 -> 3
    else -> 4
}

internal fun endObject(end: SessionEnd, durationS: Long): JsonObject = buildJsonObject {
    put("by", end.by.wire)
    put("code", end.code?.let(::JsonPrimitive) ?: JsonNull)
    put("durationS", durationS.coerceIn(0, MAX_SESSION_MS / 1_000))
}
