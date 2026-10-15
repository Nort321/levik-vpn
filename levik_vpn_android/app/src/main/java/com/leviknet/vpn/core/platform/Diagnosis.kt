package com.leviknet.vpn.core.platform

import kotlinx.serialization.Serializable

/**
 * "It's not the VPN, it's the internet", docs/app-platform.md "Diagnosis":
 * what the device saw and what /api/app/v1/diagnose knows, folded into one
 * sentence and one button.
 */

@Serializable
data class DiagnoseReport(
    val network: DiagnoseNetwork = DiagnoseNetwork(),
    val whitelist: DiagnoseWhitelist = DiagnoseWhitelist(),
    val services: List<DiagnoseService> = emptyList(),
    val protocols: DiagnoseProtocols? = null,
    val countries: List<DiagnoseCountry> = emptyList(),
    val maintenance: List<DiagnoseMaintenance> = emptyList(),
)

@Serializable
data class DiagnoseNetwork(val known: Boolean = false, val provider: String? = null, val country: String? = null)

@Serializable
data class DiagnoseWhitelist(val status: String = "unknown")

@Serializable
data class DiagnoseService(
    val slug: String,
    val name: String,
    /** operational, degraded, outage, restricted or unknown */
    val state: String = "unknown",
    /** failing, working or unknown on the caller's operator */
    val operator: String = "unknown",
)

@Serializable
data class DiagnoseProtocols(val preferred: List<String> = emptyList(), val avoid: List<String> = emptyList())

@Serializable
data class DiagnoseCountry(val country: String, val online: Int, val total: Int)

@Serializable
data class DiagnoseMaintenance(val nodeId: String, val startsAt: String, val endsAt: String)

enum class LocalNetwork { NONE, CAPTIVE, OK }

enum class VpnPhase { OFF, CONNECTING, CONNECTED, FAILED }

data class DiagnosisInput(
    val network: LocalNetwork,
    val cellular: Boolean,
    val vpn: VpnPhase,
    val serverCountry: String?,
    val serverNodeId: String?,
    /** The current server's protocol as telemetry names it, such as hysteria2. */
    val protocol: String?,
    /** Services checked from the device, by the monitor's slug, that did not answer. */
    val failingServices: List<String>,
    val anyServiceWorks: Boolean,
    /** Null when Levik could not be reached. */
    val report: DiagnoseReport?,
    val nowMs: Long,
)

enum class Verdict {
    NO_NETWORK,
    CAPTIVE_PORTAL,
    INTERNET_DOWN,
    WHITELISTS,
    SERVER_DOWN,
    PROTOCOL,
    VPN_UNREACHABLE,
    VPN_PATH_BROKEN,
    SERVICE_DOWN_EVERYWHERE,
    SERVICE_RESTRICTED,
    SERVICE_OPERATOR,
    SERVICE_FAILS_DIRECT,
    SERVICE_VIA_VPN,
    ALL_OK_VPN_OFF,
    ALL_OK,
}

enum class VerdictAction { RETRY, NETWORK_SETTINGS, WIFI_SETTINGS, CONNECT, BEST_SERVER, MOBILE_SERVERS, SUPPORT, NONE }

data class DiagnosisVerdict(
    val verdict: Verdict,
    val action: VerdictAction,
    /** The service the sentence names. */
    val service: String? = null,
    val provider: String? = null,
    val country: String? = null,
    val protocol: String? = null,
)

object DiagnosisPolicy {
    fun verdict(input: DiagnosisInput): DiagnosisVerdict {
        when (input.network) {
            LocalNetwork.NONE -> return DiagnosisVerdict(Verdict.NO_NETWORK, VerdictAction.NETWORK_SETTINGS)
            LocalNetwork.CAPTIVE -> return DiagnosisVerdict(Verdict.CAPTIVE_PORTAL, VerdictAction.WIFI_SETTINGS)
            LocalNetwork.OK -> Unit
        }
        val report = input.report
        if (report == null && !input.anyServiceWorks) {
            return if (input.vpn == VpnPhase.CONNECTED) {
                DiagnosisVerdict(Verdict.VPN_PATH_BROKEN, VerdictAction.BEST_SERVER)
            } else {
                DiagnosisVerdict(Verdict.INTERNET_DOWN, VerdictAction.RETRY)
            }
        }
        val provider = report?.network?.provider?.takeIf { report.network.known }
        val vpnFailing = input.vpn == VpnPhase.CONNECTING || input.vpn == VpnPhase.FAILED ||
            (input.vpn == VpnPhase.CONNECTED && !input.anyServiceWorks)
        if (vpnFailing) {
            if (report?.whitelist?.status == "active" && input.cellular) {
                return DiagnosisVerdict(Verdict.WHITELISTS, VerdictAction.MOBILE_SERVERS, provider = provider)
            }
            if (report != null && serverDown(report, input)) {
                return DiagnosisVerdict(Verdict.SERVER_DOWN, VerdictAction.BEST_SERVER, country = input.serverCountry)
            }
            betterProtocol(report?.protocols, input.protocol)?.let { better ->
                return DiagnosisVerdict(Verdict.PROTOCOL, VerdictAction.BEST_SERVER, provider = provider, protocol = better)
            }
            return if (input.vpn == VpnPhase.CONNECTED) {
                DiagnosisVerdict(Verdict.VPN_PATH_BROKEN, VerdictAction.BEST_SERVER)
            } else {
                DiagnosisVerdict(Verdict.VPN_UNREACHABLE, VerdictAction.BEST_SERVER, country = input.serverCountry)
            }
        }
        val failing = input.failingServices.map { slug ->
            report?.services?.firstOrNull { it.slug == slug } ?: DiagnoseService(slug = slug, name = slug)
        }
        failing.firstOrNull { it.state == "outage" }?.let { service ->
            return DiagnosisVerdict(Verdict.SERVICE_DOWN_EVERYWHERE, VerdictAction.NONE, service = service.name)
        }
        if (input.vpn == VpnPhase.OFF) {
            failing.firstOrNull { it.state == "restricted" }?.let { service ->
                return DiagnosisVerdict(Verdict.SERVICE_RESTRICTED, VerdictAction.CONNECT, service = service.name)
            }
            failing.firstOrNull { it.operator == "failing" && provider != null }?.let { service ->
                return DiagnosisVerdict(Verdict.SERVICE_OPERATOR, VerdictAction.CONNECT, service = service.name, provider = provider)
            }
            failing.firstOrNull()?.let { service ->
                return DiagnosisVerdict(Verdict.SERVICE_FAILS_DIRECT, VerdictAction.CONNECT, service = service.name)
            }
            return DiagnosisVerdict(Verdict.ALL_OK_VPN_OFF, VerdictAction.CONNECT)
        }
        failing.firstOrNull()?.let { service ->
            return DiagnosisVerdict(Verdict.SERVICE_VIA_VPN, VerdictAction.BEST_SERVER, service = service.name)
        }
        return DiagnosisVerdict(Verdict.ALL_OK, VerdictAction.SUPPORT)
    }

    private fun serverDown(report: DiagnoseReport, input: DiagnosisInput): Boolean {
        val nodeId = input.serverNodeId
        if (nodeId != null && report.maintenance.any { it.nodeId == nodeId && active(it, input.nowMs) }) return true
        val country = report.countries.firstOrNull { it.country == input.serverCountry } ?: return false
        return country.total > 0 && country.online == 0
    }

    private fun active(window: DiagnoseMaintenance, nowMs: Long): Boolean {
        val starts = runCatching { java.time.Instant.parse(window.startsAt).toEpochMilli() }.getOrNull() ?: return false
        val ends = runCatching { java.time.Instant.parse(window.endsAt).toEpochMilli() }.getOrNull() ?: return false
        return nowMs in starts until ends
    }

    /** A protocol that works better on this operator than the current one, if the server knows. */
    private fun betterProtocol(advice: DiagnoseProtocols?, current: String?): String? {
        if (advice == null || current == null) return null
        val best = advice.preferred.firstOrNull { it != current } ?: return null
        return best.takeIf { current in advice.avoid || current !in advice.preferred }
    }

    /** A readable name for a protocol the server advises. */
    fun protocolName(wire: String): String = when (wire) {
        "vless-reality" -> "VLESS Reality"
        "vless-xhttp" -> "XHTTP"
        "vless-ws" -> "WebSocket"
        "vless-grpc" -> "gRPC"
        "vless-tcp" -> "VLESS"
        "hysteria2" -> "Hysteria 2"
        "tuic" -> "TUIC"
        "trojan" -> "Trojan"
        "shadowsocks" -> "Shadowsocks"
        else -> wire
    }
}
