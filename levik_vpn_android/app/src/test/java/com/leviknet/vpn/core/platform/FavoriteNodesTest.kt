package com.leviknet.vpn.core.platform

import com.leviknet.vpn.vpn.TunnelServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteNodesTest {
    private val fi = "n1142bf5571df2373ffb23"
    private val de = "n15c40cf1f1842e4bd352a"
    private val byNode = mapOf(fi to listOf("fi-vless", "fi-hy2"), de to listOf("de-vless"))

    @Test
    fun `node ids match the site and the desktop apps`() {
        assertEquals(fi, FavoriteNodes.nodeId("fi1.example.net"))
        assertEquals(fi, FavoriteNodes.nodeId(" FI1.Example.NET. "))
        assertEquals(de, FavoriteNodes.nodeId("203.0.113.7"))
        assertEquals("n121f47268350d79178ced", FavoriteNodes.nodeId("[2001:db8::1]"))
        assertNull(FavoriteNodes.nodeId(""))
    }

    @Test
    fun `lists are sorted, distinct and bounded`() {
        assertEquals("$fi,$de", FavoriteNodes.canonical(listOf(de, fi, de, "bad")))
        assertTrue(FavoriteNodes.isCanonical(""))
        assertFalse(FavoriteNodes.isCanonical("$de,$fi"))
        assertEquals(FavoriteNodes.MAX, FavoriteNodes.parse(FavoriteNodes.canonical((0 until 50).map { "n1%020x".format(it) })).size)
    }

    @Test
    fun `starring here adds the node and unstarring removes it`() {
        val start = FavoriteNodes.State(emptySet(), emptySet())
        val starred = FavoriteNodes.reconcile(start, setOf("fi-hy2"), byNode)
        assertEquals(setOf(fi), starred.state.nodes)
        assertEquals(setOf("fi-hy2"), starred.local)
        val unstarred = FavoriteNodes.reconcile(starred.state, emptySet(), byNode)
        assertTrue(unstarred.state.nodes.isEmpty())
    }

    @Test
    fun `the account list is applied to servers this phone has and others are kept`() {
        val unknown = "n1000000000000000000ff"
        val result = FavoriteNodes.reconcile(
            FavoriteNodes.State(setOf(de, unknown), emptySet()),
            setOf("yandex"),
            byNode,
        )
        assertEquals(setOf("yandex", "de-vless"), result.local)
        assertEquals(setOf(de, unknown), result.state.nodes)
        val removed = FavoriteNodes.reconcile(FavoriteNodes.State(setOf(unknown), result.local), result.local, byNode)
        assertEquals(setOf("yandex"), removed.local)
    }

    @Test
    fun `favourites made before sharing join the account list`() {
        val result = FavoriteNodes.reconcile(FavoriteNodes.State(setOf(de), emptySet()), setOf("fi-vless"), byNode)
        assertEquals(setOf(fi, de), result.state.nodes)
        assertEquals(setOf("fi-vless", "de-vless"), result.local)
    }

    @Test
    fun `one server behind a shared relay is found by its port`() {
        val relayFr = FavoriteNodes.endpointId("2.27.42.184", 21443)
        assertEquals("n15f491927d4394acd315d", relayFr)
        val relay = "n126c4033f0b74bc1be26a"
        assertEquals(relay, FavoriteNodes.nodeId("2.27.42.184"))
        val vless = TunnelServer(
            id = "fr", tag = "fr", name = "fr", countryCode = "FR",
            outbound = Json.parseToJsonElement(
                """{"protocol":"vless","settings":{"vnext":[{"address":"2.27.42.184","port":21443}]}}""",
            ).jsonObject,
        )
        val flat = vless.copy(
            outbound = Json.parseToJsonElement(
                """{"protocol":"hysteria","settings":{"address":"2.27.42.184","port":21443}}""",
            ).jsonObject,
        )
        val load = mapOf(relayFr!! to 0.1, relay to 0.28)
        assertEquals(0.1, FavoriteNodes.load(load, vless)!!, 0.0)
        assertEquals(0.1, FavoriteNodes.load(load, flat)!!, 0.0)
        // Without a port entry the shared host's load still applies.
        assertEquals(0.28, FavoriteNodes.load(mapOf(relay to 0.28), vless)!!, 0.0)
        assertNull(FavoriteNodes.load(emptyMap(), vless))
        assertNull(FavoriteNodes.endpointId("2.27.42.184", 0))
    }
}
