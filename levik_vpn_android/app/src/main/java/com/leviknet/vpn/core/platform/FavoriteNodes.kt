package com.leviknet.vpn.core.platform

import com.leviknet.vpn.vpn.TunnelServer
import com.leviknet.vpn.vpn.syncHost
import com.leviknet.vpn.vpn.syncPort
import java.security.MessageDigest

/**
 * Favourite servers shared between the user's apps, docs/app-platform.md.
 *
 * A server is named by its node id: the hash of its endpoint host, so every
 * protocol of one server has the same id on every platform. The account keeps
 * a sorted list of node ids; this phone keeps its own server ids for the UI and
 * reconciles both sides whenever either the favourites or the servers change.
 */
object FavoriteNodes {
    const val MAX = 40
    private val NODE_ID = Regex("^n1[0-9a-f]{20}$")

    fun nodeId(host: String): String? {
        val normalized = host.trim().lowercase().removeSurrounding("[", "]").removeSuffix(".")
        if (normalized.isEmpty() || normalized.length > 253) return null
        val digest = MessageDigest.getInstance("SHA-256").digest("levik-node-v1|$normalized".toByteArray())
        return "n1" + digest.joinToString("") { "%02x".format(it) }.take(20)
    }

    fun nodeId(server: TunnelServer): String? = server.syncHost()?.let(::nodeId)

    /**
     * One port of a host, docs/app-platform.md "Node ids". A relay forwards several
     * servers from one address on distinct ports, so only the port tells them apart.
     */
    fun endpointId(host: String, port: Int): String? {
        val normalized = host.trim().lowercase().removeSurrounding("[", "]").removeSuffix(".")
        if (normalized.isEmpty() || normalized.length > 253 || port !in 1..65_535) return null
        val digest = MessageDigest.getInstance("SHA-256").digest("levik-endpoint-v1|$normalized|$port".toByteArray())
        return "n1" + digest.joinToString("") { "%02x".format(it) }.take(20)
    }

    /**
     * A server's load, docs/app-platform.md "Node load": by its host and port
     * first, which names one server behind a shared relay, then by its host.
     */
    fun load(load: Map<String, Double>, server: TunnelServer): Double? {
        val host = server.syncHost() ?: return null
        val exact = server.syncPort()?.let { endpointId(host, it) }?.let(load::get)
        return exact ?: nodeId(host)?.let(load::get)
    }

    fun isCanonical(value: String): Boolean {
        if (value.isEmpty()) return true
        val ids = value.split(',')
        return ids.size <= MAX && ids.all(NODE_ID::matches) && ids.zipWithNext().all { (a, b) -> a < b }
    }

    fun parse(value: String?): Set<String> =
        value?.takeIf(::isCanonical)?.takeIf(String::isNotEmpty)?.split(',')?.toSet().orEmpty()

    /** Sorted and distinct; beyond the limit the last ids in order are left out. */
    fun canonical(ids: Collection<String>): String =
        ids.filter(NODE_ID::matches).distinct().sorted().take(MAX).joinToString(",")

    fun union(first: String, second: String): String = canonical(parse(first) + parse(second))

    /** Server ids of this phone grouped by node; servers without an endpoint host are not shared. */
    fun serversByNode(servers: List<TunnelServer>): Map<String, List<String>> =
        servers.mapNotNull { server -> nodeId(server)?.let { it to server.id } }
            .groupBy({ it.first }, { it.second })

    data class State(
        /** What the account should hold. */
        val nodes: Set<String>,
        /** Local favourites as they were after the last reconcile. */
        val reconciledLocal: Set<String>,
    )

    data class Result(val state: State, val local: Set<String>)

    /**
     * A node the user starred or unstarred here since the last reconcile is a
     * local edit and changes the account list. For every other node present on
     * this phone the account list wins. Favourites of servers without a node id
     * stay untouched, and nodes this phone does not have are kept for the others.
     */
    fun reconcile(state: State, local: Set<String>, byNode: Map<String, List<String>>): Result {
        val nodes = state.nodes.toMutableSet()
        val result = local.toMutableSet()
        byNode.forEach { (node, ids) ->
            val now = ids.any(local::contains)
            val before = ids.any(state.reconciledLocal::contains)
            when {
                now != before -> if (now) nodes += node else nodes -= node
                node in nodes && !now -> result += ids
                node !in nodes && now -> result -= ids.toSet()
            }
        }
        val bounded = parse(canonical(nodes))
        return Result(State(bounded, result), result)
    }
}
