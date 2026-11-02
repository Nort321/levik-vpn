package com.leviknet.vpn.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class WhitelistMapChannel { WEBSITE, SERVICE }
internal enum class WhitelistMapOperation(val path: String) { REGION("region"), REPORT("report") }
internal data class WhitelistMapResponse(val status: Int, val body: String = "")

/** Protocol state only. The transport cannot choose a destination from report data. */
internal class WhitelistMapDelivery<N : Any>(
    private val enabled: () -> Boolean,
    private val elapsedMillis: () -> Long,
    private val wallMillis: () -> Long,
    private val request: suspend (WhitelistMapChannel, WhitelistMapOperation, N?, String?) -> WhitelistMapResponse?,
) {
    private val json = Json { ignoreUnknownKeys = false }
    private var cached: RegionToken<N>? = null

    private data class RegionToken<N>(
        val network: N,
        val token: String,
        val channel: WhitelistMapChannel,
        val receivedElapsedMs: Long,
        val expiresElapsedMs: Long,
    )

    @Serializable private data class RegionResponse(val token: String, val expiresAt: Long)
    @Serializable private data class Report(val token: String, val signal: String, val transport: String)

    fun clear() { cached = null }

    suspend fun collect(network: N?, isCurrent: () -> Boolean, detect: suspend () -> WhitelistMode) {
        if (!enabled() || network == null || !isCurrent()) { clear(); return }
        cached = cached?.takeIf { usable(it, network) }

        suspend fun exchange(channel: WhitelistMapChannel, operation: WhitelistMapOperation, body: String? = null): WhitelistMapResponse? {
            currentCoroutineContext().ensureActive()
            if (!enabled() || !isCurrent()) return null
            // A region lookup and ALL service requests use the measured physical network.
            // Only an ordinary report with a previously issued region may use the VPN route.
            val boundNetwork = if (operation == WhitelistMapOperation.REGION || channel == WhitelistMapChannel.SERVICE) network else null
            return try { request(channel, operation, boundNetwork, body) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
        }

        if (cached == null) {
            for (channel in WhitelistMapChannel.entries) {
                val response = exchange(channel, WhitelistMapOperation.REGION) ?: continue
                if (response.status != 200) continue
                val region = try { json.decodeFromString<RegionResponse>(response.body) }
                catch (_: IllegalArgumentException) { continue }
                val ttl = region.expiresAt - wallMillis()
                if (region.token.length !in 1..512 || ttl !in 1..WHITELIST_MAP_MAX_TOKEN_AGE_MS) continue
                if (!enabled() || !isCurrent()) { clear(); return }
                val received = elapsedMillis()
                cached = RegionToken(network, region.token, channel, received, received + ttl)
                break
            }
        }
        val region = cached ?: return
        val signal = whitelistMapSignal(detect()) ?: return
        currentCoroutineContext().ensureActive()
        if (!enabled() || !isCurrent() || !usable(region, network)) { clear(); return }
        val body = json.encodeToString(Report(region.token, signal, "cellular"))
        val response = exchange(region.channel, WhitelistMapOperation.REPORT, body)
        if (response?.status == 422) { clear(); return }
        if (response?.status in setOf(200, 204)) return
        // Fresh, bounded retry only. Reports are never stored or replayed in a later run.
        if (usable(region, network)) {
            val alternate = when (region.channel) {
                WhitelistMapChannel.WEBSITE -> WhitelistMapChannel.SERVICE
                WhitelistMapChannel.SERVICE -> WhitelistMapChannel.WEBSITE
            }
            val fallback = exchange(alternate, WhitelistMapOperation.REPORT, body)
            if (fallback?.status == 422) clear()
        }
    }

    private fun usable(region: RegionToken<N>, network: N): Boolean = whitelistRegionTokenIsUsable(
        region.network == network, region.receivedElapsedMs, region.expiresElapsedMs, elapsedMillis(),
    )
}
