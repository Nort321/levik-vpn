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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal fun interface TuicNativeSessionFactory {
    fun create(config: TuicServerConfig, environment: TunnelEngineEnvironment): TuicNativeSession
}

internal interface TuicNativeSession {
    /** Starts the sidecar and returns its loopback SOCKS5 endpoint. */
    suspend fun prepare(): LocalProxyEndpoint

    fun stop()
}

/**
 * TUIC v5 through a minimal sing-box sidecar. Xray keeps the TUN, routing and DNS and
 * forwards the selected server's traffic to the sidecar's loopback SOCKS5 inbound.
 */
internal class TuicTunnelEngineAdapter(
    private val sessionFactory: TuicNativeSessionFactory,
    private val xrayRuntime: XrayRuntime,
) : TunnelEngineAdapter {
    override val kind: TunnelEngineKind = TunnelEngineKind.LEVIK_TUIC
    private val lock = Any()
    private val owners = mutableSetOf<Long>()
    private val sessions = mutableMapOf<Long, ActiveTuicSession>()

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
        val tuic = request as? TunnelEngineRequest.Tuic
            ?: throw IllegalArgumentException("Invalid TUIC engine request")
        if (environment.network == null) tuicEngineFailure("tuic_network_required")
        val nativeSession = sessionFactory.create(tuic.config, environment)
        val active = ActiveTuicSession(nativeSession)
        synchronized(lock) {
            if (owner !in owners) tuicEngineFailure("tuic_owner_inactive")
            if (sessions.putIfAbsent(owner, active) != null) tuicEngineFailure("tuic_session_already_active")
        }
        return try {
            val proxy = nativeSession.prepare()
            val prepared = PreparedTuicEngineSession(owner, tuic, environment, proxy, nativeSession)
            val accepted = synchronized(lock) {
                (sessions[owner] === active && owner in owners).also { if (it) active.prepared = prepared }
            }
            if (!accepted) {
                nativeSession.stop()
                tuicEngineFailure("tuic_owner_retired")
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
            tuicEngineFailure("tuic_prepare_failed")
        }
    }

    override suspend fun start(
        owner: Long,
        prepared: PreparedTunnelEngineSession,
        tun: TunnelFileDescriptorHandle,
    ): Long {
        val tuic = prepared as? PreparedTuicEngineSession
            ?: throw IllegalArgumentException("Invalid prepared TUIC session")
        val active = synchronized(lock) {
            sessions[owner]?.takeIf { tuic.owner == owner && it.prepared === tuic }
        } ?: tuicEngineFailure("tuic_prepared_session_inactive")
        var startedLease: Long? = null
        return try {
            val controller = object : libXray.DialerController {
                // Xray only dials the IP-literal loopback sidecar; the sidecar protects its own sockets.
                override fun protectFd(fd: Long): Boolean = tuic.environment.unboundSocketProtector(fd)
            }
            val lease = xrayRuntime.start(
                owner = owner,
                configJson = tuic.request.configFactory.build(tun.borrowedFd, tuic.proxy),
                controller = controller,
                dnsServer = null,
            )
            startedLease = lease
            synchronized(lock) {
                if (sessions[owner] !== active || owner !in owners) tuicEngineFailure("tuic_owner_retired")
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
            tuicEngineFailure("tuic_start_failed")
        }
    }

    override fun stop(owner: Long, prepared: PreparedTunnelEngineSession?, lease: Long?) {
        val target = prepared as? PreparedTuicEngineSession
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

    private fun removeAndStop(owner: Long, expected: ActiveTuicSession) {
        synchronized(lock) { if (sessions[owner] === expected) sessions.remove(owner) }
        expected.nativeSession.stop()
    }

    private class ActiveTuicSession(
        val nativeSession: TuicNativeSession,
        var prepared: PreparedTuicEngineSession? = null,
        var lease: Long? = null,
    )
}

private data class PreparedTuicEngineSession(
    val owner: Long,
    val request: TunnelEngineRequest.Tuic,
    val environment: TunnelEngineEnvironment,
    val proxy: LocalProxyEndpoint,
    val nativeSession: TuicNativeSession,
) : PreparedTunnelEngineSession {
    override val engine: TunnelEngineKind = TunnelEngineKind.LEVIK_TUIC
    override val tunPlan: TunPlan = request.tunPlan
}

internal class AndroidTuicNativeSessionFactory(private val executablePath: String) : TuicNativeSessionFactory {
    override fun create(config: TuicServerConfig, environment: TunnelEngineEnvironment): TuicNativeSession =
        AndroidTuicNativeSession(executablePath, config, environment)
}

/**
 * Runs `libleviktuic.so` (Levik TUIC client, sing-box config subset) and answers its `protect_path` requests: every
 * outbound socket arrives over an abstract Unix socket via SCM_RIGHTS and is protected
 * from the VPN and bound to the selected network before the sidecar may use it.
 */
private class AndroidTuicNativeSession(
    private val executablePath: String,
    private val config: TuicServerConfig,
    private val environment: TunnelEngineEnvironment,
) : TuicNativeSession {
    private val random = SecureRandom()
    private val stopping = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val protectName = "levik_tuic_protect_${token(18)}"
    private val username = token(18)
    private val password = token(36)
    @Volatile private var process: java.lang.Process? = null
    @Volatile private var protectServer: LocalServerSocket? = null

    override suspend fun prepare(): LocalProxyEndpoint = withContext(Dispatchers.IO) {
        try {
            val executable = File(executablePath)
            if (!executable.isFile || !executable.canExecute()) tuicEngineFailure("tuic_native_missing")
            val port = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }
            startProtectServer()
            val native = ProcessBuilder(executable.absolutePath, "run", "-c", "stdin", "--disable-color")
                .also { it.environment().clear() }
                .start()
            process = native
            // The configuration carries credentials; it is passed on stdin, never written to disk.
            native.outputStream.use { it.write(sidecarConfig(port).toString().encodeToByteArray()) }
            daemon("levik-tuic-out") { drain(native.inputStream) }
            daemon("levik-tuic-err") { drain(native.errorStream) }
            daemon("levik-tuic-wait") {
                val code = runCatching { native.waitFor() }.getOrNull()
                // Startup failures are reported by prepare(); this covers an established session.
                if (running.get() && !stopping.get()) {
                    AppLogger.w(LOG_TAG, "TUIC sidecar exited (code=$code)")
                    environment.terminalFailureHandler("tuic_process_exited")
                }
            }
            val deadline = SystemClock.elapsedRealtime() + STARTUP_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!native.isAlive) tuicEngineFailure("tuic_process_exited")
                if (loopbackAccepts(port)) {
                    running.set(true)
                    return@withContext LocalProxyEndpoint(LOOPBACK, port, username, password)
                }
                Thread.sleep(50)
            }
            tuicEngineFailure("tuic_start_timeout")
        } catch (error: CancellationException) {
            stop(); throw error
        } catch (error: TunnelEngineFailureException) {
            stop(); throw error
        } catch (_: Throwable) {
            stop(); tuicEngineFailure("tuic_prepare_failed")
        }
    }

    override fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        process?.let { native ->
            native.destroy()
            if (!native.waitFor(1_500, TimeUnit.MILLISECONDS)) {
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
                put("tag", "levik-tuic-in")
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
                put("type", "tuic")
                put("tag", "levik-tuic")
                put("server", config.address)
                put("server_port", config.port)
                put("uuid", config.uuid)
                put("password", config.password)
                put("congestion_control", config.congestionControl)
                put("udp_relay_mode", config.udpRelayMode)
                put("heartbeat", "10s")
                // Go maps a leading '@' to the Linux abstract socket namespace.
                put("protect_path", "@$protectName")
                putJsonObject("tls") {
                    put("enabled", true)
                    put("server_name", config.serverName)
                    putJsonArray("alpn") { config.alpn.forEach { add(it) } }
                    putJsonArray("certificate") { add(config.caCertificatePem) }
                }
            }
        }
        putJsonObject("route") { put("final", "levik-tuic") }
    }

    private fun startProtectServer() {
        val server = LocalServerSocket(protectName)
        protectServer = server
        daemon("levik-tuic-protect") {
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
                lines.forEach { line -> if (BuildConfig.DEBUG) AppLogger.d(LOG_TAG, line.take(300)) }
            }
        }
    }

    private fun daemon(name: String, body: () -> Unit) {
        Thread(body, name).apply { isDaemon = true }.start()
    }

    private fun token(size: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(size).also(random::nextBytes))

    private companion object {
        const val LOG_TAG = "TuicTunnel"
        const val LOOPBACK = "127.0.0.1"
        const val STARTUP_TIMEOUT_MS = 8_000L
        const val PROTECT_TIMEOUT_MS = 2_000
    }
}

private fun tuicEngineFailure(code: String): Nothing = throw TunnelEngineFailureException(code)
