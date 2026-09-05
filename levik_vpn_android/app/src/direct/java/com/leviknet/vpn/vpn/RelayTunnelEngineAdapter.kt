package com.leviknet.vpn.vpn

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun interface RelayNativeSessionFactory {
    fun create(
        config: RelayServerConfig,
        environment: TunnelEngineEnvironment,
    ): RelayNativeSession
}

internal fun interface RelayVkTurnProvider {
    fun obtain(hash: String): RelayTurnCredentials
}

internal interface RelayNativeSession {
    suspend fun prepare(): LocalProxyEndpoint

    suspend fun start()

    fun stop()
}

internal class RelayTunnelEngineAdapter(
    private val sessionFactory: RelayNativeSessionFactory,
    private val xrayRuntime: XrayRuntime,
) : TunnelEngineAdapter {
    override val kind: TunnelEngineKind = TunnelEngineKind.LEVIK_RELAY
    private val lock = Any()
    private val owners = mutableSetOf<Long>()
    private val sessions = mutableMapOf<Long, ActiveRelaySession>()

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
        val relay = request as? TunnelEngineRequest.Relay
            ?: throw IllegalArgumentException("Invalid relay engine request")
        if (environment.network == null) engineFailure("relay_network_required")
        val nativeSession = sessionFactory.create(relay.config, environment)
        val active = ActiveRelaySession(nativeSession)
        synchronized(lock) {
            if (owner !in owners) engineFailure("relay_owner_inactive")
            if (sessions.putIfAbsent(owner, active) != null) {
                engineFailure("relay_session_already_active")
            }
        }
        return try {
            val proxy = nativeSession.prepare()
            val prepared = PreparedRelayEngineSession(
                owner = owner,
                request = relay,
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
                engineFailure("relay_owner_retired")
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
            engineFailure("relay_prepare_failed")
        }
    }

    override suspend fun start(
        owner: Long,
        prepared: PreparedTunnelEngineSession,
        tun: TunnelFileDescriptorHandle,
    ): Long {
        val relay = prepared as? PreparedRelayEngineSession
            ?: throw IllegalArgumentException("Invalid prepared relay session")
        val active = synchronized(lock) {
            sessions[owner]?.takeIf {
                relay.owner == owner &&
                    it.prepared === relay &&
                    it.nativeSession === relay.nativeSession
            }
        } ?: engineFailure("relay_prepared_session_inactive")
        var startedXrayLease: Long? = null
        return try {
            active.nativeSession.start()
            val controller = object : libXray.DialerController {
                override fun protectFd(fd: Long): Boolean =
                    relay.environment.protector.protectAndBind(fd)
            }
            val lease = xrayRuntime.start(
                owner = owner,
                configJson = relay.request.configFactory.build(tun.borrowedFd, relay.proxy),
                controller = controller,
                dnsServer = relay.environment.dnsServer,
            )
            startedXrayLease = lease
            synchronized(lock) {
                if (sessions[owner] !== active || owner !in owners) {
                    engineFailure("relay_owner_retired")
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
            engineFailure("relay_start_failed")
        }
    }

    override fun stop(
        owner: Long,
        prepared: PreparedTunnelEngineSession?,
        lease: Long?,
    ) {
        val target = prepared as? PreparedRelayEngineSession
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

    private fun removeAndStop(owner: Long, expected: ActiveRelaySession) {
        synchronized(lock) {
            if (sessions[owner] === expected) sessions.remove(owner)
        }
        expected.nativeSession.stop()
    }

    private data class ActiveRelaySession(
        val nativeSession: RelayNativeSession,
        var prepared: PreparedRelayEngineSession? = null,
        var lease: Long? = null,
    )
}

private data class PreparedRelayEngineSession(
    val owner: Long,
    val request: TunnelEngineRequest.Relay,
    val environment: TunnelEngineEnvironment,
    val proxy: LocalProxyEndpoint,
    val nativeSession: RelayNativeSession,
) : PreparedTunnelEngineSession {
    override val engine: TunnelEngineKind = TunnelEngineKind.LEVIK_RELAY
    override val tunPlan: TunPlan = request.tunPlan
}

internal class AndroidRelayNativeSessionFactory(
    private val executablePath: String,
    private val vkTurnProvider: RelayVkTurnProvider,
) : RelayNativeSessionFactory {
    override fun create(
        config: RelayServerConfig,
        environment: TunnelEngineEnvironment,
    ): RelayNativeSession = AndroidRelayNativeSession(
        executablePath = executablePath,
        config = config,
        environment = environment,
        vkTurnProvider = vkTurnProvider,
    )
}

private class AndroidRelayNativeSession(
    private val executablePath: String,
    private val config: RelayServerConfig,
    private val environment: TunnelEngineEnvironment,
    private val vkTurnProvider: RelayVkTurnProvider,
) : RelayNativeSession {
    private val codec = RelayControlCodec()
    private val stateMachine = RelayControlStateMachine(codec)
    private val stopping = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val backgroundFailure = AtomicReference<TunnelEngineFailureException?>(null)
    private val controlWriteLock = Any()
    private val secureRandom = SecureRandom()
    private val observedNativeDiagnostics = ConcurrentHashMap.newKeySet<String>()
    private val controlSocketName = randomSocketName("control")
    private val protectSocketName = randomSocketName("protect")
    private val credentialDeadline = runCatching {
        MonotonicCredentialDeadline.create(
            expiresAt = Instant.parse(config.bootstrap.expiresAt),
            wallClockNow = Instant.now(),
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        )
    }.getOrElse { engineFailure("relay_credential_expired") }
    private val proxyUsername = randomCredential(PROXY_USERNAME_BYTES)
    private val proxyPassword = randomCredential(PROXY_PASSWORD_BYTES)
    private var process: java.lang.Process? = null
    private var executor: ExecutorService? = null
    private val workers = mutableListOf<Future<*>>()
    private var controlSocket: LocalSocket? = null
    private var controlReader: LocalSocketFrameReader? = null
    private var protectSocket: LocalSocket? = null
    private var protectReader: LocalSocketFrameReader? = null

    override suspend fun prepare(): LocalProxyEndpoint = withContext(Dispatchers.IO) {
        try {
            startProcess()
            val prepareDeadline = minOf(
                deadlineAfter(PREPARE_TIMEOUT_MS),
                credentialDeadline.deadlineElapsedMs,
            )
            if (credentialDeadline.isExpired(SystemClock.elapsedRealtime())) {
                engineFailure("relay_credential_expired")
            }
            val control = connectAbstract(
                socketName = controlSocketName,
                deadlineElapsedMs = minOf(prepareDeadline, deadlineAfter(CHANNEL_CONNECT_TIMEOUT_MS)),
                timeoutCode = "relay_control_connect_timeout",
            )
            controlSocket = control
            controlReader = LocalSocketFrameReader(control, RELAY_MAX_CONTROL_MESSAGE_BYTES)
            val init = RelayNativeInit(
                peer = peerAddress(config.node.host, config.node.port),
                turnHashes = config.node.turnHashes,
                accessToken = config.bootstrap.accessToken,
                deviceId = config.bootstrap.deviceId,
                workers = RELAY_WORKERS,
                turnFrontSni = config.node.turnFrontSni,
                protectFdSocket = protectSocketName,
                serverPublicKey = config.node.serverPublicKey,
                vkAuthMode = "account",
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword,
            )
            writeControl(codec.encodeInit(init))
            stateMachine.markInitSent()

            var preparedProxy: LocalProxyEndpoint? = null
            while (preparedProxy == null) {
                currentCoroutineContext().ensureActive()
                when (val action = nextControlAction(prepareDeadline)) {
                    RelayControlAction.ConnectProtectChannel -> startProtectChannel(prepareDeadline)
                    is RelayControlAction.RequestVkAuth -> provideVkCredentials(action.request)
                    is RelayControlAction.PreparedProxy -> preparedProxy = LocalProxyEndpoint(
                        address = action.address,
                        port = action.port,
                        username = proxyUsername,
                        password = proxyPassword,
                    )
                    RelayControlAction.Continue -> Unit
                    is RelayControlAction.Diagnostic -> logNativeDiagnostic(action.code)
                    is RelayControlAction.NativeFailure ->
                        engineFailure(nativeErrorCode(action.code))
                    RelayControlAction.Running ->
                        engineFailure("relay_protocol_running_during_prepare")
                }
            }
            preparedProxy
        } catch (error: CancellationException) {
            stop()
            throw error
        } catch (error: RelayProtocolException) {
            stop()
            engineFailure(error.stableCode)
        } catch (error: TunnelEngineFailureException) {
            stop()
            throw error
        } catch (_: Throwable) {
            stop()
            engineFailure("relay_prepare_io")
        }
    }

    override suspend fun start(): Unit = withContext(Dispatchers.IO) {
        try {
            checkBackgroundFailure()
            if (credentialDeadline.isExpired(SystemClock.elapsedRealtime())) {
                engineFailure("relay_credential_expired")
            }
            startControlMonitor()
            startCredentialExpiryMonitor()
        } catch (error: CancellationException) {
            throw error
        } catch (error: RelayProtocolException) {
            engineFailure(error.stableCode)
        } catch (error: TunnelEngineFailureException) {
            throw error
        } catch (_: Throwable) {
            engineFailure("relay_start_io")
        }
    }

    override fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        val sendStop = runCatching { stateMachine.beginStop() }.getOrDefault(false)
        if (sendStop) {
            runCatching { writeControl(codec.encodeStop()) }
        }
        terminateProcess()
        closeResources()
        stateMachine.markStopped()
    }

    private fun startProcess() {
        val executable = File(executablePath)
        if (!executable.isFile || !executable.canExecute()) {
            engineFailure("relay_native_missing")
        }
        val builder = relayProcessBuilder(executable.absolutePath, controlSocketName)
        val nativeProcess = try {
            builder.start()
        } catch (_: IOException) {
            engineFailure("relay_process_start_failed")
        }
        process = nativeProcess
        runCatching { nativeProcess.outputStream.close() }
        val workerPool = Executors.newFixedThreadPool(
            IPC_WORKER_COUNT,
        ) { task ->
            Thread(task, "levik-relay-ipc").apply { isDaemon = true }
        }
        executor = workerPool
        synchronized(workers) {
            workers += workerPool.submit { discard(nativeProcess.inputStream) }
            workers += workerPool.submit { discard(nativeProcess.errorStream) }
        }
    }

    private fun startProtectChannel(prepareDeadline: Long) {
        if (protectSocket != null) engineFailure("relay_protect_duplicate")
        val socket = connectAbstract(
            socketName = protectSocketName,
            deadlineElapsedMs = minOf(prepareDeadline, deadlineAfter(CHANNEL_CONNECT_TIMEOUT_MS)),
            timeoutCode = "relay_protect_connect_timeout",
        )
        protectSocket = socket
        val reader = LocalSocketFrameReader(socket, MAX_PROTECT_MESSAGE_BYTES)
        protectReader = reader
        val pool = executor ?: engineFailure("relay_worker_pool_unavailable")
        synchronized(workers) {
            workers += pool.submit { runProtectLoop(socket, reader) }
        }
    }

    private fun runProtectLoop(
        socket: LocalSocket,
        reader: LocalSocketFrameReader,
    ) {
        while (!closed.get()) {
            val frame = try {
                reader.readFrame(Long.MAX_VALUE) { process?.isAlive == true || stopping.get() }
            } catch (_: Throwable) {
                if (!stopping.get()) recordBackgroundFailure("relay_protect_channel_failed")
                return
            }
            val request = try {
                codec.decodeProtectRequest(frame.payload)
            } catch (_: Throwable) {
                closeDescriptors(frame.fileDescriptors)
                recordBackgroundFailure("relay_protocol_protect_request")
                return
            }
            if (frame.fileDescriptors.size != 1) {
                closeDescriptors(frame.fileDescriptors)
                recordBackgroundFailure("relay_protect_fd_count")
                return
            }
            val descriptor = frame.fileDescriptors.single()
            val protected = try {
                environment.protector.protectAndBind(descriptor)
            } catch (_: Throwable) {
                false
            } finally {
                closeDescriptor(descriptor)
            }
            try {
                // A positive ACK is emitted only after both VpnService.protect and bindSocket
                // succeeded. A negative ACK contains only a stable code and terminates the session.
                writeFrame(socket, codec.encodeProtectAck(request.requestId, protected))
            } catch (_: Throwable) {
                recordBackgroundFailure("relay_protect_ack_failed")
                return
            }
            if (!protected) {
                recordBackgroundFailure("relay_protect_bind_failed")
                return
            }
        }
    }

    private fun nextControlAction(deadlineElapsedMs: Long): RelayControlAction {
        checkBackgroundFailure()
        val reader = controlReader ?: engineFailure("relay_control_unavailable")
        val frame = try {
            reader.readFrame(deadlineElapsedMs) { process?.isAlive == true }
        } catch (_: SocketTimeoutException) {
            if (credentialDeadline.isExpired(SystemClock.elapsedRealtime())) {
                engineFailure("relay_credential_expired")
            }
            engineFailure("relay_prepare_timeout")
        } catch (_: EOFException) {
            checkBackgroundFailure()
            engineFailure("relay_process_exited")
        } catch (error: Throwable) {
            checkBackgroundFailure()
            AppLogger.w(
                RELAY_LOG_TAG,
                "Control channel read failed (${error.javaClass.simpleName}); " +
                    "nativeAlive=${process?.isAlive == true}",
            )
            engineFailure("relay_control_read_failed")
        }
        if (frame.fileDescriptors.isNotEmpty()) {
            closeDescriptors(frame.fileDescriptors)
            engineFailure("relay_control_unexpected_fd")
        }
        return stateMachine.accept(codec.decodeEvent(frame.payload))
    }

    private fun startControlMonitor() {
        val pool = executor ?: engineFailure("relay_worker_pool_unavailable")
        val reader = controlReader ?: engineFailure("relay_control_unavailable")
        synchronized(workers) {
            workers += pool.submit {
                while (!closed.get() && !stopping.get()) {
                    val frame = try {
                        reader.readFrame(Long.MAX_VALUE) { process?.isAlive == true }
                    } catch (_: Throwable) {
                        if (!stopping.get()) recordBackgroundFailure("relay_control_monitor_failed")
                        return@submit
                    }
                    if (frame.fileDescriptors.isNotEmpty()) {
                        closeDescriptors(frame.fileDescriptors)
                        recordBackgroundFailure("relay_control_unexpected_fd")
                        return@submit
                    }
                    val action = try {
                        stateMachine.accept(codec.decodeEvent(frame.payload))
                    } catch (_: Throwable) {
                        recordBackgroundFailure("relay_protocol_running_event")
                        return@submit
                    }
                    if (action is RelayControlAction.NativeFailure) {
                        recordBackgroundFailure(nativeErrorCode(action.code))
                        return@submit
                    }
                    if (action is RelayControlAction.Diagnostic) {
                        logNativeDiagnostic(action.code)
                        continue
                    }
                    if (action is RelayControlAction.RequestVkAuth) {
                        try {
                            provideVkCredentials(action.request)
                        } catch (_: Throwable) {
                            recordBackgroundFailure("relay_vk_auth_failed")
                            return@submit
                        }
                    }
                }
            }
        }
    }

    private fun startCredentialExpiryMonitor() {
        val pool = executor ?: engineFailure("relay_worker_pool_unavailable")
        synchronized(workers) {
            workers += pool.submit {
                while (!closed.get() && !stopping.get()) {
                    val remaining = credentialDeadline.remainingMillis(SystemClock.elapsedRealtime())
                    if (remaining <= 0L) {
                        recordBackgroundFailure("relay_credential_expired")
                        return@submit
                    }
                    try {
                        Thread.sleep(minOf(remaining, EXPIRY_POLL_MAX_MS))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@submit
                    }
                }
            }
        }
    }

    private fun connectAbstract(
        socketName: String,
        deadlineElapsedMs: Long,
        timeoutCode: String,
    ): LocalSocket {
        while (SystemClock.elapsedRealtime() < deadlineElapsedMs) {
            if (stopping.get()) engineFailure("relay_session_stopping")
            if (process?.isAlive != true) engineFailure("relay_process_exited")
            val socket = LocalSocket()
            try {
                socket.connect(
                    LocalSocketAddress(
                        socketName.removePrefix("@"),
                        LocalSocketAddress.Namespace.ABSTRACT,
                    ),
                )
                verifyPeerUid(socket)
                socket.soTimeout = SOCKET_READ_TIMEOUT_MS
                return socket
            } catch (error: TunnelEngineFailureException) {
                socket.closeQuietly()
                throw error
            } catch (_: Throwable) {
                socket.closeQuietly()
                try {
                    Thread.sleep(CONNECT_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    engineFailure("relay_session_stopping")
                }
            }
        }
        if (credentialDeadline.isExpired(SystemClock.elapsedRealtime())) {
            engineFailure("relay_credential_expired")
        }
        engineFailure(timeoutCode)
    }

    private fun verifyPeerUid(socket: LocalSocket) {
        val credential = try {
            // LocalSocket exposes the kernel SO_PEERCRED result through the public Android API.
            socket.peerCredentials
        } catch (_: Throwable) {
            engineFailure("relay_peer_credentials_failed")
        }
        if (credential.uid != Process.myUid()) engineFailure("relay_peer_uid_mismatch")
    }

    private fun writeControl(payload: String) {
        val socket = controlSocket ?: engineFailure("relay_control_unavailable")
        synchronized(controlWriteLock) {
            writeFrame(socket, payload)
        }
    }

    private fun provideVkCredentials(request: RelayVkAuthRequest) {
        val credentials = try {
            vkTurnProvider.obtain(request.hash)
        } catch (_: Throwable) {
            engineFailure("relay_vk_auth_failed")
        }
        writeControl(codec.encodeTurnCredentials(request, credentials))
    }

    private fun writeFrame(socket: LocalSocket, payload: String) {
        val bytes = payload.encodeToByteArray()
        if (bytes.isEmpty() || bytes.size > RELAY_MAX_CONTROL_MESSAGE_BYTES || bytes.any { it == 0.toByte() }) {
            engineFailure("relay_control_write_invalid")
        }
        socket.outputStream.write(bytes)
        socket.outputStream.write('\n'.code)
        socket.outputStream.flush()
    }

    private fun recordBackgroundFailure(code: String) {
        if (stopping.get()) return
        val firstFailure = backgroundFailure.compareAndSet(null, TunnelEngineFailureException(code))
        runCatching { controlSocket?.close() }
        runCatching { process?.destroy() }
        if (firstFailure && stateMachine.state == RelayControlState.RUNNING) {
            environment.terminalFailureHandler(code)
        }
    }

    private fun checkBackgroundFailure() {
        backgroundFailure.get()?.let { throw it }
    }

    private fun terminateProcess() {
        val nativeProcess = process ?: return
        if (nativeProcess.isAlive && !waitFor(nativeProcess, STOP_GRACEFUL_TIMEOUT_MS)) {
            runCatching { nativeProcess.destroy() }
            if (nativeProcess.isAlive && !waitFor(nativeProcess, STOP_DESTROY_TIMEOUT_MS)) {
                runCatching { nativeProcess.destroyForcibly() }
                waitFor(nativeProcess, STOP_FORCIBLE_TIMEOUT_MS)
            }
        }
    }

    private fun closeResources() {
        if (!closed.compareAndSet(false, true)) return
        protectReader?.closePendingDescriptors()
        controlReader?.closePendingDescriptors()
        protectSocket.closeQuietly()
        controlSocket.closeQuietly()
        val nativeProcess = process
        runCatching { nativeProcess?.outputStream?.close() }
        runCatching { nativeProcess?.inputStream?.close() }
        runCatching { nativeProcess?.errorStream?.close() }
        val pool = executor
        pool?.shutdownNow()
        runCatching { pool?.awaitTermination(WORKER_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        synchronized(workers) {
            workers.forEach { future -> runCatching { future.get(10, TimeUnit.MILLISECONDS) } }
            workers.clear()
        }
        process = null
        executor = null
        protectSocket = null
        controlSocket = null
        protectReader = null
        controlReader = null
    }

    private fun waitFor(nativeProcess: java.lang.Process, timeoutMs: Long): Boolean = try {
        nativeProcess.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun discard(stream: java.io.InputStream) {
        try {
            stream.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (!closed.get() && input.read(buffer) >= 0) {
                    // Native output is intentionally discarded because provider diagnostics may
                    // contain TURN URLs or credentials.
                }
            }
        } catch (_: Throwable) {
            // Closing the process streams is the normal shutdown path.
        }
    }

    private fun logNativeDiagnostic(code: String) {
        if (observedNativeDiagnostics.add(code)) {
            AppLogger.w(RELAY_LOG_TAG, "Native relay diagnostic: $code")
        }
    }

    private fun randomSocketName(role: String): String {
        val random = ByteArray(SOCKET_RANDOM_BYTES).also(secureRandom::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(random)
        return "@levik_wlr_${role}_$token"
    }

    private fun randomCredential(size: Int): String {
        val random = ByteArray(size).also(secureRandom::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random)
    }

    private fun peerAddress(host: String, port: Int): String =
        if (host.contains(':') && !host.startsWith('[')) "[$host]:$port" else "$host:$port"

    private fun deadlineAfter(durationMs: Long): Long =
        SystemClock.elapsedRealtime().let { now ->
            if (Long.MAX_VALUE - now < durationMs) Long.MAX_VALUE else now + durationMs
        }

    private companion object {
        const val RELAY_LOG_TAG = "LevikRelay"
        const val RELAY_WORKERS = 18
        const val IPC_WORKER_COUNT = 5
        const val SOCKET_RANDOM_BYTES = 18
        const val SOCKET_READ_TIMEOUT_MS = 1_000
        const val CONNECT_RETRY_DELAY_MS = 25L
        const val CHANNEL_CONNECT_TIMEOUT_MS = 10_000L
        const val PREPARE_TIMEOUT_MS = 120_000L
        const val STOP_GRACEFUL_TIMEOUT_MS = 3_000L
        const val STOP_DESTROY_TIMEOUT_MS = 1_000L
        const val STOP_FORCIBLE_TIMEOUT_MS = 1_000L
        const val WORKER_SHUTDOWN_TIMEOUT_MS = 1_000L
        const val MAX_PROTECT_MESSAGE_BYTES = 8 * 1024
        const val EXPIRY_POLL_MAX_MS = 1_000L
        const val PROXY_USERNAME_BYTES = 18
        const val PROXY_PASSWORD_BYTES = 32
    }
}

private data class LocalSocketFrame(
    val payload: String,
    val fileDescriptors: List<FileDescriptor>,
)

/**
 * Reads one byte at a time so Android's ancillary-descriptor snapshot cannot be detached from the
 * JSON record it accompanied. Partial frames survive SO_RCVTIMEO wake-ups.
 */
private class LocalSocketFrameReader(
    private val socket: LocalSocket,
    private val maxPayloadBytes: Int,
) {
    private val input = socket.inputStream
    private val payload = ByteArrayOutputStream()
    private val pendingDescriptors = mutableListOf<FileDescriptor>()

    fun readFrame(
        deadlineElapsedMs: Long,
        peerAlive: () -> Boolean,
    ): LocalSocketFrame {
        while (deadlineElapsedMs == Long.MAX_VALUE || SystemClock.elapsedRealtime() < deadlineElapsedMs) {
            val value = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                if (!peerAlive()) {
                    closePendingDescriptors()
                    throw EOFException("relay peer exited")
                }
                continue
            } catch (error: IOException) {
                // LocalSocket reports SO_RCVTIMEO as IOException(EAGAIN) on some Android
                // releases instead of SocketTimeoutException. Treat only those kernel timeout
                // errnos as a polling wake-up; every other I/O failure remains terminal.
                if (!error.isLocalSocketReadTimeout()) throw error
                if (!peerAlive()) {
                    closePendingDescriptors()
                    throw EOFException("relay peer exited")
                }
                continue
            }
            socket.ancillaryFileDescriptors?.let { descriptors ->
                pendingDescriptors += descriptors
                if (pendingDescriptors.size > MAX_ANCILLARY_DESCRIPTORS) {
                    closePendingDescriptors()
                    throw IOException("too many ancillary descriptors")
                }
            }
            if (value < 0) {
                closePendingDescriptors()
                throw EOFException("relay socket closed")
            }
            if (value == '\n'.code) {
                val bytes = payload.toByteArray()
                payload.reset()
                if (bytes.isEmpty()) {
                    closePendingDescriptors()
                    throw IOException("empty relay frame")
                }
                val text = try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                } catch (_: Throwable) {
                    closePendingDescriptors()
                    throw IOException("invalid relay UTF-8")
                }
                val descriptors = pendingDescriptors.toList()
                pendingDescriptors.clear()
                return LocalSocketFrame(text, descriptors)
            }
            payload.write(value)
            if (payload.size() > maxPayloadBytes) {
                closePendingDescriptors()
                payload.reset()
                throw IOException("relay frame too large")
            }
        }
        closePendingDescriptors()
        throw SocketTimeoutException("relay frame timeout")
    }

    fun closePendingDescriptors() {
        closeDescriptors(pendingDescriptors)
        pendingDescriptors.clear()
        payload.reset()
    }

    private companion object {
        const val MAX_ANCILLARY_DESCRIPTORS = 8
    }
}

private fun nativeErrorCode(code: String): String = "relay_native_$code".take(96)

/** The only native argv/env construction point; intentionally contains no profile material. */
internal fun relayProcessBuilder(
    executablePath: String,
    controlSocketName: String,
): ProcessBuilder {
    require(executablePath.isNotBlank())
    require(controlSocketName.matches(Regex("^@levik_wlr_[A-Za-z0-9_-]{12,95}$")))
    return ProcessBuilder(
        executablePath,
        "-levik-control-sock=$controlSocketName",
    ).also { builder ->
        // The control socket is the only profile ingress. Credentials never enter argv or the
        // inherited environment where Android/process diagnostics could expose them.
        builder.environment().clear()
    }
}

private fun engineFailure(code: String): Nothing = throw TunnelEngineFailureException(code)

private fun IOException.isLocalSocketReadTimeout(): Boolean {
    if (message == Os.strerror(OsConstants.EAGAIN)) return true
    var current: Throwable? = this
    while (current != null) {
        val errno = (current as? ErrnoException)?.errno
        if (errno == OsConstants.EAGAIN ||
            errno == OsConstants.ETIMEDOUT
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

private fun closeDescriptors(descriptors: Iterable<FileDescriptor>) {
    descriptors.forEach(::closeDescriptor)
}

private fun closeDescriptor(descriptor: FileDescriptor) {
    runCatching { Os.close(descriptor) }
}

private fun LocalSocket?.closeQuietly() {
    runCatching { this?.close() }
}
