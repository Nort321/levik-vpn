package com.leviknet.vpn.vpn

import java.util.Locale

/** A server of a country: one node with its protocols and the name it has there. */
data class CountryServerNode(
    val group: ServerProtocolGroup,
    /** The label from the host remark; null names the server by [number]. Ungrouped entries keep their remark. */
    val label: String?,
    /** The server's place among the country's servers, from 1. */
    val number: Int,
)

/**
 * Regular servers of one country, docs/app-platform.md "Server list". Entries
 * that are never grouped get a group of their own with a null country.
 */
data class CountryServerGroup(
    val id: String,
    val countryCode: String?,
    val nodes: List<CountryServerNode>,
)

fun groupByCountry(servers: List<TunnelServer>): List<CountryServerGroup> {
    val countries = linkedMapOf<String, MutableList<ServerProtocolGroup>>()
    groupProtocolVariants(servers).forEach { group ->
        val country = group.groupedCountry()
        countries.getOrPut(country?.let { "country:$it" } ?: group.id) { mutableListOf() } += group
    }
    return countries.map { (id, groups) ->
        val country = groups.first().groupedCountry()
        CountryServerGroup(
            id = id,
            countryCode = country,
            nodes = if (country == null) {
                groups.map { CountryServerNode(it, it.variants.first().name, number = 1) }
            } else {
                nameNodes(groups, country)
            },
        )
    }
}

/** Every protocol variant of every server of a country, in list order. */
fun countryServers(servers: List<TunnelServer>, countryCode: String): List<TunnelServer> {
    val code = countryCode.uppercase(Locale.ROOT)
    return groupByCountry(servers).firstOrNull { it.countryCode == code }
        ?.nodes?.flatMap { it.group.variants }.orEmpty()
}

/** The server's own name inside its country: `🇩🇪 Германия · Франкфурт` is «Франкфурт». */
fun serverLabel(name: String, countryCode: String): String? {
    val text = name.replace(FLAGS, "").trim()
    val parts = text.split(LABEL_SEPARATOR)
    val rest = if (parts.size > 1) parts.drop(1).joinToString(" · ") else withoutCountryName(text, countryCode)
    return rest.replace(PROTOCOL_WORDS, " ")
        .replace(SPACES, " ")
        .trimLabelEdges()
        .ifEmpty { null }
}

private val LABEL_SEPARATOR = Regex("\\s+[·|]\\s+")
private val FLAGS = Regex("[\\x{1F1E6}-\\x{1F1FF}]")
private val PROTOCOL_WORDS = Regex(
    "(?<![\\p{L}\\p{N}])(?:vless|reality|hysteria\\s*2?|hy2|tuic(?:\\s*v5)?)(?![\\p{L}\\p{N}])",
    RegexOption.IGNORE_CASE,
)
private val SPACES = Regex("\\s{2,}")

/** The country a server is listed under; null keeps it on a card of its own. */
private fun ServerProtocolGroup.groupedCountry(): String? {
    val first = variants.first()
    if (first.effectiveCategory() != TunnelServerCategory.REGULAR || first.isHomeRoadServer()) return null
    if (first.protocol() == ServerProtocol.OTHER || first.syncHost() == null) return null
    val code = first.countryCode.uppercase(Locale.ROOT)
    return code.takeIf { localizedCountryName(it, Locale.ENGLISH)?.equals(it, ignoreCase = true) == false }
}

private fun nameNodes(groups: List<ServerProtocolGroup>, countryCode: String): List<CountryServerNode> {
    val seen = mutableMapOf<String, Int>()
    return groups.mapIndexed { index, group ->
        val label = group.variants.firstNotNullOfOrNull { serverLabel(it.name, countryCode) }?.let { label ->
            val key = label.lowercase(RUSSIAN)
            val count = (seen[key] ?: 0) + 1
            seen[key] = count
            if (count == 1) label else "$label $count"
        }
        CountryServerNode(group, label, number = index + 1)
    }
}

private fun withoutCountryName(text: String, countryCode: String): String {
    val lower = text.lowercase(RUSSIAN)
    val names = listOfNotNull(
        localizedCountryName(countryCode, RUSSIAN),
        localizedCountryName(countryCode, Locale.ENGLISH),
        countryCode,
    )
    for (name in names) {
        val prefix = name.lowercase(RUSSIAN)
        val next = text.getOrNull(prefix.length)
        if (lower.startsWith(prefix) && (next == null || !next.isLetterOrDigit())) return text.substring(prefix.length)
    }
    return text
}

/** Drops emoji, separators and spaces around the label, as the desktop apps do. */
private fun String.trimLabelEdges(): String {
    var start = 0
    var end = length
    while (start < end && !Character.isLetterOrDigit(codePointAt(start))) start += Character.charCount(codePointAt(start))
    while (end > start && !Character.isLetterOrDigit(codePointBefore(end))) end -= Character.charCount(codePointBefore(end))
    return substring(start, end)
}

private val RUSSIAN = Locale.forLanguageTag("ru")
