package com.leviknet.vpn.core.platform

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs

/**
 * What a Levik Home router announces on its network (mDNS `_levik._tcp`),
 * docs/app-platform.md "Levik Home": the home, a five-minute window and a
 * proof only the account's server can check.
 */
data class HomeBeacon(val homeId: String, val window: Long, val proof: String)

/** The server recognised the beacon as the account's home. */
data class VerifiedHome(val name: String, val routerOnline: Boolean, val routerVpnActive: Boolean) {
    /** The router's VPN protects this network, so the phone's own may sleep. */
    val protects: Boolean get() = routerOnline && routerVpnActive
}

object LevikHome {
    /** The service type NsdManager discovers; the router answers as `_levik._tcp.local`. */
    const val SERVICE_TYPE = "_levik._tcp"
    const val WINDOW_SECONDS = 300L

    private val TOKEN = Regex("^[A-Za-z0-9_-]{22}$")
    private val WINDOW = Regex("^[0-9]{1,12}$")
    private const val MAX_NAME = 60

    /** TXT attributes of a resolved service; anything that is not a v1 beacon is ignored. */
    fun parseBeacon(attributes: Map<String, ByteArray?>): HomeBeacon? {
        fun text(key: String): String? = attributes[key]?.decodeToString()
        if (text("v") != "1") return null
        val homeId = text("h")?.takeIf(TOKEN::matches) ?: return null
        val window = text("w")?.takeIf(WINDOW::matches)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val proof = text("p")?.takeIf(TOKEN::matches) ?: return null
        return HomeBeacon(homeId, window, proof)
    }

    fun window(nowMs: Long): Long = nowMs / 1000L / WINDOW_SECONDS

    /** The server accepts the current window and its neighbours; an older beacon is a cached echo. */
    fun fresh(beacon: HomeBeacon, nowMs: Long): Boolean = abs(beacon.window - window(nowMs)) <= 1

    /** The server's answer to a sighting; null when it is not the account's home. */
    fun parseSighting(response: JsonObject): VerifiedHome? {
        val home = response["home"] as? JsonObject ?: return null
        val name = (home["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME } ?: return null
        val online = (home["routerOnline"] as? JsonPrimitive)?.booleanOrNull ?: return null
        val vpn = (home["routerVpnActive"] as? JsonPrimitive)?.booleanOrNull ?: return null
        return VerifiedHome(name, online, vpn)
    }
}

/**
 * When the VPN sleeps at home. It falls asleep when the account's home is
 * heard while the router's VPN protects it, and wakes as soon as the phone
 * leaves the network, the router's VPN stops or the home goes silent. A user
 * who turns the VPN back on at home keeps it until the next arrival.
 */
class HomeSleepPolicy(private val silenceMs: Long = SILENCE_MS) {
    sealed interface Action {
        data object None : Action
        data class Sleep(val home: String) : Action
        data object Wake : Action
    }

    var asleep: Boolean = false
        private set
    private var declined = false
    private var heardAtMs: Long? = null

    /** A scan result: the protecting home, or null when none answered on this network. */
    fun heard(home: VerifiedHome?, nowMs: Long, vpnConnected: Boolean, enabled: Boolean): Action {
        if (home == null || !home.protects) {
            if (home != null) heardAtMs = null
            return if (home != null) wake() else silence(nowMs)
        }
        heardAtMs = nowMs
        if (!enabled) return wake()
        if (asleep || declined || !vpnConnected) return Action.None
        asleep = true
        return Action.Sleep(home.name)
    }

    /** No answer lately: after a while the phone is no longer home. */
    fun silence(nowMs: Long): Action {
        val heard = heardAtMs ?: return if (asleep) wake() else Action.None
        if (nowMs - heard <= silenceMs) return Action.None
        heardAtMs = null
        declined = false
        return wake()
    }

    /** The phone left the network it fell asleep on. */
    fun left(): Action {
        heardAtMs = null
        declined = false
        return wake()
    }

    /** The user turned the VPN back on at home: not again until the next arrival. */
    fun resumedByUser() {
        if (!asleep) return
        asleep = false
        declined = true
    }

    /** The VPN was turned off altogether; there is nothing to wake. */
    fun stopped() {
        asleep = false
    }

    fun disabled(): Action = wake()

    private fun wake(): Action {
        if (!asleep) return Action.None
        asleep = false
        return Action.Wake
    }

    companion object {
        /** Three windows without the home's answer. */
        const val SILENCE_MS = 3 * LevikHome.WINDOW_SECONDS * 1000L
    }
}
