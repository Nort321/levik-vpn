package com.leviknet.vpn.vpn

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.os.SystemClock
import android.system.Os
import com.leviknet.vpn.BuildConfig
import com.leviknet.vpn.core.logger.AppLogger
import java.io.File
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal interface NativeSocksSession {
    /** Starts the sidecar and returns its loopback SOCKS5 endpoint. */
    suspend fun prepare(): LocalProxyEndpoint

    fun stop()
    fun stopProbe() = stop()
}

/**
 * Xray keeps the TUN, routing and DNS. The selected transport runs in a
 * separate process behind an authenticated loopback SOCKS5 listener.
 */
internal class LoopbackSocksTunnelEngineAdapter(
    override val kind: TunnelEngineKind,
    private val protocol: String,
    private val xrayRuntime: XrayRuntime,
    private val createSession: (TunnelEngineRequest.Socks, TunnelEngineEnvironment) -> NativeSocksSession,
    private val createProbe: (TunnelServer, TunnelEngineEnvironment) -> NativeSocksSession?,
    private val validate: (String, LocalProxyEndpoint) -> Unit = { _, _ -> },
) : TunnelEngineAdapter {

    override suspend fun prepareRouteProbe(server: TunnelServer, environment: TunnelEngineEnvironment): NativeRouteProbe? {
        val session = createProbe(server, environment) ?: return null
        return try {
            val endpoint = session.prepare()
            object : NativeRouteProbe {
                override val proxy = endpoint
                override fun close() = session.stopProbe()
            }
        } catch (error: Throwable) {
            session.stop()
            throw error
        }
    }
    private val lock = Any()
    private val owners = mutableSetOf<Long>()
    private val sessions = mutableMapOf<Long, ActiveSocksSession>()

    override fun claimOwner(owner: Long) {
        xrayRuntime.claimOwner(owner)
        synchronized(lock) { owners += owner }
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
        val socks = request as? TunnelEngineRequest.Socks
            ?: throw IllegalArgumentException("Invalid SOCKS engine request")
        if (environment.network == null) socksEngineFailure("${protocol}_network_required")
        val nativeSession = createSession(socks, environment)
        val active = ActiveSocksSession(nativeSession)
        synchronized(lock) {
            if (owner !in owners) socksEngineFailure("${protocol}_owner_inactive")
            if (sessions.putIfAbsent(owner, active) != null) socksEngineFailure("${protocol}_session_already_active")
        }
        return try {
            val proxy = nativeSession.prepare()
            val prepared = PreparedSocksEngineSession(owner, socks, environment, proxy, kind)
            val accepted = synchronized(lock) {
                (sessions[owner] === active && owner in owners).also { if (it) active.prepared = prepared }
            }
            if (!accepted) {
                nativeSession.stop()
                socksEngineFailure("${protocol}_owner_retired")
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
            socksEngineFailure("${protocol}_prepare_failed")
        }
    }

    override suspend fun start(
        owner: Long,
        prepared: PreparedTunnelEngineSession,
        tun: TunnelFileDescriptorHandle,
    ): Long {
        val socks = prepared as? PreparedSocksEngineSession
            ?: throw IllegalArgumentException("Invalid prepared SOCKS session")
        val active = synchronized(lock) {
            sessions[owner]?.takeIf { socks.owner == owner && it.prepared === socks }
        } ?: socksEngineFailure("${protocol}_prepared_session_inactive")
        var startedLease: Long? = null
        return try {
            val controller = object : libXray.DialerController {
                // Xray only dials the IP-literal loopback sidecar; the sidecar protects its own sockets.
                override fun protectFd(fd: Long): Boolean = socks.environment.unboundSocketProtector(fd)
            }
            val config = socks.request.configFactory.build(tun.borrowedFd, socks.proxy)
            validate(config, socks.proxy)
            val lease = xrayRuntime.start(
                owner = owner,
                configJson = config,
                controller = controller,
                dnsServer = null,
            )
            startedLease = lease
            synchronized(lock) {
                if (sessions[owner] !== active || owner !in owners) socksEngineFailure("${protocol}_owner_retired")
                active.lease = lease
            }
            lease
        } catch (error: CancellationException) {
            startedLease?.let { xrayRuntime.stop(owner, it) }
            active.nativeSession.stop()
            throw error
        } catch (error: TunnelEngineFailureException) {
            startedLease?.let { xrayRuntime.stop(owner, it) }
            active.nativeSession.stop()
            throw error
        } catch (_: Throwable) {
            startedLease?.let { xrayRuntime.stop(owner, it) }
            active.nativeSession.stop()
            socksEngineFailure("${protocol}_start_failed")
        }
    }

    override fun stop(owner: Long, prepared: PreparedTunnelEngineSession?, lease: Long?) {
        val target = prepared as? PreparedSocksEngineSession
        val active = synchronized(lock) {
            val current = sessions[owner] ?: return@synchronized null
            if (target != null && current.prepared !== target) return@synchronized null
            if (lease != null && current.lease != null && current.lease != lease) return@synchronized null
            sessions.remove(owner)
        }
        active?.let { session ->
            xrayRuntime.stop(owner, session.lease ?: lease)
            session.nativeSession.stop()
        }
    }

    private fun removeAndStop(owner: Long, expected: ActiveSocksSession) {
        synchronized(lock) { if (sessions[owner] === expected) sessions.remove(owner) }
        expected.nativeSession.stop()
    }

    private class ActiveSocksSession(
        val nativeSession: NativeSocksSession,
        var prepared: PreparedSocksEngineSession? = null,
        var lease: Long? = null,
    )
}

private data class PreparedSocksEngineSession(
    val owner: Long,
    val request: TunnelEngineRequest.Socks,
    val environment: TunnelEngineEnvironment,
    val proxy: LocalProxyEndpoint,
    override val engine: TunnelEngineKind,
) : PreparedTunnelEngineSession {
    override val tunPlan: TunPlan = request.tunPlan
}

/**
 * Starts a SOCKS helper and protects its outgoing descriptors on the selected
 * Android network before the helper uses them.
 */
internal class AndroidSocksNativeSession(
    private val executablePath: String,
    private val protocol: String,
    private val environment: TunnelEngineEnvironment,
    private val configureOutbound: JsonObjectBuilder.() -> Unit,
) : NativeSocksSession {
    private val random = SecureRandom()
    private val stopping = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val protectName = "levik_${protocol}_protect_${token(18)}"
    private val username = token(18)
    private val password = token(36)
    @Volatile private var process: java.lang.Process? = null
    @Volatile private var protectServer: LocalServerSocket? = null

    override suspend fun prepare(): LocalProxyEndpoint = withContext(Dispatchers.IO) {
        try {
            val executable = File(executablePath)
            if (!executable.isFile || !executable.canExecute()) socksEngineFailure("${protocol}_native_missing")
            val port = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }
            startProtectServer()
            val native = ProcessBuilder(executable.absolutePath, "run", "-c", "stdin", "--disable-color")
                .also { it.environment().clear(); it.environment()["GOMEMLIMIT"] = "64MiB" }
                .start()
            process = native
            // The configuration carries credentials; it is passed on stdin, never written to disk.
            native.outputStream.use { it.write(sidecarConfig(port).toString().encodeToByteArray()) }
            daemon("levik-$protocol-out") { drain(native.inputStream) }
            daemon("levik-$protocol-err") { drain(native.errorStream) }
            daemon("levik-$protocol-wait") {
                val code = runCatching { native.waitFor() }.getOrNull()
                // Startup failures are reported by prepare(); this covers an established session.
                if (running.get() && !stopping.get()) {
                    AppLogger.w(LOG_TAG, "$protocol sidecar exited (code=$code)")
                    environment.terminalFailureHandler("${protocol}_process_exited")
                }
            }
            val deadline = SystemClock.elapsedRealtime() + STARTUP_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                coroutineContext.ensureActive()
                if (!native.isAlive) socksEngineFailure("${protocol}_process_exited")
                if (loopbackAccepts(port)) {
                    running.set(true)
                    return@withContext LocalProxyEndpoint(LOOPBACK, port, username, password)
                }
                delay(50)
            }
            socksEngineFailure("${protocol}_start_timeout")
        } catch (error: CancellationException) {
            stopProbe(); throw error
        } catch (error: TunnelEngineFailureException) {
            stop(); throw error
        } catch (_: Throwable) {
            stop(); socksEngineFailure("${protocol}_prepare_failed")
        }
    }

    override fun stop() = stopWithGrace(1_500L)

    override fun stopProbe() = stopWithGrace(100L)

    private fun stopWithGrace(graceMs: Long) {
        if (!stopping.compareAndSet(false, true)) return
        process?.let { native ->
            native.destroy()
            if (!native.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
                native.destroyForcibly()
                native.waitFor(500, TimeUnit.MILLISECONDS)
            }
        }
        process = null
        protectServer?.let { server ->
            // LocalServerSocket.close() does not wake accept() on every release.
            runCatching { LocalSocket().use { it.connect(LocalSocketAddress(protectName, LocalSocketAddress.Namespace.ABSTRACT)) } }
            runCatching { server.close() }
        }
        protectServer = null
    }

    private fun sidecarConfig(port: Int): JsonObject = buildJsonObject {
        putJsonObject("log") {
            put("level", if (BuildConfig.DEBUG) "info" else "warn")
            put("timestamp", false)
        }
        putJsonArray("inbounds") {
            addJsonObject {
                put("type", "socks")
                put("tag", "levik-$protocol-in")
                put("listen", LOOPBACK)
                put("listen_port", port)
                putJsonArray("users") {
                    addJsonObject {
                        put("username", username)
                        put("password", password)
                    }
                }
            }
        }
        putJsonArray("outbounds") {
            addJsonObject {
                put("type", protocol)
                put("tag", "levik-$protocol")
                put("protect_path", "@$protectName")
                configureOutbound()
            }
        }
        putJsonObject("route") { put("final", "levik-$protocol") }
    }

    private fun startProtectServer() {
        val server = LocalServerSocket(protectName)
        protectServer = server
        daemon("levik-$protocol-protect") {
            while (!stopping.get()) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                if (stopping.get()) {
                    runCatching { client.close() }
                    break
                }
                runCatching { answerProtectRequest(client) }
                runCatching { client.close() }
            }
        }
    }

    /** sing-box protocol: one byte with one SCM_RIGHTS descriptor; any one-byte reply means protected. */
    private fun answerProtectRequest(client: LocalSocket) {
        if (client.peerCredentials.uid != Process.myUid()) return
        client.soTimeout = PROTECT_TIMEOUT_MS
        if (client.inputStream.read() < 0) return
        val descriptors: Array<FileDescriptor> = client.ancillaryFileDescriptors ?: return
        try {
            if (descriptors.size != 1) return
            if (environment.protector.protectAndBind(descriptors[0])) {
                client.outputStream.write(1)
                client.outputStream.flush()
            }
        } finally {
            descriptors.forEach { descriptor -> runCatching { Os.close(descriptor) } }
        }
    }

    private fun loopbackAccepts(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 200) }
        true
    }.getOrDefault(false)

    private fun drain(stream: java.io.InputStream) {
        runCatching {
            stream.bufferedReader().useLines { lines ->
                lines.forEach { line -> if (BuildConfig.DEBUG && protocol == "tuic") AppLogger.d(LOG_TAG, line.take(300)) }
            }
        }
    }

    private fun daemon(name: String, body: () -> Unit) {
        Thread(body, name).apply { isDaemon = true }.start()
    }

    private fun token(size: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(size).also(random::nextBytes))

    private companion object {
        const val LOG_TAG = "SocksTunnel"
        const val LOOPBACK = "127.0.0.1"
        const val STARTUP_TIMEOUT_MS = 8_000L
        const val PROTECT_TIMEOUT_MS = 2_000
    }
}

private fun socksEngineFailure(code: String): Nothing = throw TunnelEngineFailureException(code)
