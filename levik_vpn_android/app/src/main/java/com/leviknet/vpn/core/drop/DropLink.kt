package com.leviknet.vpn.core.drop

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One transfer's relay connection, docs/levik-drop.md "Relay": joins with a
 * fresh token, starts a session on every `peer`, rejoins after a break.
 */

enum class LinkStatus { CONNECTING, WAITING, SESSION, RETRYING }

class RelayConnection(val token: String, val endpoints: List<RelayEndpoint>)

interface LinkHost {
    /** The website's `connect`: a fresh token. Throws when the transfer cannot continue. */
    suspend fun connect(): RelayConnection

    fun work(allowLan: Boolean): SessionWork

    fun status(status: LinkStatus)

    /** Every session's end; a final outcome stops the link. */
    fun outcome(outcome: SessionOutcome)

    /** The relay ended the connection for good (`ended`, `limit`) or `connect` failed. */
    fun stopped(reason: String, error: Throwable?)
}

class RelayLink(
    private val host: LinkHost,
    private val scope: CoroutineScope,
    private val joiner: RelayJoiner = DropRelay.tls,
) {
    private var running = false
    private var socket: FrameChannel? = null
    private var session: DropSession? = null
    private var failures = 0
    private var allowLan = true
    private var loop: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    val current: DropSession? get() = session

    fun start() {
        if (running) return
        running = true
        loop = scope.launch { run() }
    }

    /** Stops quietly: the session (if any) is only dropped, nothing is said to the other app. */
    fun stop() {
        running = false
        session?.abort("stopped")
        socket?.close()
        loop?.cancel()
        wake.trySend(Unit)
    }

    /** After a pause or cancel the record leaves first, then the connection closes. */
    private fun closeAfterSaying() {
        running = false
        socket?.closeGracefully()
        wake.trySend(Unit)
    }

    private suspend fun sleep(ms: Long) {
        withTimeoutOrNull(ms) { wake.receive() }
    }

    private fun retryDelay(): Long = RETRY_MS[minOf(failures++, RETRY_MS.lastIndex)]

    private suspend fun run() {
        while (running) {
            host.status(if (failures == 0) LinkStatus.CONNECTING else LinkStatus.RETRYING)
            val connection = try {
                host.connect()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                running = false
                host.stopped("connect", error)
                return
            }
            if (!running) return
            val joined = try {
                joiner.join(connection.endpoints, connection.token, scope)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!running) return
                sleep(retryDelay())
                continue
            }
            socket = joined
            val reason = serve(joined)
            joined.close()
            socket = null
            if (!running) return
            if (reason != null && reason in FINAL_ENDS) {
                running = false
                host.stopped(reason, null)
                return
            }
            sleep(retryDelay())
        }
    }

    /** Reads the relay until the connection ends; returns the relay's end reason, if it gave one. */
    private suspend fun serve(frames: FrameChannel): String? {
        while (true) {
            val frame = try {
                frames.next()
            } catch (_: DropChannelClosed) {
                session?.abort("relay connection closed")
                return null
            }
            when (val control = DropRelay.control(frame)) {
                null -> session?.inbox?.trySend(frame)
                RelayControl.Wait -> {
                    host.status(LinkStatus.WAITING)
                    frames.watchIdle(0)
                }
                RelayControl.Gone -> {
                    session?.abort(PEER_LEFT)
                    host.status(LinkStatus.WAITING)
                    frames.watchIdle(0)
                }
                RelayControl.Peer -> {
                    session?.abort(NEW_SESSION)
                    // The other app's keepalives come through now: silence means a dead path.
                    frames.watchIdle(PEER_IDLE_MS)
                    startSession(frames)
                }
                is RelayControl.End -> {
                    session?.abort("relay: ${control.reason}")
                    return control.reason
                }
            }
        }
    }

    private fun startSession(frames: FrameChannel) {
        val work = try {
            host.work(allowLan)
        } catch (_: Exception) {
            // The transfer is gone here: the next refresh stops this link.
            return
        }
        val started = DropSession(frames, work, scope)
        session = started
        host.status(LinkStatus.SESSION)
        scope.launch {
            val outcome = started.run()
            if (session === started) session = null
            if (outcome !is SessionOutcome.Lost) {
                host.outcome(outcome)
                closeAfterSaying()
                return@launch
            }
            if (outcome.lanBroke) allowLan = false
            // A session that broke on its own needs a fresh `peer`: rejoining gives both apps one.
            val left = outcome.detail == PEER_LEFT || outcome.detail == NEW_SESSION || outcome.detail.startsWith("relay")
            host.outcome(outcome)
            if (!left && running && socket === frames) frames.close()
        }
    }

    /** Progress was made: the next break retries quickly again. */
    fun progressed() {
        failures = 0
    }

    private companion object {
        val RETRY_MS = longArrayOf(1_000, 3_000, 10_000, 30_000, 60_000)

        /** Relay ends that mean: do not come back with this transfer. */
        val FINAL_ENDS = setOf("ended", "limit", "replaced")
        const val PEER_IDLE_MS = 60_000L
        const val PEER_LEFT = "the other app left"
        const val NEW_SESSION = "new session"
    }
}
