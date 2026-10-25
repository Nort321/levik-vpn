package com.leviknet.vpn.core.drop

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** docs/levik-drop.md "Local network": a direct TCP connection when both apps share a network. */
object DropLan {
    /** VPN tunnels (Levik's own too) and mobile data, whose 10/8 addresses are the operator's. */
    private val SKIPPED_INTERFACE = Regex("tun|tap|wg|ppp|ipsec|levik|vpn|rmnet|ccmni|pdp|clat|v4-|dummy", RegexOption.IGNORE_CASE)
    const val DIAL_TIMEOUT_MS = 1_500
    const val PROOF_TIMEOUT_MS = 3_000L

    /** The listener stops waiting for the other app after this long. */
    const val LISTEN_MS = 20_000L

    /** This phone's private IPv4 addresses, VPN tunnels and mobile data left out. */
    fun privateAddresses(): List<String> {
        val found = mutableListOf<String>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        for (network in interfaces) {
            val usable = runCatching { network.isUp && !network.isLoopback }.getOrDefault(false)
            if (!usable || SKIPPED_INTERFACE.containsMatchIn(network.name)) continue
            for (address in network.inetAddresses.toList()) {
                if (address !is Inet4Address) continue
                val text = address.hostAddress ?: continue
                if (DropWire.isPrivateIpv4(text) && text !in found) found += text
            }
        }
        return found.take(8)
    }

    /** Connects to every address at once; the first that proves the session key wins. */
    suspend fun dial(addresses: List<String>, port: Int, transfer: ByteArray, key: ByteArray, scope: CoroutineScope): FrameChannel? {
        if (addresses.isEmpty()) return null
        val winner = CompletableDeferred<FrameChannel?>()
        var pending = addresses.size
        val attempts = addresses.map { address ->
            scope.launch {
                val frames = runCatching { dialOne(address, port, transfer, key, scope) }.getOrNull()
                synchronized(winner) {
                    pending--
                    when {
                        frames != null && !winner.isCompleted -> winner.complete(frames)
                        // A second address may have proved too: only the first stays.
                        frames != null -> frames.close()
                        pending == 0 && !winner.isCompleted -> winner.complete(null)
                    }
                }
            }
        }
        return try {
            winner.await()
        } finally {
            attempts.forEach(Job::cancel)
        }
    }

    private suspend fun dialOne(address: String, port: Int, transfer: ByteArray, key: ByteArray, scope: CoroutineScope): FrameChannel {
        val socket = DropRelay.connect(address, port, DIAL_TIMEOUT_MS)
        val frames = FrameChannel(socket, scope)
        try {
            frames.send(DropWire.lanDialerFrame(transfer, key))
            val answer = frames.next(PROOF_TIMEOUT_MS)
            if (!DropWire.sameBytes(answer, DropWire.lanListenerFrame(key))) throw DropChannelClosed("bad proof")
            return frames
        } catch (error: Exception) {
            frames.close()
            throw error
        }
    }
}

/** Listens on one port of every private address until the other app proves the session key. */
class LanListener private constructor(
    private val transfer: ByteArray,
    private val key: ByteArray,
    private val scope: CoroutineScope,
) {
    private val servers = mutableListOf<ServerSocket>()
    private val result = CompletableDeferred<FrameChannel?>()
    private var timer: Job? = null
    var port = 0
        private set
    val addresses = mutableListOf<String>()

    /** The verified connection, or null after the timeout or [close]. */
    suspend fun accepted(): FrameChannel? = result.await()

    fun close() = finish(null)

    private fun finish(frames: FrameChannel?) {
        timer?.cancel()
        synchronized(servers) {
            servers.forEach { runCatching { it.close() } }
            servers.clear()
        }
        val settled = synchronized(result) { !result.isCompleted && result.complete(frames) }
        if (!settled) frames?.close()
    }

    private fun serve(server: ServerSocket) = scope.launch(Dispatchers.IO) {
        while (!result.isCompleted) {
            val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@launch
            scope.launch { check(socket) }
        }
    }

    private suspend fun check(socket: Socket) {
        val frames = FrameChannel(socket, scope)
        if (result.isCompleted) {
            frames.close()
            return
        }
        try {
            val frame = frames.next(DropLan.PROOF_TIMEOUT_MS)
            if (result.isCompleted || !DropWire.sameBytes(frame, DropWire.lanDialerFrame(transfer, key))) throw DropChannelClosed("bad proof")
            frames.send(DropWire.lanListenerFrame(key))
            finish(frames)
        } catch (_: Exception) {
            frames.close()
        }
    }

    companion object {
        suspend fun open(transfer: ByteArray, key: ByteArray, scope: CoroutineScope, addresses: List<String> = DropLan.privateAddresses()): LanListener? {
            val listener = LanListener(transfer, key, scope)
            withContext(Dispatchers.IO) {
                for (address in addresses) {
                    val server = ServerSocket()
                    val bound = runCatching {
                        server.reuseAddress = false
                        server.bind(InetSocketAddress(InetAddress.getByName(address), listener.port))
                    }.isSuccess
                    if (!bound) {
                        runCatching { server.close() }
                        continue
                    }
                    if (listener.port == 0) listener.port = server.localPort
                    synchronized(listener.servers) { listener.servers += server }
                    listener.addresses += address
                }
            }
            if (listener.servers.isEmpty()) return null
            listener.servers.toList().forEach(listener::serve)
            listener.timer = scope.launch {
                delay(DropLan.LISTEN_MS)
                listener.finish(null)
            }
            return listener
        }
    }
}
