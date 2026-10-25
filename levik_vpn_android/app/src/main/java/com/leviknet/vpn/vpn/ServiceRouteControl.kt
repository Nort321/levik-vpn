package com.leviknet.vpn.vpn

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Only app-private filesystem sockets are accepted; no routing-control TCP port is exposed. */
internal class ServiceRouteControl(private val config: ServiceRouteConfig) : AutoCloseable {
    private val gate = Any()
    private var closed = false
    private val calls = mutableSetOf<Call>()
    private val client = OkHttpClient.Builder()
        .socketFactory(PrivateUnixSocketFactory(config.controlPath))
        .proxy(Proxy.NO_PROXY)
        .dns { hostname -> require(hostname == "localhost"); listOf(InetAddress.getLoopbackAddress()) }
        .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    fun override(balancer: String, target: String) {
        require(balancer == ServiceRouteConfig.MAIN || balancer.matches(Regex("^levik-sr-route-[0-9]{1,3}$")))
        require(target == ServiceRouteConfig.BLOCK || target == ServiceRouteConfig.DIRECT ||
            target == "levik-sr-base-out" || config.candidates.any { it.outboundTag == target })
        val request = Request.Builder()
            .url("http://localhost/xray.app.router.command.RoutingService/OverrideBalancerTarget")
            .header("TE", "trailers")
            .header("grpc-timeout", "3S")
            .post(ServiceRouteGrpc.overrideRequest(balancer, target).toRequestBody("application/grpc".toMediaType()))
            .build()
        val call = synchronized(gate) {
            check(!closed) { "Service route controller is closed" }
            client.newCall(request).also(calls::add)
        }
        try { call.execute().use { response ->
            check(response.code == 200 && response.header("content-type")?.startsWith("application/grpc") == true) {
                "Service route control rejected the request"
            }
            val body = response.body.byteStream().use { input -> input.readBounded(4096) }
            val status = response.trailers()["grpc-status"] ?: response.header("grpc-status")
            check(status == "0" && body.contentEquals(byteArrayOf(0, 0, 0, 0, 0))) {
                "Service route control did not confirm the target"
            }
        } } finally { synchronized(gate) { calls.remove(call) } }
    }

    override fun close() {
        synchronized(gate) { closed = true; calls.forEach(Call::cancel) }
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

/** The pinned Xray RoutingService request has only two UTF-8 string fields (1 and 2). */
internal object ServiceRouteGrpc {
    fun overrideRequest(balancer: String, target: String): ByteArray {
        require(balancer.length in 1..160 && target.length in 1..160)
        val message = ByteArrayOutputStream().apply {
            stringField(1, balancer)
            stringField(2, target)
        }.toByteArray()
        return ByteBuffer.allocate(5 + message.size).put(0).putInt(message.size).put(message).array()
    }

    private fun ByteArrayOutputStream.stringField(number: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        write((number shl 3) or 2)
        var length = bytes.size
        while (length >= 128) {
            write((length and 127) or 128)
            length = length ushr 7
        }
        write(length)
        write(bytes)
    }
}

internal fun probeServiceExit(candidate: ServiceRouteConfig.Candidate): Boolean {
    for ((address, name) in listOf("1.1.1.1" to "cloudflare-dns.com", "8.8.8.8" to "dns.google")) {
        try {
            PrivateUnixSocket(candidate.probePath).use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), 9), 2000)
                socket.soTimeout = 3500
                socket.outputStream.apply {
                    write("CONNECT $address:443 HTTP/1.1\r\nHost: $address:443\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val header = ByteArrayOutputStream()
                while (header.size() < 4096) {
                    val next = socket.inputStream.read()
                    check(next >= 0) { "Probe proxy closed" }
                    header.write(next)
                    val bytes = header.toByteArray()
                    if (bytes.size >= 4 && bytes.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
                }
                val status = header.toString(Charsets.US_ASCII.name()).substringBefore("\r\n").split(' ')
                check(status.size >= 2 && status[1] == "200" && header.size() < 4096)
                ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, name, 443, true) as SSLSocket).use { tls ->
                    tls.soTimeout = 3500
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                }
                return true
            }
        } catch (_: Exception) { /* A second independent destination distinguishes an endpoint failure. */ }
    }
    return false
}

private class PrivateUnixSocketFactory(private val path: String) : SocketFactory() {
    init { require(path.startsWith('/') && path.toByteArray().size < 100 && !path.contains('\u0000')) }
    override fun createSocket(): Socket = PrivateUnixSocket(path)
    override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
}

/** OkHttp supports custom sockets; TLS, when probing, is layered over these streams. */
private class PrivateUnixSocket(private val path: String) : Socket() {
    private val local = LocalSocket()
    private var opened = false
    private var closed = false
    private var readTimeout = 0
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        require(!closed && !opened)
        // Android declares the timeout overload but throws UnsupportedOperationException.
        // This is an app-private local endpoint; reads and the control call remain bounded.
        local.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        opened = true
        local.soTimeout = readTimeout
    }
    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)
    override fun getInputStream(): InputStream = local.inputStream
    override fun getOutputStream(): OutputStream = local.outputStream
    override fun setSoTimeout(timeout: Int) {
        require(timeout >= 0)
        readTimeout = timeout
        // OkHttp sets its timeout before connect; Android creates the UDS descriptor in connect().
        if (opened) local.soTimeout = timeout
    }
    override fun getSoTimeout(): Int = readTimeout
    override fun setTcpNoDelay(on: Boolean) = Unit
    override fun getTcpNoDelay(): Boolean = true
    override fun setKeepAlive(on: Boolean) = Unit
    override fun isConnected(): Boolean = opened && !closed
    override fun isClosed(): Boolean = closed
    override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getPort(): Int = 9
    override fun getLocalPort(): Int = 0
    override fun shutdownInput() = local.shutdownInput()
    override fun shutdownOutput() = local.shutdownOutput()
    override fun close() { if (!closed) { closed = true; local.close() } }
}

private fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        check(output.size() + count <= limit) { "Service control response is too large" }
        output.write(buffer, 0, count)
    }
}
