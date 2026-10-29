package com.leviknet.vpn.vpn

import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotSwitchProbeTest {
    @Test fun `data proof precedes the configured DNS proof on the exact private candidate`() = runTest {
        val folder = Files.createTempDirectory(Paths.get("/tmp"), "hs-proof-").toFile()
        val source = TunnelServer("a", "a", "A", "DE", Json.parseToJsonElement("""{"tag":"a","protocol":"vless","settings":{"address":"192.0.2.1","port":443}}""").jsonObject)
        try { SmartAutoLiveConfig(listOf(source), "a", folder, null).use { config ->
            config.apply(Json.parseToJsonElement("""{"dns":{"servers":["https://resolver.example/dns-query","9.9.9.9",{"address":"localhost","domains":["full:bootstrap.example"]}]},"inbounds":[{"tag":"levik-tun-in","protocol":"tun"}],"outbounds":[{"tag":"a","protocol":"vless"}],"routing":{"rules":[]}}""").jsonObject)
            val events = mutableListOf<String>()
            assertTrue(probeHotSwitchMain(config, "a", dataProbe = { events += "data:${it.id}"; true }, dnsProbe = { path, resolvers ->
                assertEquals(config.initial.path, path)
                assertEquals(listOf("https://resolver.example/dns-query", "9.9.9.9"), resolvers)
                events += "dns"; true
            }))
            assertEquals(listOf("data:a", "dns"), events)
            var dnsCalls = 0
            assertFalse(probeHotSwitchMain(config, "a", dataProbe = { false }, dnsProbe = { _, _ -> dnsCalls++; true }))
            assertEquals(0, dnsCalls)
            assertFalse(probeHotSwitchMain(config, "unknown", dataProbe = { error("Unknown candidate probed") }, dnsProbe = { _, _ -> error("Unknown candidate DNS") }))
        } } finally { folder.delete() }
    }

    @Test fun `DNS reply requires matching transaction flags question and a complete address answer`() {
        val id = 1234
        val question = HotSwitchDnsWire.question(id)
        val answer = question.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[7] = 1 } +
            byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 2, 3, 4)
        assertTrue(HotSwitchDnsWire.answer(answer, id))
        assertFalse(HotSwitchDnsWire.answer(answer, id + 1))
        assertFalse(HotSwitchDnsWire.answer(answer.copyOf(answer.size - 1), id))
        assertFalse(HotSwitchDnsWire.answer(answer.copyOf().apply { this[3] = 0x83.toByte() }, id))
        assertFalse(HotSwitchDnsWire.answer(answer.copyOf().apply { this[2] = 0x83.toByte() }, id))
        assertFalse(HotSwitchDnsWire.answer(answer.copyOf().apply { this[7] = 0 }, id))
        assertFalse(HotSwitchDnsWire.answer(answer.copyOf().apply { this[13] = 120 }, id))
        assertFalse(HotSwitchDnsWire.answer(answer.copyOf().apply { this[question.size + 1] = 0xff.toByte() }, id))
    }

    @Test fun `missing configured resolvers unsupported providers and cancelled work never fall back publicly`() {
        assertFalse(probeHotSwitchDns("/unused", emptyList()))
        assertFalse(probeHotSwitchDns("/unused", listOf("file:///tmp/untrusted")))
        assertFalse(probeHotSwitchDns("/unused", listOf("https://resolver.example/dns-query")) { false })
    }
}
