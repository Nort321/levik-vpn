package com.leviknet.vpn.vpn

import android.os.SystemClock
import com.leviknet.vpn.core.logger.AppLogger
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal fun interface GameNativeSessionFactory {
    fun create(config: GameServerConfig, preferredExitId: String?, environment: TunnelEngineEnvironment): GameNativeSession
}

internal interface GameNativeSession {
    /** Starts the sidecar and returns its loopback identity and the SOCKS ports Xray must open. */
    suspend fun prepare(): GameSidecarEndpoint

    fun stop()
}

/**
 * Levik Game through the loopback Game sidecar. Xray keeps the TUN (only the selected games)
 * and gives the sidecar their UDP; the sidecar reaches the game relays through Xray's SOCKS
 * inbounds of the game node links.
 */
internal class GameTunnelEngineAdapter(
    private val sessionFactory: GameNativeSessionFactory,
    private val xrayRuntime: XrayRuntime,
) : TunnelEngineAdapter {
    override val kind: TunnelEngineKind = TunnelEngineKind.LEVIK_GAME
    private val lock = Any()
    private val owners = mutableSetOf<Long>()
    private val sessions = mutableMapOf<Long, ActiveGameSession>()

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
        val game = request as? TunnelEngineRequest.Game
            ?: throw IllegalArgumentException("Invalid Game engine request")
        if (environment.network == null) gameEngineFailure("game_network_required")
        val nativeSession = sessionFactory.create(game.config, game.preferredExitId, environment)
        val active = ActiveGameSession(nativeSession)
        synchronized(lock) {
            if (owner !in owners) gameEngineFailure("game_owner_inactive")
            if (sessions.putIfAbsent(owner, active) != null) gameEngineFailure("game_session_already_active")
        }
        return try {
            val endpoint = nativeSession.prepare()
            val prepared = PreparedGameEngineSession(owner, game, environment, endpoint)
            val accepted = synchronized(lock) {
                (sessions[owner] === active && owner in owners).also { if (it) active.prepared = prepared }
            }
            if (!accepted) {
                nativeSession.stop()
                gameEngineFailure("game_owner_retired")
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
            gameEngineFailure("game_prepare_failed")
        }
    }

    override suspend fun start(
        owner: Long,
        prepared: PreparedTunnelEngineSession,
        tun: TunnelFileDescriptorHandle,
    ): Long {
        val game = prepared as? PreparedGameEngineSession
            ?: throw IllegalArgumentException("Invalid prepared Game session")
        val active = synchronized(lock) {
            sessions[owner]?.takeIf { game.owner == owner && it.prepared === game }
        } ?: gameEngineFailure("game_prepared_session_inactive")
        var startedLease: Long? = null
        return try {
            val controller = object : libXray.DialerController {
                // Xray dials the game nodes and the loopback sidecar: VPN exclusion without
                // binding to one network keeps loopback reachable.
                override fun protectFd(fd: Long): Boolean = game.environment.unboundSocketProtector(fd)
            }
            val lease = xrayRuntime.start(
                owner = owner,
                configJson = game.request.configFactory.build(tun.borrowedFd, game.endpoint),
                controller = controller,
                dnsServer = game.environment.dnsServer,
            )
            startedLease = lease
            synchronized(lock) {
                if (sessions[owner] !== active || owner !in owners) gameEngineFailure("game_owner_retired")
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
            gameEngineFailure("game_start_failed")
        }
    }

    override fun stop(owner: Long, prepared: PreparedTunnelEngineSession?, lease: Long?) {
        val target = prepared as? PreparedGameEngineSession
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

    private fun removeAndStop(owner: Long, expected: ActiveGameSession) {
        synchronized(lock) { if (sessions[owner] === expected) sessions.remove(owner) }
        expected.nativeSession.stop()
    }

    private class ActiveGameSession(
        val nativeSession: GameNativeSession,
        var prepared: PreparedGameEngineSession? = null,
        var lease: Long? = null,
    )
}

private data class PreparedGameEngineSession(
    val owner: Long,
    val request: TunnelEngineRequest.Game,
    val environment: TunnelEngineEnvironment,
    val endpoint: GameSidecarEndpoint,
) : PreparedTunnelEngineSession {
    override val engine: TunnelEngineKind = TunnelEngineKind.LEVIK_GAME
    override val tunPlan: TunPlan = request.tunPlan
}

internal class AndroidGameNativeSessionFactory(private val executablePath: String) : GameNativeSessionFactory {
    override fun create(config: GameServerConfig, preferredExitId: String?, environment: TunnelEngineEnvironment): GameNativeSession =
        AndroidGameNativeSession(executablePath, config, preferredExitId, environment)
}

/** Runs `liblevikgame.so`; its configuration (SOCKS password, relay token) goes over stdin only. */
private class AndroidGameNativeSession(
    private val executablePath: String,
    private val config: GameServerConfig,
    private val preferredExitId: String?,
    private val environment: TunnelEngineEnvironment,
) : GameNativeSession {
    private val random = SecureRandom()
    private val stopping = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val statusJson = Json { ignoreUnknownKeys = true }
    @Volatile private var process: java.lang.Process? = null

    override suspend fun prepare(): GameSidecarEndpoint = withContext(Dispatchers.IO) {
        try {
            val executable = File(executablePath)
            if (!executable.isFile || !executable.canExecute()) gameEngineFailure("game_native_missing")
            val ports = freePorts(config.routes.size + 1)
            val endpoint = GameSidecarEndpoint(
                port = ports.first(),
                uuid = UUID.randomUUID().toString(),
                socksPorts = ports.drop(1),
                socksPassword = token(36),
            )
            val native = ProcessBuilder(executable.absolutePath)
                .also { it.environment().clear() }
                .start()
            process = native
            native.outputStream.use { it.write(sidecarConfig(endpoint).toString().encodeToByteArray()) }
            val ready = java.util.concurrent.CompletableFuture<Int>()
            daemon("levik-game-out") { readStatus(native.inputStream, ready) }
            daemon("levik-game-err") { drainLog(native.errorStream) }
            daemon("levik-game-wait") {
                val code = runCatching { native.waitFor() }.getOrNull()
                ready.complete(-1)
                if (running.get() && !stopping.get()) {
                    AppLogger.w(LOG_TAG, "Game sidecar exited (code=$code)")
                    environment.terminalFailureHandler("game_process_exited")
                }
            }
            val port = runCatching { ready.get(STARTUP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
                ?: gameEngineFailure("game_start_timeout")
            if (port != endpoint.port || !native.isAlive) gameEngineFailure("game_process_exited")
            val deadline = SystemClock.elapsedRealtime() + STARTUP_TIMEOUT_MS
            while (!loopbackAccepts(port)) {
                if (SystemClock.elapsedRealtime() > deadline || !native.isAlive) gameEngineFailure("game_start_timeout")
                Thread.sleep(50)
            }
            running.set(true)
            endpoint
        } catch (error: CancellationException) {
            stop(); throw error
        } catch (error: TunnelEngineFailureException) {
            stop(); throw error
        } catch (_: Throwable) {
            stop(); gameEngineFailure("game_prepare_failed")
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
        GameStatusStore.update(null)
    }

    private fun sidecarConfig(endpoint: GameSidecarEndpoint): JsonObject = buildJsonObject {
        put("version", 1)
        putJsonObject("listen") {
            put("port", endpoint.port)
            put("uuid", endpoint.uuid)
        }
        put("password", endpoint.socksPassword)
        putJsonArray("routes") {
            config.routes.forEachIndexed { position, route ->
                addJsonObject {
                    put("index", route.index)
                    put("port", endpoint.socksPorts[position])
                    put("nodeId", route.nodeId)
                }
            }
        }
        putJsonArray("nodes") {
            config.nodes.forEach { node ->
                addJsonObject {
                    put("nodeId", node.nodeId)
                    put("name", sidecarName(node.name))
                    put("state", node.state)
                }
            }
        }
        preferredExitId?.takeIf { id -> config.nodes.any { it.nodeId == id } }?.let { put("preferredExitId", it) }
        config.relayToken?.let { put("relayToken", it) }
    }

    private fun readStatus(stream: java.io.InputStream, ready: java.util.concurrent.CompletableFuture<Int>) {
        runCatching {
            stream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (!ready.isDone) {
                        ready.complete(READY.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1)
                        return@forEach
                    }
                    if (line.length <= MAX_STATUS_CHARS && !stopping.get()) {
                        runCatching { statusJson.decodeFromString<GameRuntimeStatus>(line) }
                            .onSuccess(GameStatusStore::update)
                    }
                }
            }
        }
        ready.complete(-1)
    }

    /** Sidecar log lines name nodes and ports only, never addresses or credentials. */
    private fun drainLog(stream: java.io.InputStream) {
        runCatching {
            stream.bufferedReader().useLines { lines -> lines.forEach { line -> AppLogger.i(LOG_TAG, line.take(300)) } }
        }
    }

    private fun freePorts(count: Int): List<Int> {
        val sockets = (1..count).map { ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)) }
        return try {
            sockets.map(ServerSocket::getLocalPort)
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private fun loopbackAccepts(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 200) }
        true
    }.getOrDefault(false)

    private fun daemon(name: String, body: () -> Unit) {
        Thread(body, name).apply { isDaemon = true }.start()
    }

    private fun token(size: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(size).also(random::nextBytes))

    private companion object {
        const val LOG_TAG = "GameTunnel"
        const val LOOPBACK = "127.0.0.1"
        const val STARTUP_TIMEOUT_MS = 8_000L
        const val MAX_STATUS_CHARS = 16 * 1024
        val READY = Regex("ready ([0-9]{4,5})")
    }
}

/** The sidecar accepts letters, digits, spaces and ._()/- in node names (game/config.go). */
internal fun sidecarName(name: String): String =
    name.filter { it.isLetterOrDigit() || it in " ._()/-" }.trim().take(64)

private fun gameEngineFailure(code: String): Nothing = throw TunnelEngineFailureException(code)
