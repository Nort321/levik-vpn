package com.leviknet.vpn.vpn

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Bounded diagnostic transfers, not an estimate of guaranteed sustained link capacity. */
internal fun interface SmartRouteProbeTransport {
    suspend fun exchange(id: String, request: Request, bytes: Int, uploadBytes: Int): SmartRouteExchange?
}

internal data class SmartRouteExchange(val responseMs: Double, val transferMs: Double) {
    fun mbps(bytes: Int): Double = bytes * 8.0 / (transferMs.coerceAtLeast(1.0) * 1000.0)
}

internal class SmartRouteProber(
    private val candidates: List<SmartRouteProbeConfig.Candidate>,
    private val transport: SmartRouteProbeTransport? = null,
) : AutoCloseable {
    private val sockets = if (transport != null) emptyMap() else candidates.associate {
        it.id to PrivateUnixSocketFactory(it.path)
    }
    private val clients = if (transport != null) emptyMap() else candidates.associate { candidate -> candidate.id to OkHttpClient.Builder()
        .socketFactory(sockets.getValue(candidate.id))
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9)))
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS).build() }

    suspend fun measureLatency(light: Boolean = false, onMeasurement: (String, SmartRouteMeasurement) -> Unit) = coroutineScope {
        val limit = Semaphore(8)
        val samples = candidates.associate { it.id to mutableListOf<Double>() }
        val attempts = candidates.associate { it.id to java.util.concurrent.atomic.AtomicInteger() }
        val screening = java.util.concurrent.ConcurrentHashMap<String, Double>()
        val targets = candidates.associate { it.id to mutableSetOf<String>() }
        // Reserve time for sequential finalist transfers even when some routes blackhole.
        withTimeoutOrNull(6_000L) {
            repeat(6) { round ->
                candidates.map { candidate -> async {
                    if (attempts.getValue(candidate.id).get() >= 2 && samples.getValue(candidate.id).isEmpty()) return@async
                    limit.withPermit {
                        val target = LATENCY_TARGETS[round % LATENCY_TARGETS.size]
                        val bytes = if (round == 0 && !light) 64 * 1024 else target.second
                        val url = if (round == 0) "https://speed.cloudflare.com/__down?bytes=$bytes" else target.first
                        ensureActive()
                        val attempt = attempts.getValue(candidate.id).incrementAndGet()
                        val reply = withTimeoutOrNull(1_200L) {
                            exchange(candidate.id, Request.Builder().url(url)
                                .header("Accept-Encoding", "identity").header("Cache-Control", "no-store")
                                .header("Range", "bytes=0-${bytes - 1}").build(), bytes)
                        }
                        val values = samples.getValue(candidate.id)
                        reply?.responseMs?.let(values::add)
                        if (reply != null) targets.getValue(candidate.id).add(java.net.URI(url).host)
                        if (round == 0 && reply != null) screening[candidate.id] = reply.mbps(bytes)
                        val measured = SmartRouteMeasurement(values.toList(), attempt, screeningMbps = screening[candidate.id],
                            successfulTargets = targets.getValue(candidate.id).toSet())
                        onMeasurement(candidate.id, measured)
                    }
                } }.awaitAll()
            }
        }
    }

    suspend fun measureBandwidth(finalists: Map<String, SmartRouteMeasurement>, onMeasurement: (String, SmartRouteMeasurement) -> Unit) = coroutineScope {
        // Concurrent speed tests would measure competition caused by the test itself.
        for ((id, initial) in finalists) {
            ensureActive()
            val down = exchange(id, Request.Builder().url("https://speed.cloudflare.com/__down?bytes=$DOWNLOAD_BYTES")
                .header("Accept-Encoding", "identity").header("Cache-Control", "no-store").build(), DOWNLOAD_BYTES)
            val upload = ByteArray(UPLOAD_BYTES).also(SecureRandom()::nextBytes)
            val up = exchange(id, Request.Builder().url("https://speed.cloudflare.com/__up?bytes=$UPLOAD_BYTES")
                .post(upload.toRequestBody("application/octet-stream".toMediaType()))
                .header("Accept-Encoding", "identity").build(), 0, uploadBytes = UPLOAD_BYTES)
            val measured = initial.copy(samplesMs = initial.samplesMs + listOfNotNull(down?.responseMs, up?.responseMs),
                attempts = initial.attempts + 2, downloadMbps = down?.mbps(DOWNLOAD_BYTES), uploadMbps = up?.mbps(UPLOAD_BYTES),
                successfulTargets = initial.successfulTargets + if (down != null || up != null) setOf("speed.cloudflare.com") else emptySet())
            onMeasurement(id, measured)
        }
    }

    suspend fun verifyData(id: String): Boolean {
        val bytes = 64 * 1024
        return exchange(id, Request.Builder().url("https://speed.cloudflare.com/__down?bytes=$bytes")
            .header("Accept-Encoding", "identity").header("Cache-Control", "no-store").build(), bytes) != null
    }

    private suspend fun exchange(id: String, request: Request, bytes: Int, uploadBytes: Int = 0): SmartRouteExchange? {
        transport?.let { return it.exchange(id, request, bytes, uploadBytes) }
        return suspendCancellableCoroutine { continuation ->
            var sentAt = 0L
            var headersAt = 0L
            var bodyStartedAt = 0L
            val listener = object : EventListener() {
                override fun requestHeadersEnd(call: Call, request: Request) { sentAt = System.nanoTime() }
                override fun requestBodyStart(call: Call) { bodyStartedAt = System.nanoTime() }
                override fun requestBodyEnd(call: Call, byteCount: Long) { sentAt = System.nanoTime() }
                override fun responseHeadersStart(call: Call) { headersAt = System.nanoTime() }
            }
            val call = clients.getValue(id).newBuilder().eventListener(listener).build().newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resume(null) }
                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (!response.isSuccessful || sentAt == 0L || headersAt < sentAt) null else {
                                val input = response.body.byteStream()
                                val buffer = ByteArray(8192)
                                var received = 0
                                if (uploadBytes > 0) {
                                    // Acknowledgement proves the body reached the remote service.
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        received += count
                                        if (received > 8192) throw IOException("Probe response exceeds its limit")
                                    }
                                } else while (received < bytes) {
                                    val count = input.read(buffer, 0, minOf(buffer.size, bytes - received))
                                    if (count < 0) break
                                    received += count
                                }
                                if (uploadBytes == 0 && received < bytes) null else {
                                    val done = System.nanoTime()
                                    val start = if (uploadBytes > 0) bodyStartedAt.takeIf { it > 0L } ?: sentAt else sentAt
                                    // A mirror may ignore Range. Do not drain a large body just to reuse its connection.
                                    if (uploadBytes == 0 && response.body.contentLength() != bytes.toLong()) call.cancel()
                                    SmartRouteExchange((headersAt - sentAt) / 1_000_000.0, (done - start) / 1_000_000.0)
                                }
                            }
                        }
                    } catch (_: IOException) { null }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    override fun close() {
        // Conscrypt closes TLS only after its HTTP/2 reader releases a lock. With a
        // custom UDS Socket that reader can otherwise wait forever in readba_native.
        // Closing the owned raw sockets first releases both idle and active readers.
        sockets.values.forEach(PrivateUnixSocketFactory::close)
        clients.values.forEach { client ->
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    companion object {
        private const val DOWNLOAD_BYTES = 512 * 1024
        private const val UPLOAD_BYTES = 128 * 1024
        private val LATENCY_TARGETS = listOf(
            "https://speed.cloudflare.com/__down?bytes=1024" to 1024,
            "https://proof.ovh.net/files/1Mb.dat" to 1024,
        )
    }
}
