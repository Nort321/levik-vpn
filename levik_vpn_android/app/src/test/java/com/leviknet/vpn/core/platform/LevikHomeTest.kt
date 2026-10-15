package com.leviknet.vpn.core.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevikHomeTest {
    private val homeId = "AbCdEfGhIjKlMnOpQrStUv"
    private val proof = "uo4VQl8xhhyJqBeyUxfjkw"

    private fun txt(vararg pairs: Pair<String, String>): Map<String, ByteArray?> =
        pairs.associate { (key, value) -> key to value.encodeToByteArray() }

    @Test
    fun `reads a v1 beacon and ignores anything else`() {
        assertEquals(
            HomeBeacon(homeId, 5_870_000, proof),
            LevikHome.parseBeacon(txt("v" to "1", "h" to homeId, "w" to "5870000", "p" to proof)),
        )
        assertNull(LevikHome.parseBeacon(txt("v" to "2", "h" to homeId, "w" to "5870000", "p" to proof)))
        assertNull(LevikHome.parseBeacon(txt("v" to "1", "h" to "short", "w" to "5870000", "p" to proof)))
        assertNull(LevikHome.parseBeacon(txt("v" to "1", "h" to homeId, "w" to "-1", "p" to proof)))
        assertNull(LevikHome.parseBeacon(txt("v" to "1", "h" to homeId, "w" to "0", "p" to proof)))
        assertNull(LevikHome.parseBeacon(txt("v" to "1", "h" to homeId, "w" to "5870000", "p" to "$proof=")))
        assertNull(LevikHome.parseBeacon(mapOf("v" to "1".encodeToByteArray(), "h" to null)))
    }

    @Test
    fun `only beacons of the current window and its neighbours are fresh`() {
        val now = 5_870_000L * 300_000L + 1_000L
        assertTrue(LevikHome.fresh(HomeBeacon(homeId, 5_870_000, proof), now))
        assertTrue(LevikHome.fresh(HomeBeacon(homeId, 5_869_999, proof), now))
        assertTrue(LevikHome.fresh(HomeBeacon(homeId, 5_870_001, proof), now))
        assertFalse(LevikHome.fresh(HomeBeacon(homeId, 5_869_998, proof), now))
    }

    @Test
    fun `reads the server's answer`() {
        fun parse(text: String) = LevikHome.parseSighting(Json.parseToJsonElement(text).jsonObject)
        assertEquals(
            VerifiedHome("Дом", routerOnline = true, routerVpnActive = true),
            parse("""{"ok":true,"home":{"name":"Дом","routerOnline":true,"routerVpnActive":true}}"""),
        )
        assertNull(parse("""{"ok":true,"home":null}"""))
        assertNull(parse("""{"ok":true,"home":{"name":"","routerOnline":true,"routerVpnActive":true}}"""))
        assertNull(parse("""{"ok":true,"home":{"name":"Дом","routerOnline":"yes","routerVpnActive":true}}"""))
        assertNull(LevikHome.parseSighting(JsonObject(emptyMap())))
        assertFalse(VerifiedHome("Дом", routerOnline = false, routerVpnActive = true).protects)
    }
}

class HomeSleepPolicyTest {
    private val protecting = VerifiedHome("Дом", routerOnline = true, routerVpnActive = true)
    private val unprotected = VerifiedHome("Дом", routerOnline = true, routerVpnActive = false)
    private val silence = HomeSleepPolicy.SILENCE_MS

    @Test
    fun `sleeps at a protected home and wakes when the phone leaves`() {
        val policy = HomeSleepPolicy()
        assertEquals(HomeSleepPolicy.Action.Sleep("Дом"), policy.heard(protecting, 0, vpnConnected = true, enabled = true))
        assertTrue(policy.asleep)
        assertEquals(HomeSleepPolicy.Action.None, policy.heard(protecting, 60_000, vpnConnected = false, enabled = true))
        assertEquals(HomeSleepPolicy.Action.Wake, policy.left())
        assertFalse(policy.asleep)
    }

    @Test
    fun `does not sleep while the router's VPN is off, the setting is off or the VPN is not on`() {
        val policy = HomeSleepPolicy()
        assertEquals(HomeSleepPolicy.Action.None, policy.heard(unprotected, 0, vpnConnected = true, enabled = true))
        assertEquals(HomeSleepPolicy.Action.None, policy.heard(protecting, 0, vpnConnected = true, enabled = false))
        assertEquals(HomeSleepPolicy.Action.None, policy.heard(protecting, 0, vpnConnected = false, enabled = true))
        assertFalse(policy.asleep)
    }

    @Test
    fun `wakes when the router's VPN stops or the setting is turned off`() {
        val policy = HomeSleepPolicy()
        policy.heard(protecting, 0, vpnConnected = true, enabled = true)
        assertEquals(HomeSleepPolicy.Action.Wake, policy.heard(unprotected, 1_000, vpnConnected = false, enabled = true))

        policy.heard(protecting, 2_000, vpnConnected = true, enabled = true)
        assertEquals(HomeSleepPolicy.Action.Wake, policy.heard(protecting, 3_000, vpnConnected = false, enabled = false))
    }

    @Test
    fun `a silent home wakes the VPN only after three windows`() {
        val policy = HomeSleepPolicy()
        policy.heard(protecting, 0, vpnConnected = true, enabled = true)
        assertEquals(HomeSleepPolicy.Action.None, policy.heard(null, silence, vpnConnected = false, enabled = true))
        assertTrue(policy.asleep)
        assertEquals(HomeSleepPolicy.Action.Wake, policy.silence(silence + 1))
    }

    @Test
    fun `a user who turns the VPN on at home keeps it until the next arrival`() {
        val policy = HomeSleepPolicy()
        policy.heard(protecting, 0, vpnConnected = true, enabled = true)
        policy.resumedByUser()
        assertEquals(HomeSleepPolicy.Action.None, policy.heard(protecting, 60_000, vpnConnected = true, enabled = true))
        policy.left()
        assertEquals(HomeSleepPolicy.Action.Sleep("Дом"), policy.heard(protecting, 120_000, vpnConnected = true, enabled = true))
    }

    @Test
    fun `turning the VPN off forgets the sleep without waking it`() {
        val policy = HomeSleepPolicy()
        policy.heard(protecting, 0, vpnConnected = true, enabled = true)
        policy.stopped()
        assertEquals(HomeSleepPolicy.Action.None, policy.left())
    }
}
