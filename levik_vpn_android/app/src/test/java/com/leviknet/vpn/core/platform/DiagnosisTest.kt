package com.leviknet.vpn.core.platform

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosisTest {
    private val now = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()
    private val healthy = DiagnoseReport(
        network = DiagnoseNetwork(known = true, provider = "MTS", country = "RU"),
        services = listOf(
            DiagnoseService("youtube", "YouTube", state = "restricted", operator = "failing"),
            DiagnoseService("discord", "Discord", state = "outage"),
            DiagnoseService("telegram", "Telegram", state = "operational", operator = "failing"),
        ),
        countries = listOf(DiagnoseCountry("FI", online = 0, total = 2), DiagnoseCountry("DE", online = 3, total = 3)),
    )

    private fun input(
        network: LocalNetwork = LocalNetwork.OK,
        vpn: VpnPhase = VpnPhase.OFF,
        failing: List<String> = emptyList(),
        works: Boolean = true,
        report: DiagnoseReport? = healthy,
        country: String? = "DE",
        protocol: String? = "vless-reality",
        cellular: Boolean = false,
        nodeId: String? = "n1aaaaaaaaaaaaaaaaaaaa",
    ) = DiagnosisInput(network, cellular, vpn, country, nodeId, protocol, failing, works, report, now)

    private fun verdictOf(input: DiagnosisInput) = DiagnosisPolicy.verdict(input).verdict

    @Test
    fun `the device's own network comes first`() {
        assertEquals(Verdict.NO_NETWORK, verdictOf(input(network = LocalNetwork.NONE, vpn = VpnPhase.CONNECTED)))
        assertEquals(VerdictAction.WIFI_SETTINGS, DiagnosisPolicy.verdict(input(network = LocalNetwork.CAPTIVE)).action)
    }

    @Test
    fun `nothing answers - the internet without a VPN, the server with one`() {
        assertEquals(Verdict.INTERNET_DOWN, verdictOf(input(works = false, report = null)))
        assertEquals(Verdict.VPN_PATH_BROKEN, verdictOf(input(vpn = VpnPhase.CONNECTED, works = false, report = null)))
    }

    @Test
    fun `a failing connection names whitelists, a server down or a better protocol`() {
        val whitelists = healthy.copy(whitelist = DiagnoseWhitelist("active"))
        assertEquals(Verdict.WHITELISTS, verdictOf(input(vpn = VpnPhase.FAILED, report = whitelists, cellular = true)))
        // Whitelists are for mobile networks: on Wi-Fi something else is wrong.
        assertEquals(Verdict.VPN_UNREACHABLE, verdictOf(input(vpn = VpnPhase.FAILED, report = whitelists, cellular = false)))
        assertEquals(Verdict.SERVER_DOWN, verdictOf(input(vpn = VpnPhase.CONNECTING, country = "FI")))
        val maintenance = healthy.copy(
            maintenance = listOf(DiagnoseMaintenance("n1aaaaaaaaaaaaaaaaaaaa", "2026-10-15T11:00:00Z", "2026-10-15T13:00:00Z")),
        )
        assertEquals(Verdict.SERVER_DOWN, verdictOf(input(vpn = VpnPhase.FAILED, report = maintenance)))
        val advice = healthy.copy(protocols = DiagnoseProtocols(preferred = listOf("hysteria2"), avoid = listOf("vless-reality")))
        val protocol = DiagnosisPolicy.verdict(input(vpn = VpnPhase.FAILED, report = advice))
        assertEquals(Verdict.PROTOCOL, protocol.verdict)
        assertEquals("hysteria2", protocol.protocol)
        assertEquals("MTS", protocol.provider)
        // Already on the preferred protocol: no protocol advice.
        assertEquals(Verdict.VPN_UNREACHABLE, verdictOf(input(vpn = VpnPhase.FAILED, report = advice, protocol = "hysteria2")))
    }

    @Test
    fun `a service down everywhere is not the VPN`() {
        val verdict = DiagnosisPolicy.verdict(input(vpn = VpnPhase.CONNECTED, failing = listOf("discord")))
        assertEquals(Verdict.SERVICE_DOWN_EVERYWHERE, verdict.verdict)
        assertEquals("Discord", verdict.service)
        assertEquals(VerdictAction.NONE, verdict.action)
    }

    @Test
    fun `without the VPN a blocked service asks to connect`() {
        assertEquals(Verdict.SERVICE_RESTRICTED, verdictOf(input(failing = listOf("youtube"))))
        val operator = DiagnosisPolicy.verdict(input(failing = listOf("telegram")))
        assertEquals(Verdict.SERVICE_OPERATOR, operator.verdict)
        assertEquals("MTS", operator.provider)
        assertEquals(VerdictAction.CONNECT, operator.action)
        assertEquals(Verdict.SERVICE_FAILS_DIRECT, verdictOf(input(failing = listOf("chatgpt"))))
        assertEquals(Verdict.ALL_OK_VPN_OFF, verdictOf(input()))
    }

    @Test
    fun `through the VPN a failing service asks for another server`() {
        assertEquals(Verdict.SERVICE_VIA_VPN, verdictOf(input(vpn = VpnPhase.CONNECTED, failing = listOf("telegram"))))
        assertEquals(Verdict.ALL_OK, verdictOf(input(vpn = VpnPhase.CONNECTED)))
        // Levik unreachable while sites work: decided from the device alone.
        assertEquals(Verdict.ALL_OK, verdictOf(input(vpn = VpnPhase.CONNECTED, report = null)))
    }
}
