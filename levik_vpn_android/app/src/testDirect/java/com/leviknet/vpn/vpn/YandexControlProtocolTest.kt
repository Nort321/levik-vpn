package com.leviknet.vpn.vpn

import java.io.FileDescriptor
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexControlProtocolTest {
    private val codec = YandexControlCodec()
    private val envelope = "\"magic\":\"LEVIK_YANDEX_ANDROID\",\"version\":2"

    @Test fun `status diagnostics stay optional and reject negative or private fields`() {
        val common = "\"state\":\"running\",\"strictSession\":true,\"bytesUp\":1,\"bytesDown\":2,\"protectedExternalSockets\":1,\"resolvedProviderHosts\":1"
        assertEquals(YandexDataPathWire(), codec.status(event("\"type\":\"stats\",\"data\":{$common}")).dataPath)
        assertEquals(YandexProxyDiagnosticsWire(), codec.status(event("\"type\":\"stats\",\"data\":{$common}")).proxy)
        assertEquals(7L, codec.status(event("\"type\":\"stats\",\"data\":{$common,\"proxy\":{\"connections\":7}}")).proxy.connections)
        for (field in listOf("connections", "rejected", "udpAssociations")) {
            assertThrows(YandexProtocolException::class.java) { event("\"type\":\"stats\",\"data\":{$common,\"proxy\":{\"$field\":-1}}") }
        }
        assertThrows(YandexProtocolException::class.java) { event("\"type\":\"stats\",\"data\":{$common,\"proxy\":{\"token\":\"private\"}}") }
        val extended = event("\"type\":\"status\",\"data\":{$common,\"carrierPacketsUp\":10,\"carrierPacketsDown\":12,\"dataPath\":{\"dataSent\":20,\"dataReceived\":25}}")
        assertEquals(25L, codec.status(extended).dataPath.dataReceived)
        for (field in listOf("dataSent", "dataReceived", "dataDropped", "decodeErrors", "cryptoRejected", "batchSendErrors")) {
            assertThrows(YandexProtocolException::class.java) { event("\"type\":\"stats\",\"data\":{$common,\"dataPath\":{\"$field\":-1}}") }
        }
        assertThrows(YandexProtocolException::class.java) { event("\"type\":\"stats\",\"data\":{$common,\"dataPath\":{\"token\":\"private\"}}") }
    }

    @Test fun `prepare sequence requires authenticated proxy plan before running`() {
        val state = YandexControlStateMachine(codec)
        assertEquals(YandexControlAction.Continue, state.accept(event("\"type\":\"ready\",\"phase\":\"control\"")))
        assertEquals(YandexControlAction.ConnectNetwork, state.accept(event("\"type\":\"ready\",\"phase\":\"PROTECT_CHANNEL_LISTENING\"")))
        assertEquals(YandexControlAction.Continue, state.accept(event("\"type\":\"ready\",\"phase\":\"PROTECT_CHANNEL_READY\"")))
        assertEquals(YandexControlAction.PreparedProxy(32000), state.accept(event("\"type\":\"proxy_plan\",\"phase\":\"PREPARED\",\"data\":{\"address\":\"127.0.0.1\",\"port\":32000}")))
        assertFalse(state.running)
        assertEquals(YandexControlAction.Running, state.accept(event("\"type\":\"ready\",\"phase\":\"RUNNING\",\"data\":{\"protocolVersion\":2}")))
        assertTrue(state.running)
        assertThrows(YandexProtocolException::class.java) { state.accept(event("\"type\":\"ready\",\"phase\":\"RUNNING\",\"data\":{\"protocolVersion\":2}")) }
    }

    @Test fun `cross protocol unknown duplicate nested and malformed frames fail closed`() {
        for (payload in listOf(
            "{\"version\":2,\"type\":\"ready\",\"phase\":\"control\"}",
            "{$envelope,\"type\":\"ready\",\"phase\":\"control\",\"cookies\":{}}",
            "{$envelope,\"type\":\"ready\",\"type\":\"error\",\"phase\":\"control\"}",
            "{$envelope,\"type\":\"proxy_plan\",\"phase\":\"PREPARED\",\"data\":{\"address\":\"127.0.0.1\",\"port\":12,\"port\":13}}",
            "{$envelope,\"type\":\"ready\",\"phase\":\"control\"}{}",
            "{$envelope,\"type\":\"ready\",\"phase\":\"control\",\"data\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}",
            "a".repeat(YANDEX_MAX_CONTROL_BYTES),
        )) assertThrows(YandexProtocolException::class.java) { codec.decodeEvent(payload) }
        assertThrows(YandexProtocolException::class.java) {
            YandexControlStateMachine(codec).accept(event("\"type\":\"ready\",\"phase\":\"RUNNING\",\"data\":{\"protocolVersion\":2}"))
        }
    }

    @Test fun `network channel has separate no FD DNS and one FD protect operations`() {
        assertEquals(YandexNetworkRequest.Resolve(1, "opaque_guest.onlyoffice.disk.yandex.net"), codec.decodeNetworkRequest("{$envelope,\"type\":\"RESOLVE_HOST\",\"requestId\":1,\"host\":\"opaque_guest.onlyoffice.disk.yandex.net\"}"))
        assertEquals(YandexNetworkRequest.Protect(2), codec.decodeNetworkRequest("{$envelope,\"type\":\"PROTECT_SOCKET\",\"requestId\":2,\"network\":\"tcp4\",\"address\":\"8.8.8.8:443\"}"))
        for (fields in listOf(
            "\"type\":\"RESOLVE_HOST\",\"requestId\":1,\"host\":\"example.com\"",
            "\"type\":\"RESOLVE_HOST\",\"requestId\":1,\"host\":\"onlyoffice.disk.yandex.net.evil.test\"",
            "\"type\":\"RESOLVE_HOST\",\"requestId\":1,\"host\":\"onlyoffice.disk.yandex.net\",\"network\":\"tcp4\"",
            "\"type\":\"PROTECT_SOCKET\",\"requestId\":0,\"network\":\"tcp4\",\"address\":\"8.8.8.8:443\"",
            "\"type\":\"PROTECT_SOCKET\",\"requestId\":1,\"network\":\"tcp4\",\"address\":\"example.com:443\"",
            "\"type\":\"PROTECT_SOCKET\",\"requestId\":1,\"network\":\"tcp4\",\"address\":\"8.8.8.8:80\"",
            "\"type\":\"PROTECT_SOCKET\",\"requestId\":1,\"network\":\"tcp4\",\"address\":\"8.8.8.8:443/path\"",
        )) assertThrows(YandexProtocolException::class.java) { codec.decodeNetworkRequest("{$envelope,$fields}") }
        assertTrue(codec.encodeResolveAck(1, listOf("8.8.8.8")).contains("\"ok\":true"))
        assertTrue(codec.encodeResolveAck(1, listOf("hostname.invalid")).contains("\"ok\":false"))
        assertTrue(codec.encodeResolveAck(1, List(65) { "8.8.8.8" }).contains("\"ok\":false"))
    }

    @Test fun `socket descriptor closes exactly once before positive or negative ACK`() {
        for (success in listOf(false, true)) {
            val events = mutableListOf<String>()
            val descriptor = FileDescriptor()
            assertEquals(success, yandexProtectSocket(listOf(descriptor), { events += "protect_bind"; success }, { assertTrue(it === descriptor); events += "close" }, { assertEquals(success, it); events += "ack" }))
            assertEquals(listOf("protect_bind", "close", "ack"), events)
        }
        var closed = 0
        assertThrows(YandexProtocolException::class.java) {
            yandexProtectSocket(listOf(FileDescriptor(), FileDescriptor()), { throw AssertionError() }, { closed++ }, { throw AssertionError() })
        }
        assertEquals(2, closed)
        val descriptor = FileDescriptor()
        closed = 0
        assertFalse(yandexProtectSocket(listOf(descriptor), { throw IllegalStateException("private") }, { closed++ }, { assertFalse(it) }))
        assertEquals(1, closed)
        closed = 0
        assertThrows(IllegalStateException::class.java) {
            yandexProtectSocket(listOf(descriptor), { true }, { closed++ }, { throw IllegalStateException("ack failed") })
        }
        assertEquals(1, closed)
    }

    @Test fun `native argv and environment contain only private socket route`() {
        val process = yandexProcessBuilder("/data/app/lib/liblevikyandex.so", "@levik_ydx_control_abcdefghijklmnopqrstuvwx")
        assertEquals(listOf("/data/app/lib/liblevikyandex.so", "--control-sock", "@levik_ydx_control_abcdefghijklmnopqrstuvwx"), process.command())
        assertTrue(process.environment().isEmpty())
    }

    @Test fun `refresh ACK is bounded correlated data only and accepted only while running`() {
        val positive = event("\"type\":\"refreshed\",\"data\":{\"requestId\":7,\"ok\":true,\"leaseExpiresAt\":2000,\"validUntil\":1500}")
        assertThrows(YandexProtocolException::class.java) { YandexControlStateMachine(codec).accept(positive) }
        val state = runningState()
        assertEquals(YandexControlAction.Refreshed(YandexRefreshResult(7, true, 2000, 1500)), state.accept(positive))
        val negative = event("\"type\":\"refreshed\",\"data\":{\"requestId\":8,\"ok\":false,\"code\":\"refresh_authorization_failed\"}")
        assertEquals(YandexControlAction.Refreshed(YandexRefreshResult(8, false, code = "refresh_authorization_failed")), state.accept(negative))
        assertTrue(state.running)
        for (data in listOf(
            "\"requestId\":0,\"ok\":true,\"leaseExpiresAt\":2000,\"validUntil\":1500",
            "\"requestId\":1,\"ok\":true,\"leaseExpiresAt\":1000,\"validUntil\":1500",
            "\"requestId\":1,\"ok\":false,\"code\":\"provider raw secret\"",
            "\"requestId\":1,\"ok\":false,\"code\":\"refresh_busy\",\"validUntil\":1500",
            "\"requestId\":1,\"ok\":true,\"leaseExpiresAt\":2000,\"validUntil\":1500,\"token\":\"private\"",
        )) assertThrows(YandexProtocolException::class.java) { event("\"type\":\"refreshed\",\"data\":{$data}") }
    }

    @Test fun `hot refresh preserves document lease PSK routing and device bindings`() {
        val base64 = Base64.getUrlEncoder().withoutPadding()
        val token = base64.encodeToString("""{"alg":"HS256"}""".toByteArray()) + "." +
            base64.encodeToString("""{"document":{"key":"fixture","permissions":{"edit":true}}}""".toByteArray()) + "." + "A".repeat(43)
        val now = Instant.now().epochSecond
        val bootstrap = YandexBootstrap(1, "a".repeat(64), "https://disk.yandex.ru/i/fixture-document", "A".repeat(43), "b".repeat(64), now + 900,
            YandexProviderAuth("https://fixture.onlyoffice.disk.yandex.net", token, now + 600))
        val config = YandexServerConfig(bootstrap)
        val next = config.copy(bootstrap = bootstrap.copy(expiresAt = now + 1800, providerAuth = bootstrap.providerAuth.copy(validUntil = now + 700)))
        assertTrue(sameYandexSessionBinding(config, next))
        assertTrue(codec.encodeRefresh(1, next.bootstrap).contains("\"type\":\"REFRESH\""))
        for (changed in listOf(
            bootstrap.copy(documentUrl = "https://disk.yandex.ru/i/different-document"),
            bootstrap.copy(deviceId = "c".repeat(64)), bootstrap.copy(sharedKey = "d".repeat(64)),
            bootstrap.copy(leaseRef = "B".repeat(43)), bootstrap.copy(version = 2),
        )) assertFalse(sameYandexSessionBinding(config, config.copy(bootstrap = changed)))
        assertThrows(YandexProtocolException::class.java) { codec.encodeRefresh(0, next.bootstrap) }
        assertThrows(YandexProtocolException::class.java) { codec.encodeRefresh(1, next.bootstrap.copy(expiresAt = now + 7200)) }
    }

    private fun runningState(): YandexControlStateMachine = YandexControlStateMachine(codec).apply {
        accept(event("\"type\":\"ready\",\"phase\":\"control\""))
        accept(event("\"type\":\"ready\",\"phase\":\"PROTECT_CHANNEL_LISTENING\""))
        accept(event("\"type\":\"ready\",\"phase\":\"PROTECT_CHANNEL_READY\""))
        accept(event("\"type\":\"proxy_plan\",\"phase\":\"PREPARED\",\"data\":{\"address\":\"127.0.0.1\",\"port\":32000}"))
        accept(event("\"type\":\"ready\",\"phase\":\"RUNNING\",\"data\":{\"protocolVersion\":2}"))
    }

    private fun event(fields: String): YandexEventWire = codec.decodeEvent("{$envelope,$fields}")
}
