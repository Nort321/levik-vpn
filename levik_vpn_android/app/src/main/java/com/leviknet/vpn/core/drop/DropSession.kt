package com.leviknet.vpn.core.drop

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One session of a transfer, docs/levik-drop.md "Session": from the relay's
 * `peer` to its end. Keys never outlive it; what was written does.
 *
 * Everything here runs on the engine's one-at-a-time dispatcher; blocking
 * work goes to [Dispatchers.IO] and comes back.
 */

enum class Trust { TRUSTED, NEW, CHANGED }

enum class SessionPath { RELAY, LAN }

sealed class SessionOutcome {
    /** The connection or the handshake broke: the next `peer` starts again. */
    data class Lost(val detail: String, val lanBroke: Boolean) : SessionOutcome()
    data object Done : SessionOutcome()
    data class Declined(val bySelf: Boolean) : SessionOutcome()
    data class Paused(val bySelf: Boolean) : SessionOutcome()
    data class Cancelled(val bySelf: Boolean) : SessionOutcome()
    data class Failed(val bySelf: Boolean, val reason: String) : SessionOutcome()
}

/** This app's identity key: the private half never leaves the Android Keystore. */
interface DropIdentity {
    /** ECDSA P-256, SPKI DER. */
    val spki: ByteArray

    /** ECDSA-P256-SHA256, DER. */
    fun sign(payload: ByteArray): ByteArray
}

class SessionEvents(
    /** After the handshake: the code both screens show and the peer's key. */
    val keyed: (code: String, trust: Trust, peerKey: ByteArray) -> Unit,
    val path: (SessionPath) -> Unit,
    /** Bytes of the whole transfer in place (receiver) or acknowledged (sender). */
    val progress: (Long) -> Unit,
)

sealed class SessionWork(
    val transferId: String,
    val identity: DropIdentity,
    val ownPlatform: String,
    val peerPlatform: String?,
    val trust: (ByteArray) -> Trust,
    /** Unless the direct connection broke in an earlier session of this relay connection. */
    val allowLan: Boolean,
    val events: SessionEvents,
    /** True once the user confirmed the code here; false: the codes differ. */
    val confirm: suspend (code: String, trust: Trust) -> Boolean,
)

class SenderWork(
    transferId: String,
    identity: DropIdentity,
    ownPlatform: String,
    peerPlatform: String?,
    trust: (ByteArray) -> Trust,
    allowLan: Boolean,
    events: SessionEvents,
    confirm: suspend (String, Trust) -> Boolean,
    val files: List<SourceFile>,
    val sources: DropSources,
) : SessionWork(transferId, identity, ownPlatform, peerPlatform, trust, allowLan, events, confirm)

class ReceiverWork(
    transferId: String,
    identity: DropIdentity,
    ownPlatform: String,
    peerPlatform: String?,
    trust: (ByteArray) -> Trust,
    allowLan: Boolean,
    events: SessionEvents,
    confirm: suspend (String, Trust) -> Boolean,
    /** The manifest of an earlier session; later ones must repeat it exactly. */
    var manifest: List<ManifestFile>?,
    /** Set once the user accepted. */
    var targets: TargetFiles?,
    /** First session: the user accepts (and so confirms the code) or declines. */
    val decide: suspend (manifest: List<ManifestFile>, code: String, trust: Trust) -> TargetFiles?,
) : SessionWork(transferId, identity, ownPlatform, peerPlatform, trust, allowLan, events, confirm)

class DropSession(
    private val relay: FrameChannel,
    private val work: SessionWork,
    private val parent: CoroutineScope,
) {
    /** The relay's frames for this session, fed by the relay connection. */
    val inbox = Channel<ByteArray>(Channel.UNLIMITED)

    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val result = CompletableDeferred<SessionOutcome>()
    private val transfer = DropWire.transferBytes(work.transferId)
    private val sendLock = Mutex()
    private val random = SecureRandom()
    private var keys: DropWire.SessionKeys? = null
    private var sealer: DropWire.RecordSealer? = null
    private var opener: DropWire.RecordOpener? = null
    private var input = SessionPath.RELAY
    private var output = SessionPath.RELAY
    private var lan: FrameChannel? = null
    private var lanReady: Deferred<FrameChannel?>? = null
    private var lanListener: LanListener? = null
    private var lanBroke = false
    private var outcome: SessionOutcome? = null

    // Sender
    private var manifestSent = false
    private var accepted: DropControl.Accept? = null
    private var acked = LongArray(0)

    /** Data bytes sent, and acknowledged beyond `accept`, in this session: their difference is in flight. */
    private var sentBytes = 0L
    private var ackedAtAccept = 0L
    private val ackSignal = Channel<Unit>(Channel.CONFLATED)

    // Receiver
    private var acceptSent = false
    private val lastAck = mutableMapOf<Int, Long>()
    private val written = mutableMapOf<Int, Long>()

    private class Ended : Exception("session ended")

    val finished: Boolean get() = outcome != null

    /** Runs the session to its outcome; never throws. */
    suspend fun run(): SessionOutcome {
        scope.launch {
            try {
                main()
            } catch (error: Throwable) {
                fromError(error)
            }
        }
        return result.await()
    }

    /** The relay said `gone`, or the connection closed. */
    fun abort(detail: String) = finish(SessionOutcome.Lost(detail, lanBroke))

    fun pause() = sayAndFinish(DropControl.Pause, SessionOutcome.Paused(bySelf = true))

    fun cancel() = sayAndFinish(DropControl.Cancel, SessionOutcome.Cancelled(bySelf = true))

    fun fail(reason: String) = sayAndFinish(DropControl.Fail(reason), SessionOutcome.Failed(bySelf = true, reason))

    private fun sayAndFinish(control: DropControl, outcome: SessionOutcome) {
        if (this.outcome != null) return
        val say = if (sealer != null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { runCatching { sendControl(control) } }
        } else {
            null
        }
        finish(outcome, say)
    }

    private fun finish(outcome: SessionOutcome, say: Job? = null) {
        if (this.outcome != null) return
        this.outcome = outcome
        inbox.close()
        lanListener?.close()
        ackSignal.trySend(Unit)
        parent.launch {
            // The last record leaves before anything closes.
            if (say != null) withTimeoutOrNull(SAY_TIMEOUT_MS) { say.join() }
            job.cancel()
            lan?.closeGracefully()
            (work as? ReceiverWork)?.targets?.closeWriting()
            result.complete(outcome)
        }
    }

    private fun fromError(error: Throwable) {
        if (outcome != null) return
        when (error) {
            is TransferFailure -> fail(error.reason)
            // A broken record or message: this session is over, the transfer is not.
            is DropProtocolException -> finish(SessionOutcome.Lost("protocol: ${error.message}", lanBroke))
            is CancellationException -> finish(SessionOutcome.Lost("stopped", lanBroke))
            else -> finish(SessionOutcome.Lost(error.message ?: "session", lanBroke))
        }
    }

    // Handshake ----------------------------------------------------------------------------

    private suspend fun nextHandshake(): ByteArray =
        withTimeoutOrNull(STEP_TIMEOUT_MS) { inbox.receiveCatching().getOrNull() ?: throw Ended() }
            ?: throw DropChannelClosed("timed out")

    private suspend fun sign(sender: Boolean, th: ByteArray): ByteArray =
        withContext(Dispatchers.IO) { work.identity.sign(DropWire.signedPayload(sender, th)) }

    private fun nonce(): ByteArray = ByteArray(32).also(random::nextBytes)

    private suspend fun handshake(): Pair<DropWire.SessionKeys, ByteArray> {
        val own = work.identity
        val pair = withContext(Dispatchers.IO) { DropWire.ephemeralKey() }
        val ephemeral = DropWire.encodePoint(pair.public as ECPublicKey)
        if (work is SenderWork) {
            val nonce = nonce()
            val hello = DropWire.SenderHello(transfer, own.spki, ephemeral, DropWire.commitment(own.spki, ephemeral, nonce))
            relay.send(DropWire.encodeSenderHello(hello))
            val reply = DropWire.decodeReceiverHello(nextHandshake())
            if (!DropWire.sameBytes(reply.transfer, transfer)) throw DropProtocolException("another transfer")
            val th = DropWire.transcript(hello, reply, nonce)
            relay.send(DropWire.encodeSenderProof(nonce, sign(true, th)))
            val signature = DropWire.decodeReceiverProof(nextHandshake())
            if (!DropWire.verifyTranscript(false, reply.identity, th, signature)) throw DropProtocolException("receiver signature")
            return DropWire.sessionKeys(pair, reply.ephemeral, th) to reply.identity
        }
        val hello = DropWire.decodeSenderHello(nextHandshake())
        if (!DropWire.sameBytes(hello.transfer, transfer)) throw DropProtocolException("another transfer")
        val reply = DropWire.ReceiverHello(transfer, own.spki, ephemeral, nonce())
        relay.send(DropWire.encodeReceiverHello(reply))
        val (senderNonce, signature) = DropWire.decodeSenderProof(nextHandshake())
        if (!DropWire.sameBytes(DropWire.commitment(hello.identity, hello.ephemeral, senderNonce), hello.commit)) {
            throw DropProtocolException("commitment")
        }
        val th = DropWire.transcript(hello, reply, senderNonce)
        if (!DropWire.verifyTranscript(true, hello.identity, th, signature)) throw DropProtocolException("sender signature")
        relay.send(DropWire.encodeReceiverProof(sign(false, th)))
        return DropWire.sessionKeys(pair, hello.ephemeral, th) to hello.identity
    }

    private suspend fun main() {
        val (sessionKeys, peerKey) = handshake()
        keys = sessionKeys
        val sender = work is SenderWork
        sealer = DropWire.RecordSealer(if (sender) sessionKeys.sr else sessionKeys.rs)
        opener = DropWire.RecordOpener(if (sender) sessionKeys.rs else sessionKeys.sr)
        val trust = work.trust(peerKey)
        work.events.keyed(sessionKeys.code, trust, peerKey)
        work.events.path(SessionPath.RELAY)

        val reading = scope.launch { readLoop(trust) }
        if (work is SenderWork) {
            if (trust != Trust.TRUSTED && !work.confirm(sessionKeys.code, trust)) {
                fail("key_rejected")
                return
            }
            if (outcome != null) return
            sendControl(DropControl.Manifest(work.files.map { ManifestFile(it.name, it.size) }))
            manifestSent = true
        }
        if (listensOnLan()) scope.launch { openLan() }
        reading.join()
    }

    // Records ------------------------------------------------------------------------------

    private fun socketFor(path: SessionPath): FrameChannel = when (path) {
        SessionPath.RELAY -> relay
        SessionPath.LAN -> lan ?: throw DropChannelClosed("no direct connection")
    }

    /** Sealing and writing in one step keeps the counters in the order the bytes leave. */
    private suspend fun sendPlaintext(plaintext: ByteArray, switchTo: SessionPath? = null) {
        val records = sealer ?: throw DropProtocolException("no keys")
        sendLock.withLock {
            socketFor(output).send(records.seal(plaintext))
            if (switchTo != null) output = switchTo
        }
    }

    private suspend fun sendControl(control: DropControl, switchTo: SessionPath? = null) =
        sendPlaintext(DropWire.encodeControl(control), switchTo)

    private suspend fun nextRecord(): ByteArray {
        if (input == SessionPath.RELAY) return inbox.receiveCatching().getOrNull() ?: throw Ended()
        try {
            return socketFor(SessionPath.LAN).next()
        } catch (error: Exception) {
            lanBroke = true
            throw error
        }
    }

    private suspend fun readLoop(trust: Trust) {
        val records = opener ?: return
        try {
            while (true) {
                val frame = nextRecord()
                if (outcome != null) return
                when (val plaintext = DropWire.decodePlaintext(records.open(frame))) {
                    DropPlaintext.Unknown -> continue
                    is DropPlaintext.Data -> onData(plaintext.file, plaintext.offset, plaintext.bytes)
                    is DropPlaintext.Control -> onControl(plaintext.control, trust)
                }
                if (outcome != null) return
            }
        } catch (_: Ended) {
            return
        } catch (error: Throwable) {
            fromError(error)
        }
    }

    private suspend fun onControl(control: DropControl, trust: Trust) {
        when (control) {
            DropControl.Pause -> finish(SessionOutcome.Paused(bySelf = false))
            DropControl.Cancel -> finish(SessionOutcome.Cancelled(bySelf = false))
            is DropControl.Fail -> finish(SessionOutcome.Failed(bySelf = false, control.reason))
            is DropControl.Lan -> if (!listensOnLan() && work.allowLan && lanReady == null) dialLan(control.addrs, control.port)
            // The sender's last record through the relay.
            DropControl.LanOn -> {
                if (work !is ReceiverWork || input != SessionPath.RELAY) throw DropProtocolException("unexpected lan_on")
                switchReceiver()
            }
            DropControl.LanOk -> {
                if (work !is SenderWork || output != SessionPath.LAN || input != SessionPath.RELAY) {
                    throw DropProtocolException("unexpected lan_ok")
                }
                input = SessionPath.LAN
            }
            else -> if (work is SenderWork) senderControl(work, control) else receiverControl(work as ReceiverWork, control, trust)
        }
    }

    // Local network ------------------------------------------------------------------------

    private fun listensOnLan(): Boolean {
        if (!work.allowLan) return false
        val receiver = work is ReceiverWork
        val receiverPlatform = if (receiver) work.ownPlatform else work.peerPlatform
        val senderPlatform = if (receiver) work.peerPlatform else work.ownPlatform
        val receiverListens = !(receiverPlatform == "windows" && senderPlatform != "windows")
        return receiver == receiverListens
    }

    private suspend fun openLan() {
        val lanKey = keys?.lan ?: return
        val listener = LanListener.open(transfer, lanKey, parent) ?: return
        if (outcome != null) {
            listener.close()
            return
        }
        lanListener = listener
        val ready = scope.async { listener.accepted() }
        lanReady = ready
        runCatching { sendControl(DropControl.Lan(listener.addresses.toList(), listener.port)) }
        onLanReady(ready.await())
    }

    private fun dialLan(addresses: List<String>, port: Int) {
        val lanKey = keys?.lan ?: return
        val ready = scope.async { DropLan.dial(addresses, port, transfer, lanKey, parent) }
        lanReady = ready
        scope.launch { onLanReady(ready.await()) }
    }

    private fun onLanReady(socket: FrameChannel?) {
        if (socket == null || lan === socket) return
        if (outcome != null) {
            socket.close()
            return
        }
        lan = socket
        socket.startKeepalive()
        socket.watchIdle(LAN_IDLE_MS)
        socket.onClose {
            scope.launch {
                if (outcome != null) return@launch
                lanBroke = true
                if (output == SessionPath.LAN || input == SessionPath.LAN) abort("direct connection closed")
            }
        }
        if (work is SenderWork) {
            scope.launch {
                try {
                    sendControl(DropControl.LanOn, switchTo = SessionPath.LAN)
                    work.events.path(SessionPath.LAN)
                } catch (error: Exception) {
                    fromError(error)
                }
            }
        }
    }

    private suspend fun switchReceiver() {
        val ready = lanReady
        val socket = if (ready != null) withTimeoutOrNull(LAN_SWITCH_TIMEOUT_MS) { ready.await() } else null
        if (socket == null || socket.closed) {
            lanBroke = true
            throw DropChannelClosed("direct connection did not come up")
        }
        onLanReady(socket)
        sendControl(DropControl.LanOk, switchTo = SessionPath.LAN)
        input = SessionPath.LAN
        work.events.path(SessionPath.LAN)
    }

    // Sender -------------------------------------------------------------------------------

    private fun ackedTotal(): Long = acked.sum()

    private fun senderControl(work: SenderWork, control: DropControl) {
        val files = work.files
        when (control) {
            is DropControl.Accept -> {
                if (!manifestSent || accepted != null || control.offsets.size != files.size) throw DropProtocolException("bad accept")
                control.offsets.forEachIndexed { index, offset ->
                    val size = files[index].size
                    if (offset > size || ((offset in 1 until size) != (control.prefix[index] != null))) throw DropProtocolException("bad accept")
                }
                accepted = control
                acked = control.offsets.toLongArray()
                ackedAtAccept = ackedTotal()
                work.events.progress(ackedAtAccept)
                scope.launch {
                    try {
                        pump(work, control)
                    } catch (_: Ended) {
                        // The session ended while sending.
                    } catch (error: Throwable) {
                        fromError(error)
                    }
                }
            }
            DropControl.Decline -> finish(SessionOutcome.Declined(bySelf = false))
            is DropControl.Ack -> {
                val size = files.getOrNull(control.file)?.size
                if (size == null || control.offset > size || accepted == null) throw DropProtocolException("bad ack")
                acked[control.file] = maxOf(acked[control.file], control.offset)
                work.events.progress(ackedTotal())
                ackSignal.trySend(Unit)
            }
            is DropControl.FileOk -> {
                val size = files.getOrNull(control.file)?.size
                if (size == null || accepted == null) throw DropProtocolException("bad file_ok")
                acked[control.file] = size
                work.events.progress(ackedTotal())
                ackSignal.trySend(Unit)
            }
            DropControl.Done -> {
                if (accepted == null) throw DropProtocolException("early done")
                finish(SessionOutcome.Done)
            }
            else -> Unit
        }
    }

    private suspend fun pump(work: SenderWork, accept: DropControl.Accept) {
        work.files.forEachIndexed { index, file ->
            val offset = accept.offsets[index]
            if (offset == file.size && file.size > 0) return@forEachIndexed
            val digest = MessageDigest.getInstance("SHA-256")
            var position = offset
            readSource(work.sources, file, offset, accept.prefix[index], digest) { buffer, length ->
                while (outcome == null && sentBytes - (ackedTotal() - ackedAtAccept) > DropWire.WINDOW) ackSignal.receive()
                if (outcome != null) throw Ended()
                sendPlaintext(DropWire.encodeData(index, position, buffer, length))
                position += length
                sentBytes += length
            }
            if (outcome != null) return
            sendControl(DropControl.End(index, digest.digest().toHex()))
        }
    }

    // Receiver -----------------------------------------------------------------------------

    private suspend fun receiverControl(work: ReceiverWork, control: DropControl, trust: Trust) {
        when (control) {
            is DropControl.End -> onEnd(work, control.file, control.sha256)
            is DropControl.Manifest -> {
                val known = work.manifest
                if (known != null && known != control.files) throw TransferFailure("protocol", "manifest changed")
                work.manifest = control.files
                // The user's answer may take minutes; records keep being read meanwhile.
                scope.launch {
                    try {
                        answerManifest(work, control.files, trust)
                    } catch (error: Throwable) {
                        fromError(error)
                    }
                }
            }
            else -> Unit
        }
    }

    private suspend fun answerManifest(work: ReceiverWork, manifest: List<ManifestFile>, trust: Trust) {
        val code = keys?.code.orEmpty()
        if (work.targets != null) {
            if (trust != Trust.TRUSTED && !work.confirm(code, trust)) {
                fail("key_rejected")
                return
            }
        } else {
            val targets = work.decide(manifest, code, trust)
            if (outcome != null) return
            if (targets == null) {
                sayAndFinish(DropControl.Decline, SessionOutcome.Declined(bySelf = true))
                return
            }
            work.targets = targets
        }
        if (outcome != null) return
        val targets = work.targets ?: return
        val kept = targets.offsets()
        if (outcome != null) return
        kept.offsets.forEachIndexed { index, offset -> lastAck[index] = offset }
        work.events.progress(targets.doneBytes(kept.offsets))
        sendControl(DropControl.Accept(kept.offsets, kept.prefix))
        acceptSent = true
        maybeDone(work)
    }

    private suspend fun onData(file: Int, offset: Long, bytes: ByteArray) {
        val targets = (work as? ReceiverWork)?.targets
        if (targets == null || !acceptSent) throw DropProtocolException("data before accept")
        val now = targets.write(file, offset, bytes)
        written[file] = now
        if (now - (lastAck[file] ?: 0L) >= DropWire.ACK_EVERY) {
            lastAck[file] = now
            sendControl(DropControl.Ack(file, now))
        }
        work.events.progress(receivedTotal(targets))
    }

    private fun receivedTotal(targets: TargetFiles): Long = targets.targets.withIndex().sumOf { (index, target) ->
        if (target.final != null) target.size else maxOf(written[index] ?: 0L, lastAck[index] ?: 0L)
    }

    private suspend fun onEnd(work: ReceiverWork, file: Int, sha256: String) {
        val targets = work.targets
        if (targets == null || !acceptSent) throw DropProtocolException("end before accept")
        // An empty file already in place is offered at offset 0 again: it stays as it is.
        if (targets.targets.getOrNull(file)?.final == null) targets.finish(file, sha256)
        sendControl(DropControl.FileOk(file))
        work.events.progress(receivedTotal(targets))
        maybeDone(work)
    }

    private suspend fun maybeDone(work: ReceiverWork) {
        val targets = work.targets ?: return
        if (targets.targets.any { it.final == null }) return
        sendControl(DropControl.Done)
        finish(SessionOutcome.Done)
    }

    private companion object {
        const val STEP_TIMEOUT_MS = 30_000L
        const val LAN_SWITCH_TIMEOUT_MS = 5_000L
        const val LAN_IDLE_MS = 60_000L
        const val SAY_TIMEOUT_MS = 2_000L
    }
}
