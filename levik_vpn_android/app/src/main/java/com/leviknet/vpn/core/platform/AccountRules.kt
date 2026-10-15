package com.leviknet.vpn.core.platform

import java.net.IDN
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * "My rules everywhere", docs/app-platform.md "Account rules": one change the
 * account's rules accept. The server checks the site again and folds the
 * rules into every tunnel profile.
 */
sealed class RuleChange {
    data class SetCategory(val category: String, val action: RuleAction?) : RuleChange()
    data class SetDomain(val domain: String, val action: RuleAction) : RuleChange()
    data class RemoveDomain(val domain: String) : RuleChange()

    /** The server needs "action": null to reset a category, which the shared Json leaves out. */
    fun body(): String = JsonObject(
        when (this) {
            is SetCategory -> mapOf(
                "op" to JsonPrimitive("set_category"),
                "category" to JsonPrimitive(category),
                "action" to (action?.let { JsonPrimitive(it.wire) } ?: JsonNull),
            )
            is SetDomain -> mapOf(
                "op" to JsonPrimitive("set_domain"),
                "domain" to JsonPrimitive(domain),
                "action" to JsonPrimitive(action.wire),
            )
            is RemoveDomain -> mapOf(
                "op" to JsonPrimitive("remove_domain"),
                "domain" to JsonPrimitive(domain),
            )
        },
    ).toString()
}

enum class RuleAction(val wire: String) {
    DIRECT("direct"),
    PROXY("proxy"),
    ;

    companion object {
        fun of(wire: String?): RuleAction? = entries.firstOrNull { it.wire == wire }
    }
}

/** A site shared to the app from a browser or a messenger. */
object SharedSite {
    private val LINK = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val BARE_HOST = Regex("""(?:^|\s)((?:[\p{L}\p{N}](?:[\p{L}\p{N}-]{0,61}[\p{L}\p{N}])?\.)+[\p{L}][\p{L}\p{N}-]{0,61}[\p{L}\p{N}])(?:[/\s]|$)""")
    private val HOSTNAME =
        Regex("""^(?=.{4,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$""")
    private const val MAX_TEXT = 4_000

    /** The first link's site, without "www.", as the account's rules store it; null when there is none. */
    fun from(text: String?): String? {
        val value = text?.take(MAX_TEXT)?.trim().orEmpty()
        if (value.isEmpty()) return null
        val host = LINK.find(value)?.value?.let(::hostOf)
            ?: BARE_HOST.find(value)?.groupValues?.get(1)
            ?: return null
        return normalize(host)
    }

    /** java.net.URI gives no host for a non-ASCII one, such as президент.рф. */
    private fun hostOf(link: String): String? {
        val authority = link.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@')
        if (host.startsWith("[")) return null
        return host.substringBefore(':').ifEmpty { null }
    }

    fun normalize(host: String): String? {
        val ascii = runCatching { IDN.toASCII(host.trim().trimEnd('.'), IDN.ALLOW_UNASSIGNED) }.getOrNull()
            ?.lowercase()
            ?.removePrefix("www.")
            ?: return null
        return ascii.takeIf(HOSTNAME::matches)
    }
}
