package com.leviknet.vpn.vpn

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.os.SystemClock
import android.system.Os
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

internal fun interface DnsttNativeSessionFactory {
    fun create(config: DnsttServerConfig, environment: TunnelEngineEnvironment): DnsttNativeSession
}

internal interface DnsttNativeSession {
    /** Starts the sidecar and returns its loopback SOCKS5 endpoint. */
    suspend fun prepare(): LocalProxyEndpoint

    fun stop()
}

/**
 * DNSTT through Yandex DNS. Xray keeps the TUN, routing and DNS and
 * forwards the selected server's traffic to the sidecar's loopback SOCKS5 inbound.
 */
internal class DnsttTunnelEngineAdapter(
    private val sessionFactory: DnsttNativeSessionFactory,
    private val xrayRuntime: XrayRuntime,
) : TunnelEngineAdapter {
    override val kind: TunnelEngineKind = TunnelEngineKind.LEVIK_DNSTT
    private val lock = Any()
    private val owners = mutableSetOf<Long>()
    private val sessions = mutableMapOf<Long, ActiveDnsttSession>()

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
        val dnstt = request as? TunnelEngineRequest.Dnstt
            ?: throw IllegalArgumentException("Invalid DNSTT engine request")
        if (environment.network == null) dnsttEngineFailure("dnstt_network_required")
        val nativeSession = sessionFactory.create(dnstt.config, environment)
        val active = ActiveDnsttSession(nativeSession)
        synchronized(lock) {
            if (owner !in owners) dnsttEngineFailure("dnstt_owner_inactive")
            if (sessions.putIfAbsent(owner, active) != null) dnsttEngineFailure("dnstt_session_already_active")
        }
        return try {
            val proxy = nativeSession.prepare()
            val prepared = PreparedDnsttEngineSession(owner, dnstt, environment, proxy, nativeSession)
            val accepted = synchronized(lock) {
                (sessions[owner] === active && owner in owners).also { if (it) active.prepared = prepared }
            }
            if (!accepted) {
                nativeSession.stop()
                dnsttEngineFailure("dnstt_owner_retired")
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
            dnsttEngineFailure("dnstt_prepare_failed")
        }
    }

    override suspend fun start(
        owner: Long,
        prepared: PreparedTunnelEngineSession,
        tun: TunnelFileDescriptorHandle,
    ): Long {
        val dnstt = prepared as? PreparedDnsttEngineSession
            ?: throw IllegalArgumentException("Invalid prepared DNSTT session")
        val active = synchronized(lock) {
            sessions[owner]?.takeIf { dnstt.owner == owner && it.prepared === dnstt }
        } ?: dnsttEngineFailure("dnstt_prepared_session_inactive")
        var startedLease: Long? = null
        return try {
            val controller = object : libXray.DialerController {
                // Xray only dials the IP-literal loopback sidecar; the sidecar protects its own sockets.
                override fun protectFd(fd: Long): Boolean = dnstt.environment.unboundSocketProtector(fd)
            }
            val lease = xrayRuntime.start(
                owner = owner,
                configJson = dnstt.request.configFactory.build(tun.borrowedFd, dnstt.proxy),
                controller = controller,
                dnsServer = null,
            )
            startedLease = lease
            synchronized(lock) {
                if (sessions[owner] !== active || owner !in owners) dnsttEngineFailure("dnstt_owner_retired")
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
            dnsttEngineFailure("dnstt_start_failed")
        }
    }

    override fun stop(owner: Long, prepared: PreparedTunnelEngineSession?, lease: Long?) {
        val target = prepared as? PreparedDnsttEngineSession
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

    private fun removeAndStop(owner: Long, expected: ActiveDnsttSession) {
        synchronized(lock) { if (sessions[owner] === expected) sessions.remove(owner) }
        expected.nativeSession.stop()
    }

    private class ActiveDnsttSession(
        val nativeSession: DnsttNativeSession,
        var prepared: PreparedDnsttEngineSession? = null,
        var lease: Long? = null,
    )
}

private data class PreparedDnsttEngineSession(
    val owner: Long,
    val request: TunnelEngineRequest.Dnstt,
    val environment: TunnelEngineEnvironment,
    val proxy: LocalProxyEndpoint,
    val nativeSession: DnsttNativeSession,
) : PreparedTunnelEngineSession {
    override val engine: TunnelEngineKind = TunnelEngineKind.LEVIK_DNSTT
    override val tunPlan: TunPlan = request.tunPlan
}

internal class AndroidDnsttNativeSessionFactory(private val executablePath: String) : DnsttNativeSessionFactory {
    override fun create(config: DnsttServerConfig, environment: TunnelEngineEnvironment): DnsttNativeSession =
        AndroidDnsttNativeSession(executablePath, config, environment)
}

/**
 * Runs the pinned DNSTT client and answers its socket protection requests: every
 * outbound socket arrives over an abstract Unix socket via SCM_RIGHTS and is protected
 * from the VPN and bound to the selected network before the sidecar may use it.
 */
private class AndroidDnsttNativeSession(
    private val executablePath: String,
    private val config: DnsttServerConfig,
    private val environment: TunnelEngineEnvironment,
) : DnsttNativeSession {
    private val random = SecureRandom()
    private val stopping = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val protectName = "levik_dnstt_protect_${token(18)}"
    private val username = config.username
    private val password = config.password
    @Volatile private var process: java.lang.Process? = null
    @Volatile private var protectServer: LocalServerSocket? = null

    override suspend fun prepare(): LocalProxyEndpoint = withContext(Dispatchers.IO) {
        try {
            val executable = File(executablePath)
            if (!executable.isFile || !executable.canExecute()) dnsttEngineFailure("dnstt_native_missing")
            val port = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }
            startProtectServer()
            val native = ProcessBuilder(executable.absolutePath, "-udp", "${config.resolver}:53",
                "-pubkey", config.publicKey, "-protect-path", "@$protectName", "-qps", config.qps.toString(),
                config.domain, "$LOOPBACK:$port")
                .also { it.environment().clear() }
                .start()
            process = native
            // Remote SOCKS authentication is performed inside the encrypted tunnel.
            native.outputStream.close()
            daemon("levik-dnstt-out") { drain(native.inputStream) }
            daemon("levik-dnstt-err") { drain(native.errorStream) }
            daemon("levik-dnstt-wait") {
                val code = runCatching { native.waitFor() }.getOrNull()
                // Startup failures are reported by prepare(); this covers an established session.
                if (running.get() && !stopping.get()) {
                    AppLogger.w(LOG_TAG, "DNSTT sidecar exited (code=$code)")
                    environment.terminalFailureHandler("dnstt_process_exited")
                }
            }
            val deadline = SystemClock.elapsedRealtime() + STARTUP_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!native.isAlive) dnsttEngineFailure("dnstt_process_exited")
                if (loopbackAccepts(port)) {
                    running.set(true)
                    return@withContext LocalProxyEndpoint(LOOPBACK, port, username, password)
                }
                Thread.sleep(50)
            }
            dnsttEngineFailure("dnstt_start_timeout")
        } catch (error: CancellationException) {
            stop(); throw error
        } catch (error: TunnelEngineFailureException) {
            stop(); throw error
        } catch (_: Throwable) {
            stop(); dnsttEngineFailure("dnstt_prepare_failed")
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

    private fun startProtectServer() {
        val server = LocalServerSocket(protectName)
        protectServer = server
        daemon("levik-dnstt-protect") {
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

    /** Socket protection protocol: one byte with one SCM_RIGHTS descriptor; any one-byte reply means protected. */
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
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK, port), 200)
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            output.write(byteArrayOf(5, 1, 2))
            output.flush()
            require(input.read() == 5 && input.read() == 2)
            output.write(byteArrayOf(1, username.length.toByte()))
            output.write(username.toByteArray(Charsets.US_ASCII))
            output.write(password.length)
            output.write(password.toByteArray(Charsets.US_ASCII))
            output.flush()
            require(input.read() == 1 && input.read() == 0)
        }
        true
    }.getOrDefault(false)

    private fun drain(stream: java.io.InputStream) {
        runCatching {
            stream.bufferedReader().useLines { lines ->
                lines.forEach { _ -> Unit } // Native diagnostics can contain endpoints; do not log them.
            }
        }
    }

    private fun daemon(name: String, body: () -> Unit) {
        Thread(body, name).apply { isDaemon = true }.start()
    }

    private fun token(size: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(size).also(random::nextBytes))

    private companion object {
        const val LOG_TAG = "DnsttTunnel"
        const val LOOPBACK = "127.0.0.1"
        const val STARTUP_TIMEOUT_MS = 35_000L
        const val PROTECT_TIMEOUT_MS = 2_000
    }
}

private fun dnsttEngineFailure(code: String): Nothing = throw TunnelEngineFailureException(code)
