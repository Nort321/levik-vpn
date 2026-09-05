package com.leviknet.vpn.vpn

import android.net.Network
import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.net.InetAddress
import java.util.EnumMap
import libXray.DialerController

/** Protects a native socket from the VPN and binds it to the selected physical network. */
fun interface TunnelFileDescriptorProtector {
    fun protectAndBind(fd: Long): Boolean

    /**
     * Applies the same operation to an SCM_RIGHTS descriptor without taking ownership of it.
     * The duplicate shares the underlying socket state; the IPC receiver remains responsible for
     * closing the exact descriptor it received.
     */
    fun protectAndBind(fileDescriptor: FileDescriptor): Boolean = runCatching {
        ParcelFileDescriptor.dup(fileDescriptor).use { duplicate ->
            protectAndBind(duplicate.fd.toLong())
        }
    }.getOrDefault(false)
}

data class TunAddress(
    val address: String,
    val prefixLength: Int,
) {
    init {
        val addressBits = numericIpAddressBits(address)
        require(prefixLength in 0..addressBits) { "Invalid TUN address prefix length" }
    }
}

/** Network parameters negotiated or selected before Android creates the VPN interface. */
data class TunPlan(
    val mtu: Int,
    val addresses: List<TunAddress>,
    val dnsServers: List<String>,
) {
    init {
        require(mtu in 576..9_000) { "Invalid TUN MTU" }
        require(addresses.isNotEmpty()) { "TUN plan must contain an address" }
        require(addresses.size <= 8) { "Too many TUN addresses" }
        require(dnsServers.isNotEmpty()) { "TUN plan must contain a DNS server" }
        require(dnsServers.size <= 8) { "Too many TUN DNS servers" }
        dnsServers.forEach(::numericIpAddressBits)
    }
}

private fun numericIpAddressBits(address: String): Int {
    require(address.contains('.') || address.contains(':')) { "Invalid numeric IP address" }
    require(address.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) {
        "Invalid numeric IP address"
    }
    return runCatching { InetAddress.getByName(address).address.size * 8 }
        .getOrElse { throw IllegalArgumentException("Invalid numeric IP address", it) }
}

fun interface XrayConfigFactory {
    fun build(tunFileDescriptor: Int): String
}

data class LocalProxyEndpoint(
    val address: String,
    val port: Int,
    val username: String,
    val password: String,
) {
    init {
        require(address == "127.0.0.1") { "Relay proxy must be loopback-only" }
        require(port in 1..65_535) { "Invalid relay proxy port" }
        require(username.matches(Regex("[A-Za-z0-9_-]{16,64}"))) {
            "Invalid relay proxy username"
        }
        require(password.matches(Regex("[A-Za-z0-9_-]{32,128}"))) {
            "Invalid relay proxy password"
        }
    }
}

fun interface RelayXrayConfigFactory {
    fun build(tunFileDescriptor: Int, proxy: LocalProxyEndpoint): String
}

sealed interface TunnelEngineRequest {
    data class Xray(
        val configFactory: XrayConfigFactory,
        val tunPlan: TunPlan,
    ) : TunnelEngineRequest

    data class Relay(
        val config: RelayServerConfig,
        val configFactory: RelayXrayConfigFactory,
        val tunPlan: TunPlan,
    ) : TunnelEngineRequest
}

/**
 * Inputs available while an engine performs its upstream handshake. For a networked session the
 * protector is pinned to [network]: native PROTECT_SOCKET requests must be acknowledged only after
 * it returns true. [network] is null only for an intentionally offline engine session (the local
 * kill-switch blackhole); relay implementations must reject that case.
 */
data class TunnelEngineEnvironment(
    val network: Network?,
    val protector: TunnelFileDescriptorProtector,
    val dnsServer: String,
    /** Stable-code callback for an engine that fails after [TunnelEngineAdapter.start] returns. */
    val terminalFailureHandler: (String) -> Unit = {},
)

/** Opaque engine-owned result of prepare. It may contain a live native handshake/session token. */
interface PreparedTunnelEngineSession {
    val engine: TunnelEngineKind
    val tunPlan: TunPlan
}

/**
 * Handle for a TUN descriptor owned by LevikVpnService. [borrowedFd] must never be closed or
 * detached by an adapter. An adapter that needs ownership must call [duplicateForEngine]; the
 * returned descriptor is then exclusively engine-owned and must be closed or detached exactly
 * once. LevikVpnService keeps the borrowed descriptor alive until adapter.stop returns.
 */
interface TunnelFileDescriptorHandle {
    val borrowedFd: Int
    fun duplicateForEngine(): EngineOwnedTunnelFileDescriptor
}

interface EngineOwnedTunnelFileDescriptor : AutoCloseable {
    val fd: Int

    /** Transfers ownership to native code. The caller must eventually close the returned fd. */
    fun detach(): Int

    override fun close()
}

internal class AndroidTunnelFileDescriptorHandle(
    private val descriptor: ParcelFileDescriptor,
) : TunnelFileDescriptorHandle {
    override val borrowedFd: Int
        get() = descriptor.fd

    override fun duplicateForEngine(): EngineOwnedTunnelFileDescriptor =
        AndroidEngineOwnedTunnelFileDescriptor(
            ParcelFileDescriptor.dup(descriptor.fileDescriptor),
        )
}

private class AndroidEngineOwnedTunnelFileDescriptor(
    private val descriptor: ParcelFileDescriptor,
) : EngineOwnedTunnelFileDescriptor {
    private var state = DescriptorState.OPEN

    override val fd: Int
        @Synchronized get() {
            check(state == DescriptorState.OPEN) { "TUN descriptor is no longer owned by the adapter" }
            return descriptor.fd
        }

    @Synchronized
    override fun detach(): Int {
        check(state == DescriptorState.OPEN) { "TUN descriptor ownership was already released" }
        state = DescriptorState.DETACHED
        return descriptor.detachFd()
    }

    @Synchronized
    override fun close() {
        if (state == DescriptorState.OPEN) {
            state = DescriptorState.CLOSED
            descriptor.close()
        }
    }

    private enum class DescriptorState {
        OPEN,
        DETACHED,
        CLOSED,
    }
}

interface TunnelEngineAdapter {
    val kind: TunnelEngineKind

    fun claimOwner(owner: Long)

    fun retireOwner(owner: Long)

    /** May perform an upstream handshake; Android has not established the TUN yet. */
    suspend fun prepare(
        owner: Long,
        request: TunnelEngineRequest,
        environment: TunnelEngineEnvironment,
    ): PreparedTunnelEngineSession

    /** Starts packet processing with an already established, service-owned TUN. */
    suspend fun start(
        owner: Long,
        prepared: PreparedTunnelEngineSession,
        tun: TunnelFileDescriptorHandle,
    ): Long

    /** Also releases a prepared session that never reached [start]. */
    fun stop(
        owner: Long,
        prepared: PreparedTunnelEngineSession?,
        lease: Long? = null,
    )
}

class TunnelEngineRegistry(
    adapters: List<TunnelEngineAdapter>,
) {
    private val adapters = EnumMap<TunnelEngineKind, TunnelEngineAdapter>(TunnelEngineKind::class.java)

    init {
        adapters.forEach { adapter ->
            require(this.adapters.put(adapter.kind, adapter) == null) {
                "Duplicate tunnel engine adapter: ${adapter.kind}"
            }
        }
        require(this.adapters.containsKey(TunnelEngineKind.XRAY)) {
            "The Xray tunnel engine is required"
        }
    }

    val supportedProfileEngines: Set<TunnelEngineKind>
        get() = adapters.keys.toSet()

    fun require(kind: TunnelEngineKind): TunnelEngineAdapter =
        adapters[kind] ?: throw TunnelEngineUnavailableException(kind)

    fun claimOwner(owner: Long) {
        adapters.values.forEach { it.claimOwner(owner) }
    }

    fun retireOwner(owner: Long) {
        adapters.values.forEach { it.retireOwner(owner) }
    }
}

private data class PreparedXrayEngineSession(
    val request: TunnelEngineRequest.Xray,
    val environment: TunnelEngineEnvironment,
) : PreparedTunnelEngineSession {
    override val engine: TunnelEngineKind = TunnelEngineKind.XRAY
    override val tunPlan: TunPlan = request.tunPlan
}

class XrayTunnelEngineAdapter(
    private val runtime: XrayRuntime,
) : TunnelEngineAdapter {
    override val kind: TunnelEngineKind = TunnelEngineKind.XRAY

    override fun claimOwner(owner: Long) = runtime.claimOwner(owner)

    override fun retireOwner(owner: Long) = runtime.retireOwner(owner)

    override suspend fun prepare(
        owner: Long,
        request: TunnelEngineRequest,
        environment: TunnelEngineEnvironment,
    ): PreparedTunnelEngineSession {
        val xray = request as? TunnelEngineRequest.Xray
            ?: throw IllegalArgumentException("Invalid Xray engine request")
        return PreparedXrayEngineSession(xray, environment)
    }

    override suspend fun start(
        owner: Long,
        prepared: PreparedTunnelEngineSession,
        tun: TunnelFileDescriptorHandle,
    ): Long {
        val xray = prepared as? PreparedXrayEngineSession
            ?: throw IllegalArgumentException("Invalid prepared Xray session")
        val controller = object : DialerController {
            override fun protectFd(fd: Long): Boolean =
                xray.environment.protector.protectAndBind(fd)
        }
        // libXray consumes a borrowed fd embedded in its JSON and does not assume ownership.
        return runtime.start(
            owner = owner,
            configJson = xray.request.configFactory.build(tun.borrowedFd),
            controller = controller,
            dnsServer = xray.environment.dnsServer,
        )
    }

    override fun stop(
        owner: Long,
        prepared: PreparedTunnelEngineSession?,
        lease: Long?,
    ) = runtime.stop(owner, lease)
}

class UnavailableTunnelEngineAdapter(
    override val kind: TunnelEngineKind,
) : TunnelEngineAdapter {
    override fun claimOwner(owner: Long) = Unit

    override fun retireOwner(owner: Long) = Unit

    override suspend fun prepare(
        owner: Long,
        request: TunnelEngineRequest,
        environment: TunnelEngineEnvironment,
    ): PreparedTunnelEngineSession = throw TunnelEngineUnavailableException(kind)

    override suspend fun start(
        owner: Long,
        prepared: PreparedTunnelEngineSession,
        tun: TunnelFileDescriptorHandle,
    ): Long = throw TunnelEngineUnavailableException(kind)

    override fun stop(
        owner: Long,
        prepared: PreparedTunnelEngineSession?,
        lease: Long?,
    ) = Unit
}

class TunnelEngineUnavailableException(
    val engine: TunnelEngineKind,
) : Exception("Tunnel engine is unavailable in this application build")

/** Stable, non-secret engine failure exposed to the service/UI boundary. */
class TunnelEngineFailureException(
    val code: String,
) : Exception(code) {
    init {
        require(code.matches(Regex("^[a-z0-9_]{1,96}$"))) { "Invalid tunnel engine error code" }
    }
}
