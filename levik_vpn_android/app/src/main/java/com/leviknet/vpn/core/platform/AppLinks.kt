package com.leviknet.vpn.core.platform

import java.net.URI

/** Screens the website's "return to the app" page can ask for. */
enum class OpenAppTarget(val wire: String) {
    HOME("home"),
    SUBSCRIPTIONS("subscriptions"),
    PLANS("plans"),
    SUPPORT("support"),
}

/** Pages of the website's personal cabinet the app can open signed in. */
enum class CabinetTarget(val path: String) {
    DASHBOARD("/dashboard"),
    SUBSCRIPTIONS("/dashboard/subscriptions"),
    PLANS("/dashboard/plans"),
    ORDERS("/dashboard/orders"),
    DEVICES("/dashboard/devices"),
    SUPPORT("/dashboard/support"),
    ACCOUNT_SECURITY("/dashboard/account-security"),
}

object AppLinks {
    private val SITE_HOSTS = setOf("leviknet.org", "leviknet.com")
    private const val OPEN_APP_PATH = "/open-app"
    private val HANDOFF_QUERY = Regex("^token=[A-Za-z0-9_-]{43}$")
    private const val MAX_URI_LENGTH = 300

    /**
     * https://leviknet.org/open-app?to=<screen> opens the app through an App
     * Link. The link can only choose a screen; anything else opens the main one.
     */
    fun openAppTarget(rawUri: String): OpenAppTarget? {
        val uri = siteUri(rawUri) ?: return null
        if (uri.rawPath != OPEN_APP_PATH) return null
        val target = uri.rawQuery
            ?.split('&')
            ?.firstNotNullOfOrNull { part -> part.removePrefix("to=").takeIf { part.startsWith("to=") } }
        return OpenAppTarget.entries.firstOrNull { it.wire == target } ?: OpenAppTarget.HOME
    }

    /** The one-time sign-in link must point at the Levik website's /handoff page. */
    fun isHandoffUrl(rawUri: String): Boolean {
        val uri = siteUri(rawUri) ?: return false
        return uri.rawPath == "/handoff" && uri.rawQuery?.let(HANDOFF_QUERY::matches) == true
    }

    /** Where a page opens when the app cannot sign the browser in. */
    fun cabinetFallbackUrl(target: CabinetTarget): String = "https://leviknet.org${target.path}"

    private fun siteUri(rawUri: String): URI? {
        if (rawUri.length > MAX_URI_LENGTH) return null
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (SITE_HOSTS.none { uri.host.equals(it, ignoreCase = true) }) return null
        if (uri.port != -1 || uri.rawUserInfo != null || uri.rawFragment != null) return null
        return uri
    }
}
