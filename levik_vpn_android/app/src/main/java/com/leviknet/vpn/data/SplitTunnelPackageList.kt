package com.leviknet.vpn.data

internal object SplitTunnelPackageList {
    private val separators = Regex("[,\\s\\p{Z}]+")
    private val packageSegment = Regex("[A-Za-z][A-Za-z0-9_]*")

    fun parse(text: String): Set<String> = text.splitToSequence(separators)
        .map(String::trim)
        .filter { name ->
            // Android's framework package is the single-segment exception.
            name == "android" || (
                '.' in name && name.splitToSequence('.').all(packageSegment::matches)
            )
        }
        .toSet()

    fun format(packages: Set<String>): String = packages.sorted().joinToString("\n")
}
