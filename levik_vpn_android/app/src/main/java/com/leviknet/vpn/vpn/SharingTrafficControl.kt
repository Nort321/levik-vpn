package com.leviknet.vpn.vpn

import com.leviknet.vpn.core.sharing.SharingConfig
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** App-private, read-only endpoint; public sharing listeners cannot reach it. */
internal class SharingTrafficControl(path: String) : AutoCloseable {
    private val sockets: javax.net.SocketFactory = PrivateUnixSocketFactory(path)
    private val client = OkHttpClient.Builder().socketFactory(sockets).proxy(Proxy.NO_PROXY)
        .dns { name -> require(name == "localhost"); listOf(InetAddress.getLoopbackAddress()) }
        .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).retryOnConnectionFailure(false).followRedirects(false)
        .connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).callTimeout(2, TimeUnit.SECONDS).build()

    fun counters(): SmartAutoTrafficCounters? = runCatching {
        var up = 0L
        var down = 0L
        for (tag in listOf("levik-tun-in", SharingConfig.HTTP_IN, SharingConfig.GUEST_IN)) {
            val value = inbound(tag) ?: return@runCatching null
            up = Math.addExact(up, value.uplink)
            down = Math.addExact(down, value.downlink)
        }
        SmartAutoTrafficCounters(up, down)
    }.getOrNull()

    private fun inbound(tag: String): SmartAutoTrafficCounters? {
        val request = Request.Builder().url("http://localhost/xray.app.stats.command.StatsService/QueryStats")
            .header("TE", "trailers").header("grpc-timeout", "1S")
            .post(SmartAutoStatsCodec.request("inbound>>>$tag>>>traffic>>>").toRequestBody("application/grpc".toMediaType())).build()
        return client.newCall(request).execute().use { response ->
            require(response.code == 200 && response.header("content-type")?.startsWith("application/grpc") == true)
            val output = ByteArrayOutputStream()
            response.body.byteStream().use { input ->
                val buffer = ByteArray(1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= 4096)
                    output.write(buffer, 0, read)
                }
            }
            require((response.trailers()["grpc-status"] ?: response.header("grpc-status")) == "0")
            SmartAutoStatsCodec.response(output.toByteArray(), setOf(tag))
        }
    }

    override fun close() {
        // Some existing client revisions provide explicit cancellation of connecting Unix sockets.
        if (sockets is AutoCloseable) sockets.close()
        client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }
}
