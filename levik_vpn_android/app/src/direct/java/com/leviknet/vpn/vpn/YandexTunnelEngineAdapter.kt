package com.leviknet.vpn.vpn

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.leviknet.vpn.BuildConfig
import com.leviknet.vpn.core.logger.AppLogger
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun interface YandexNativeSessionFactory {
    fun create(
        config: YandexServerConfig,
        environment: TunnelEngineEnvironment,
    ): YandexNativeSession
}

internal interface YandexNativeSession {
    suspend fun prepare(): LocalProxyEndpoint

    suspend fun start()

    suspend fun refresh(config: YandexServerConfig): Boolean

    fun stop()
}

internal class YandexTunnelEngineAdapter(
    private val sessionFactory: YandexNativeSessionFactory,
    private val xrayRuntime: XrayRuntime,
) : TunnelEngineAdapter, YandexRefreshableTunnelEngineAdapter {
    override val kind: TunnelEngineKind = TunnelEngineKind.LEVIK_YANDEX
    private val lock = Any()
    private val owners = mutableSetOf<Long>()
    private val sessions = mutableMapOf<Long, ActiveYandexSession>()

    override fun claimOwner(owner: Long) {
        xrayRuntime.claimOwner(owner)
        synchronized(lock) {
            owners += owner
        }
    }

    override fun retireOwner(owner: Long) {
        val active = synchronized(lock) {
            owners -= owner
            sessions.remove(owner)
        }
        active?.let { session ->
            xrayRuntime.stop(owner, session.lease)
            session.nativeSession.stop()
        }
        xrayRuntime.retireOwner(owner)
    }

    override suspend fun prepare(
        owner: Long,
        request: TunnelEngineRequest,
        environment: TunnelEngineEnvironment,
    ): PreparedTunnelEngineSession {
        val yandex = request as? TunnelEngineRequest.Yandex
            ?: throw IllegalArgumentException("Invalid yandex engine request")
        if (environment.network == null) yandexEngineFailure("yandex_network_required")
        val nativeSession = sessionFactory.create(yandex.config, environment)
        val active = ActiveYandexSession(nativeSession)
        synchronized(lock) {
            if (owner !in owners) yandexEngineFailure("yandex_owner_inactive")
            if (sessions.putIfAbsent(owner, active) != null) {
                yandexEngineFailure("yandex_session_already_active")
            }
        }
        return try {
            val proxy = nativeSession.prepare()
            val prepared = PreparedYandexEngineSession(
                owner = owner,
                request = yandex,
                environment = environment,
                proxy = proxy,
                nativeSession = nativeSession,
            )
            val accepted = synchronized(lock) {
                if (sessions[owner] === active && owner in owners) {
                    active.prepared = prepared
                    true
                } else {
                    false
                }
            }
            if (!accepted) {
                nativeSession.stop()
                yandexEngineFailure("yandex_owner_retired")
            }
            prepared
        } catch (error: CancellationException) {
            removeAndStop(owner, active)
            throw error
        } catch (error: TunnelEngineFailureException) {
            removeAndStop(owner, active)
            throw error
        } catch (_: Throwable) {
            removeAndStop(owner, active)
            yandexEngineFailure("yandex_prepare_failed")
        }
    }

    override suspend fun start(
        owner: Long,
        prepared: PreparedTunnelEngineSession,
        tun: TunnelFileDescriptorHandle,
    ): Long {
        val yandex = prepared as? PreparedYandexEngineSession
            ?: throw IllegalArgumentException("Invalid prepared yandex session")
        val active = synchronized(lock) {
            sessions[owner]?.takeIf {
                yandex.owner == owner &&
                    it.prepared === yandex &&
                    it.nativeSession === yandex.nativeSession
            }
        } ?: yandexEngineFailure("yandex_prepared_session_inactive")
        var startedXrayLease: Long? = null
        return try {
            active.nativeSession.start()
            val controller = object : libXray.DialerController {
                override fun protectFd(fd: Long): Boolean =
                    yandex.environment.unboundSocketProtector(fd)
            }
            val lease = xrayRuntime.start(
                owner = owner,
                configJson = yandex.request.configFactory.build(tun.borrowedFd, yandex.proxy),
                controller = controller,
                // The yandex proxy is an IP literal. Resolve through Xray's routed DNS;
                // a protected resolver would bypass the yandex on restricted mobile networks.
                dnsServer = null,
            )
            startedXrayLease = lease
            synchronized(lock) {
                if (sessions[owner] !== active || owner !in owners) {
                    yandexEngineFailure("yandex_owner_retired")
                }
                active.lease = lease
            }
            lease
        } catch (error: CancellationException) {
            startedXrayLease?.let { xrayRuntime.stop(owner, it) }
            active.nativeSession.stop()
            throw error
        } catch (error: TunnelEngineFailureException) {
            startedXrayLease?.let { xrayRuntime.stop(owner, it) }
            active.nativeSession.stop()
            throw error
        } catch (_: Throwable) {
            startedXrayLease?.let { xrayRuntime.stop(owner, it) }
            active.nativeSession.stop()
            yandexEngineFailure("yandex_start_failed")
        }
    }

    override fun stop(
        owner: Long,
        prepared: PreparedTunnelEngineSession?,
        lease: Long?,
    ) {
        val target = prepared as? PreparedYandexEngineSession
        val active = synchronized(lock) {
            val current = sessions[owner] ?: return@synchronized null
            if (target != null && current.prepared !== target) return@synchronized null
            if (lease != null && current.lease != null && current.lease != lease) {
                return@synchronized null
            }
            sessions.remove(owner)
        }
        active?.let { session ->
            xrayRuntime.stop(owner, session.lease ?: lease)
            session.nativeSession.stop()
        }
    }

    override suspend fun refresh(owner: Long, prepared: PreparedTunnelEngineSession, config: YandexServerConfig): Boolean {
        val target = prepared as? PreparedYandexEngineSession ?: return false
        val active = synchronized(lock) {
            sessions[owner]?.takeIf { owner in owners && target.owner == owner && it.prepared === target && it.lease != null }
        } ?: return false
        val refreshed = active.nativeSession.refresh(config)
        return refreshed && synchronized(lock) { owner in owners && sessions[owner] === active }
    }

    private fun removeAndStop(owner: Long, expected: ActiveYandexSession) {
        synchronized(lock) {
            if (sessions[owner] === expected) sessions.remove(owner)
        }
        expected.nativeSession.stop()
    }

    private data class ActiveYandexSession(
        val nativeSession: YandexNativeSession,
        var prepared: PreparedYandexEngineSession? = null,
        var lease: Long? = null,
    )
}

private data class PreparedYandexEngineSession(
    val owner: Long,
    val request: TunnelEngineRequest.Yandex,
    val environment: TunnelEngineEnvironment,
    val proxy: LocalProxyEndpoint,
    val nativeSession: YandexNativeSession,
) : PreparedTunnelEngineSession {
    override val engine: TunnelEngineKind = TunnelEngineKind.LEVIK_YANDEX
    override val tunPlan: TunPlan = request.tunPlan
}

internal class AndroidYandexNativeSessionFactory(private val executablePath: String) : YandexNativeSessionFactory {
    override fun create(config: YandexServerConfig, environment: TunnelEngineEnvironment): YandexNativeSession =
        AndroidYandexNativeSession(executablePath, config, environment)
}

private class AndroidYandexNativeSession(
    private val executablePath: String,
    private val config: YandexServerConfig,
    private val environment: TunnelEngineEnvironment,
) : YandexNativeSession {
    private val codec = YandexControlCodec()
    private val machine = YandexControlStateMachine(codec)
    private val stopping = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val backgroundFailure = AtomicReference<TunnelEngineFailureException?>(null)
    private val writeLock = Any()
    private val lifecycleLock = Any()
    private val deadlineLock = Any()
    private val refreshSequence = AtomicLong(0)
    private val pendingRefresh = AtomicReference<PendingYandexRefresh?>(null)
    private val random = SecureRandom()
    private val controlName = socketName("control")
    private val networkName = socketName("network")
    private val username = credential(18)
    private val password = credential(32)
    @Volatile private var deadline = runCatching {
        MonotonicCredentialDeadline.create(
            Instant.ofEpochSecond(minOf(config.bootstrap.expiresAt, config.bootstrap.providerAuth.validUntil)),
            Instant.now(),
            SystemClock.elapsedRealtime(),
        )
    }.getOrElse { yandexEngineFailure("yandex_credential_expired") }
    @Volatile private var process: java.lang.Process? = null
    private var executor: ExecutorService? = null
    private var dnsExecutor: ExecutorService? = null
    private var control: LocalSocket? = null
    private var network: LocalSocket? = null
    private var controlReader: YandexLocalSocketFrameReader? = null
    private var networkReader: YandexLocalSocketFrameReader? = null
    private var lastDiagnosticAt = 0L

    override suspend fun prepare(): LocalProxyEndpoint = withContext(Dispatchers.IO) {
        try {
            if (environment.network == null) yandexEngineFailure("yandex_network_required")
            checkDeadline()
            val executable = File(executablePath)
            if (!executable.isFile || !executable.canExecute()) yandexEngineFailure("yandex_native_missing")
            synchronized(lifecycleLock) {
                if (stopping.get()) yandexEngineFailure("yandex_session_stopping")
                process = yandexProcessBuilder(executable.absolutePath, controlName).start()
                process?.outputStream?.close()
                val pool = Executors.newFixedThreadPool(5) { task -> Thread(task, "levik-yandex-ipc").apply { isDaemon = true } }
                executor = pool
                dnsExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "levik-yandex-dns").apply { isDaemon = true } }
                process?.let { native -> pool.submit { discard(native.inputStream) }; pool.submit { discard(native.errorStream) } }
            }
            val prepareDeadline = minOf(deadline.deadlineElapsedMs, after(60_000))
            val socket = connect(controlName, minOf(prepareDeadline, after(10_000)))
            synchronized(lifecycleLock) {
                if (stopping.get()) { socket.close(); yandexEngineFailure("yandex_session_stopping") }
                control = socket
                controlReader = YandexLocalSocketFrameReader(socket, YANDEX_MAX_CONTROL_BYTES)
            }
            synchronized(writeLock) {
                writeFrame(socket, codec.encodeInit(YandexNativeInit(config.bootstrap, networkName, username, password)), YANDEX_MAX_CONTROL_BYTES)
            }
            var proxy: LocalProxyEndpoint? = null
            while (proxy == null) {
                currentCoroutineContext().ensureActive()
                when (val action = nextAction(prepareDeadline)) {
                    YandexControlAction.ConnectNetwork -> startNetwork(prepareDeadline)
                    is YandexControlAction.PreparedProxy -> proxy = LocalProxyEndpoint("127.0.0.1", action.port, username, password)
                    is YandexControlAction.NativeFailure -> yandexEngineFailure("yandex_native_${action.code}")
                    YandexControlAction.Running -> yandexEngineFailure("yandex_protocol_early_running")
                    YandexControlAction.Continue -> Unit
                    is YandexControlAction.Refreshed -> yandexEngineFailure("yandex_protocol_early_refresh")
                }
            }
            proxy
        } catch (error: CancellationException) {
            stop(); throw error
        } catch (error: TunnelEngineFailureException) {
            stop(); throw error
        } catch (error: YandexProtocolException) {
            stop(); yandexEngineFailure(error.stableCode)
        } catch (_: Throwable) {
            stop(); yandexEngineFailure("yandex_prepare_failed")
        }
    }

    override suspend fun start(): Unit = withContext(Dispatchers.IO) {
        try {
            checkFailure()
            checkDeadline()
            // Consume the strict native RUNNING acknowledgement before Xray
            // receives the service-owned, borrowed TUN descriptor.
            if (nextAction(minOf(deadline.deadlineElapsedMs, after(10_000))) != YandexControlAction.Running) {
                yandexEngineFailure("yandex_protocol_running")
            }
            val pool = executor ?: yandexEngineFailure("yandex_workers_missing")
            pool.submit {
                while (!stopping.get()) {
                    try {
                        when (val action = nextAction(Long.MAX_VALUE)) {
                            is YandexControlAction.NativeFailure -> { failBackground("yandex_native_${action.code}"); return@submit }
                            YandexControlAction.Continue -> Unit
                            is YandexControlAction.Refreshed -> completeRefresh(action.result)
                            else -> { failBackground("yandex_protocol_running"); return@submit }
                        }
                    } catch (_: Throwable) {
                        if (!stopping.get()) failBackground("yandex_control_failed")
                        return@submit
                    }
                }
            }
            pool.submit {
                while (!stopping.get()) {
                    val remaining = synchronized(deadlineLock) { deadline.remainingMillis(SystemClock.elapsedRealtime()) }
                    if (remaining <= 0) { failBackground("yandex_credential_expired"); return@submit }
                    try { Thread.sleep(minOf(remaining, 1000)) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@submit }
                }
            }
        } catch (error: CancellationException) { throw error
        } catch (error: TunnelEngineFailureException) { throw error
        } catch (error: YandexProtocolException) { yandexEngineFailure(error.stableCode)
        } catch (_: Throwable) { yandexEngineFailure("yandex_start_failed") }
    }

    override suspend fun refresh(config: YandexServerConfig): Boolean = withContext(Dispatchers.IO) {
        if (stopping.get() || !machine.running || backgroundFailure.get() != null ||
            !sameYandexSessionBinding(this@AndroidYandexNativeSession.config, config)
        ) return@withContext false
        val next = runCatching {
            val bootstrap = config.bootstrap
            YandexContract.validateProviderAuth(bootstrap.providerAuth)
            MonotonicCredentialDeadline.create(Instant.ofEpochSecond(minOf(bootstrap.expiresAt, bootstrap.providerAuth.validUntil)), Instant.now(), SystemClock.elapsedRealtime())
        }.getOrNull() ?: return@withContext false
        val pending = PendingYandexRefresh(refreshSequence.incrementAndGet(), config.bootstrap, next)
        if (!pendingRefresh.compareAndSet(null, pending)) return@withContext false
        var submitted = false
        try {
            val frame = codec.encodeRefresh(pending.id, config.bootstrap)
            synchronized(writeLock) {
                if (stopping.get()) return@synchronized
                checkDeadline()
                val socket = control ?: yandexEngineFailure("yandex_control_missing")
                // A failed write can still have delivered a complete local frame.
                // Retain its ID until ACK or channel termination rather than accepting a late ACK as unknown.
                submitted = true
                writeFrame(socket, frame, YANDEX_MAX_CONTROL_BYTES)
            }
            if (!submitted) return@withContext false
            // The sole control reader continues to process stats and terminal errors.
            // On caller timeout retain the pending ID until its ACK; never accept a stale ACK as a new request.
            withTimeoutOrNull(12_000) { pending.result.await() } ?: false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        } finally {
            if (!submitted) pendingRefresh.compareAndSet(pending, null)
        }
    }

    private fun completeRefresh(result: YandexRefreshResult) {
        val pending = pendingRefresh.get() ?: yandexEngineFailure("yandex_refresh_unexpected_ack")
        if (pending.id != result.requestId) yandexEngineFailure("yandex_refresh_request_mismatch")
        var accepted = false
        if (result.ok) {
            if (result.leaseExpiresAt != pending.bootstrap.expiresAt || result.validUntil != pending.bootstrap.providerAuth.validUntil) {
                yandexEngineFailure("yandex_refresh_deadline_mismatch")
            }
            accepted = synchronized(deadlineLock) {
                val now = SystemClock.elapsedRealtime()
                if (stopping.get() || deadline.isExpired(now) || pending.deadline.isExpired(now)) false
                else { deadline = pending.deadline; true }
            }
        }
        if (!pendingRefresh.compareAndSet(pending, null)) yandexEngineFailure("yandex_refresh_state")
        pending.result.complete(accepted)
    }

    override fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        pendingRefresh.getAndSet(null)?.result?.complete(false)
        synchronized(lifecycleLock) {
            runCatching { control?.let { synchronized(writeLock) { writeFrame(it, codec.encodeStop(), YANDEX_MAX_CONTROL_BYTES) } } }
            process?.let { native ->
                if (native.isAlive && !waitFor(native, 1500)) {
                    native.destroy()
                    if (native.isAlive && !waitFor(native, 500)) { native.destroyForcibly(); waitFor(native, 500) }
                }
            }
            if (!closed.compareAndSet(false, true)) return
            runCatching { network?.close() }
            runCatching { control?.close() }
            controlReader?.closePendingDescriptors()
            networkReader?.closePendingDescriptors()
            process?.let { native -> runCatching { native.inputStream.close() }; runCatching { native.errorStream.close() } }
            dnsExecutor?.shutdownNow()
            executor?.shutdownNow()
            process = null
        }
    }

    private fun startNetwork(prepareDeadline: Long) {
        if (network != null) yandexEngineFailure("yandex_network_duplicate")
        val socket = connect(networkName, minOf(prepareDeadline, after(10_000)))
        val reader = YandexLocalSocketFrameReader(socket, YANDEX_MAX_NETWORK_BYTES)
        synchronized(lifecycleLock) {
            if (stopping.get()) { socket.close(); yandexEngineFailure("yandex_session_stopping") }
            network = socket
            networkReader = reader
        }
        (executor ?: yandexEngineFailure("yandex_workers_missing")).submit {
            while (!stopping.get()) {
                val frame = try { reader.readFrame(Long.MAX_VALUE) { process?.isAlive == true } } catch (_: Throwable) {
                    if (!stopping.get()) failBackground("yandex_network_channel_failed")
                    return@submit
                }
                var pendingDescriptors = frame.fileDescriptors
                try {
                    when (val request = codec.decodeNetworkRequest(frame.payload)) {
                        is YandexNetworkRequest.Protect -> {
                            val descriptors = pendingDescriptors
                            pendingDescriptors = emptyList()
                            yandexProtectSocket(
                                descriptors,
                                environment.protector::protectAndBind,
                                ::closeYandexDescriptor,
                            ) { protected ->
                                // ACK follows both protect and selected Network bind.
                                writeFrame(socket, codec.encodeProtectAck(request.requestId, protected), YANDEX_MAX_NETWORK_BYTES)
                            }
                            // A valid negative ACK denies only this unconnected socket.
                            // The existing admitted carrier remains protected and usable.
                        }
                        is YandexNetworkRequest.Resolve -> {
                            if (frame.fileDescriptors.isNotEmpty()) yandexEngineFailure("yandex_resolve_unexpected_fd")
                            val selected = environment.network ?: yandexEngineFailure("yandex_network_required")
                            val pending = (dnsExecutor ?: yandexEngineFailure("yandex_dns_workers_missing")).submit<List<String>> {
                                selected.getAllByName(request.host).map { it.hostAddress ?: throw IOException() }.distinct()
                            }
                            val addresses = try { pending.get(5, TimeUnit.SECONDS) } catch (_: Throwable) { pending.cancel(true); null }
                            writeFrame(socket, codec.encodeResolveAck(request.requestId, addresses), YANDEX_MAX_NETWORK_BYTES)
                            // A failed selected-network lookup has a complete, correlated
                            // negative ACK. Keep IPC aligned for a later refresh/reconnect.
                        }
                    }
                } catch (_: Throwable) {
                    closeYandexDescriptors(pendingDescriptors)
                    failBackground("yandex_protocol_network")
                    return@submit
                }
            }
        }
    }

    private fun nextAction(limit: Long): YandexControlAction {
        checkFailure()
        val frame = (controlReader ?: yandexEngineFailure("yandex_control_missing")).readFrame(limit) { process?.isAlive == true }
        if (frame.fileDescriptors.isNotEmpty()) { closeYandexDescriptors(frame.fileDescriptors); yandexEngineFailure("yandex_control_unexpected_fd") }
        val event = codec.decodeEvent(frame.payload)
        if (BuildConfig.DEBUG && event.type in setOf("stats", "status") &&
            SystemClock.elapsedRealtime() - lastDiagnosticAt >= 30_000
        ) {
            lastDiagnosticAt = SystemClock.elapsedRealtime()
            val status = codec.status(event)
            AppLogger.d("YandexTunnel", "Carrier session=${status.strictSession} " +
                "proxyBytes=${status.bytesUp}/${status.bytesDown} " +
                "proxy=${status.proxy.connections}/${status.proxy.udpAssociations}/${status.proxy.rejected} " +
                "carrierPackets=${status.carrierPacketsUp}/${status.carrierPacketsDown} " +
                "protected=${status.protectedExternalSockets} resolved=${status.resolvedProviderHosts} " +
                "ip=${status.dataPath.dataSent}/${status.dataPath.dataReceived} " +
                "drops=${status.dataPath.dataDropped} decode=${status.dataPath.decodeErrors} " +
                "crypto=${status.dataPath.cryptoRejected} batch=${status.dataPath.batchSendErrors}")
        }
        return machine.accept(event)
    }

    private fun connect(name: String, limit: Long): LocalSocket {
        while (SystemClock.elapsedRealtime() < limit) {
            checkFailure()
            checkDeadline()
            if (stopping.get() || process?.isAlive != true) yandexEngineFailure("yandex_process_exited")
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(name.removePrefix("@"), LocalSocketAddress.Namespace.ABSTRACT))
                if (socket.peerCredentials.uid != Process.myUid()) yandexEngineFailure("yandex_peer_uid_mismatch")
                socket.soTimeout = 1000
                return socket
            } catch (error: TunnelEngineFailureException) { runCatching { socket.close() }; throw error
            } catch (_: Throwable) { runCatching { socket.close() }; Thread.sleep(25) }
        }
        yandexEngineFailure("yandex_channel_timeout")
    }

    private fun writeFrame(socket: LocalSocket, payload: String, maximum: Int) {
        val bytes = payload.encodeToByteArray()
        if (bytes.isEmpty() || bytes.size + 1 > maximum || bytes.any { it == 0.toByte() || it == '\n'.code.toByte() }) yandexEngineFailure("yandex_protocol_size")
        socket.outputStream.write(bytes)
        socket.outputStream.write('\n'.code)
        socket.outputStream.flush()
    }

    private fun failBackground(code: String) {
        if (stopping.get()) return
        val first = backgroundFailure.compareAndSet(null, TunnelEngineFailureException(code))
        pendingRefresh.getAndSet(null)?.result?.complete(false)
        runCatching { network?.close() }
        runCatching { control?.close() }
        networkReader?.closePendingDescriptors()
        controlReader?.closePendingDescriptors()
        runCatching { process?.destroy() }
        if (first && machine.running) environment.terminalFailureHandler(code)
    }
    private fun checkFailure() { backgroundFailure.get()?.let { throw it } }
    private fun checkDeadline() { if (deadline.isExpired(SystemClock.elapsedRealtime())) yandexEngineFailure("yandex_credential_expired") }
    private fun socketName(role: String): String = "@levik_ydx_${role}_${credential(18)}"
    private fun credential(size: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(size).also(random::nextBytes))
    private fun after(duration: Long): Long = SystemClock.elapsedRealtime() + duration
    private fun waitFor(native: java.lang.Process, duration: Long): Boolean = try { native.waitFor(duration, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    private fun discard(stream: java.io.InputStream) {
        runCatching { stream.use { input -> val buffer = ByteArray(8192); while (!closed.get() && input.read(buffer) >= 0) Unit } }
    }
}

private class PendingYandexRefresh(val id: Long, val bootstrap: YandexBootstrap, val deadline: MonotonicCredentialDeadline) {
    val result = CompletableDeferred<Boolean>()
}

internal fun sameYandexSessionBinding(current: YandexServerConfig, next: YandexServerConfig): Boolean {
    return yandexProfileRefreshMode(current, next) == YandexProfileRefreshMode.HOT_REFRESH
}


private data class YandexLocalSocketFrame(
    val payload: String,
    val fileDescriptors: List<FileDescriptor>,
)

/**
 * Reads one byte at a time so Android's ancillary-descriptor snapshot cannot be detached from the
 * JSON record it accompanied. Partial frames survive SO_RCVTIMEO wake-ups.
 */
private class YandexLocalSocketFrameReader(
    private val socket: LocalSocket,
    private val maxPayloadBytes: Int,
) {
    private val input = socket.inputStream
    private val payload = ByteArrayOutputStream()
    private val pendingDescriptors = mutableListOf<FileDescriptor>()
    private var readerClosed = false // Guarded by pendingDescriptors, including late ancillary delivery.

    fun readFrame(
        deadlineElapsedMs: Long,
        peerAlive: () -> Boolean,
    ): YandexLocalSocketFrame {
        while (deadlineElapsedMs == Long.MAX_VALUE || SystemClock.elapsedRealtime() < deadlineElapsedMs) {
            if (synchronized(pendingDescriptors) { readerClosed }) throw EOFException("yandex reader closed")
            val value = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                if (!peerAlive()) {
                    closePendingDescriptors()
                    throw EOFException("yandex peer exited")
                }
                continue
            } catch (error: IOException) {
                // LocalSocket reports SO_RCVTIMEO as IOException(EAGAIN) on some Android
                // releases instead of SocketTimeoutException. Treat only those kernel timeout
                // errnos as a polling wake-up; every other I/O failure remains terminal.
                if (!error.isLocalSocketReadTimeout()) throw error
                if (!peerAlive()) {
                    closePendingDescriptors()
                    throw EOFException("yandex peer exited")
                }
                continue
            }
            socket.ancillaryFileDescriptors?.let { descriptors ->
                val rejected = synchronized(pendingDescriptors) {
                    if (readerClosed) true else { pendingDescriptors += descriptors; false }
                }
                if (rejected) {
                    closeYandexDescriptors(descriptors.asList())
                    throw EOFException("yandex reader closed")
                }
                if (synchronized(pendingDescriptors) { pendingDescriptors.size > MAX_ANCILLARY_DESCRIPTORS }) {
                    closePendingDescriptors()
                    throw IOException("too many ancillary descriptors")
                }
            }
            if (value < 0) {
                closePendingDescriptors()
                throw EOFException("yandex socket closed")
            }
            if (value == '\n'.code) {
                val bytes = payload.toByteArray()
                payload.reset()
                if (bytes.isEmpty()) {
                    closePendingDescriptors()
                    throw IOException("empty yandex frame")
                }
                val text = try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                } catch (_: Throwable) {
                    closePendingDescriptors()
                    throw IOException("invalid yandex UTF-8")
                }
                val descriptors = synchronized(pendingDescriptors) {
                    pendingDescriptors.toList().also { pendingDescriptors.clear() }
                }
                return YandexLocalSocketFrame(text, descriptors)
            }
            payload.write(value)
            if (payload.size() > maxPayloadBytes) {
                closePendingDescriptors()
                payload.reset()
                throw IOException("yandex frame too large")
            }
        }
        closePendingDescriptors()
        throw SocketTimeoutException("yandex frame timeout")
    }

    fun closePendingDescriptors() {
        val descriptors = synchronized(pendingDescriptors) {
            readerClosed = true
            pendingDescriptors.toList().also { pendingDescriptors.clear() }
        }
        closeYandexDescriptors(descriptors)
        payload.reset()
    }

    private companion object {
        const val MAX_ANCILLARY_DESCRIPTORS = 8
    }
}


internal fun yandexProcessBuilder(executablePath: String, controlSocketName: String): ProcessBuilder {
    require(executablePath.isNotBlank())
    require(controlSocketName.matches(Regex("@levik_ydx_[A-Za-z0-9_-]{16,90}")))
    return ProcessBuilder(executablePath, "--control-sock", controlSocketName).also { it.environment().clear() }
}

private fun yandexEngineFailure(code: String): Nothing = throw TunnelEngineFailureException(code)

private fun IOException.isLocalSocketReadTimeout(): Boolean {
    if (message == Os.strerror(OsConstants.EAGAIN)) return true
    var current: Throwable? = this
    while (current != null) {
        val errno = (current as? ErrnoException)?.errno
        if (errno == OsConstants.EAGAIN || errno == OsConstants.ETIMEDOUT) return true
        current = current.cause
    }
    return false
}

private fun closeYandexDescriptors(descriptors: Iterable<FileDescriptor>) {
    descriptors.forEach(::closeYandexDescriptor)
}

private fun closeYandexDescriptor(descriptor: FileDescriptor) { runCatching { Os.close(descriptor) } }

/** Owns received SCM_RIGHTS copies only; never the helper's original socket. */
internal fun yandexProtectSocket(
    descriptors: List<FileDescriptor>,
    protectAndBind: (FileDescriptor) -> Boolean,
    close: (FileDescriptor) -> Unit,
    acknowledge: (Boolean) -> Unit,
): Boolean {
    if (descriptors.size != 1) {
        descriptors.forEach(close)
        throw YandexProtocolException("yandex_protect_fd_count")
    }
    val descriptor = descriptors.single()
    val success = try { protectAndBind(descriptor) } catch (_: Throwable) { false } finally { close(descriptor) }
    acknowledge(success)
    return success
}
