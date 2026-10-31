package com.leviknet.vpn.vpn

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal suspend fun probeHotSwitchMain(
    config: SmartAutoLiveConfig,
    serverId: String,
    dataProbe: suspend (SmartRouteProbeConfig.Candidate) -> Boolean = { candidate ->
        SmartRouteProber(listOf(candidate)).use { it.verifyData(candidate.id) }
    },
    dnsProbe: suspend (String, List<String>) -> Boolean = { path, resolvers ->
        val request = currentCoroutineContext()[Job]
        withContext(Dispatchers.IO) { probeHotSwitchDns(path, resolvers) { request?.isActive != false } }
    },
): Boolean = withTimeoutOrNull(15_000L) {
    val candidate = config.candidates.firstOrNull { it.id == serverId } ?: return@withTimeoutOrNull false
    dataProbe(candidate) && dnsProbe(candidate.path, config.dnsResolvers)
} == true

/** A DNS wire reply must cross the same private candidate proxy as the data proof. */
internal fun probeHotSwitchDns(path: String, resolvers: List<String>, guard: () -> Boolean = { true }): Boolean {
    if (resolvers.isEmpty()) return false
    for (resolver in resolvers) {
        if (!guard()) return false
        try {
            val uri = URI(if (resolver.contains("://")) resolver else "tcp://${resolver.removeSurrounding("[", "]").let { if (':' in it) "[$it]" else it }}:53")
            if (uri.scheme !in setOf("https", "tcp") || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null) continue
            val id = SecureRandom().nextInt(65536)
            val question = HotSwitchDnsWire.question(id)
            val answer = if (uri.scheme == "https") {
                val sockets = PrivateUnixSocketFactory(path)
                val client = OkHttpClient.Builder().socketFactory(sockets)
                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9)))
                    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                    .connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
                try {
                    client.newCall(Request.Builder().url(uri.toString())
                        .header("Accept", "application/dns-message")
                        .post(question.toRequestBody("application/dns-message".toMediaType())).build()).execute().use { response ->
                        check(response.code == 200 && response.header("Content-Type")?.substringBefore(';')?.trim() == "application/dns-message")
                        response.body.byteStream().use { input ->
                            val bytes = input.hotSwitchRead(65536)
                            check(bytes.size <= 65535)
                            bytes
                        }
                    }
                } finally {
                    sockets.close()
                    client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
                }
            } else PrivateUnixSocketFactory(path).createSocket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", 9), 2000); socket.soTimeout = 3500
                val host = uri.host.removeSurrounding("[", "]")
                val destination = "${if (':' in host) "[$host]" else host}:${if (uri.port > 0) uri.port else 53}"
                socket.outputStream.write("CONNECT $destination HTTP/1.1\r\nHost: $destination\r\n\r\n".toByteArray(Charsets.US_ASCII))
                socket.outputStream.flush()
                val header = ByteArrayOutputStream()
                while (header.size() < 4096) {
                    val next = socket.inputStream.read(); check(next >= 0); header.write(next)
                    val bytes = header.toByteArray()
                    if (bytes.size >= 4 && bytes.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
                }
                check(header.size() < 4096 && header.toString(Charsets.US_ASCII.name()).substringBefore("\r\n").split(' ').getOrNull(1) == "200")
                socket.outputStream.write(byteArrayOf((question.size ushr 8).toByte(), question.size.toByte()) + question)
                socket.outputStream.flush()
                val high = socket.inputStream.read(); val low = socket.inputStream.read(); check(high >= 0 && low >= 0)
                val length = (high shl 8) or low
                val bytes = socket.inputStream.hotSwitchRead(length); check(bytes.size == length)
                bytes
            }
            if (HotSwitchDnsWire.answer(answer, id)) return true
        } catch (_: Exception) { /* Only the user's configured resolver alternatives may be tried. */ }
    }
    return false
}

private fun InputStream.hotSwitchRead(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal object HotSwitchDnsWire {
    fun question(id: Int): ByteArray {
        require(id in 0..65535)
        return byteArrayOf((id ushr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1)
    }
    fun answer(bytes: ByteArray, id: Int): Boolean {
        fun word(at: Int): Int = ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
        if (bytes.size < 12 || word(0) != id || bytes[2].toInt() and 0x80 == 0 || bytes[2].toInt() and 2 != 0 || bytes[3].toInt() and 15 != 0 || word(4) != 1) return false
        fun name(start: Int): Int {
            var at = start
            repeat(128) {
                if (at >= bytes.size) return -1
                val size = bytes[at++].toInt() and 255
                if (size == 0) return at
                if (size and 0xc0 == 0xc0) return if (at < bytes.size && ((size and 63) shl 8 or (bytes[at].toInt() and 255)) < bytes.size) at + 1 else -1
                if (size > 63 || at + size > bytes.size) return -1
                at += size
            }
            return -1
        }
        var at = name(12)
        if (at < 0 || at + 4 > bytes.size || word(at) != 1 || word(at + 2) != 1) return false
        at += 4
        val expected = question(id)
        if (at != expected.size || !bytes.copyOfRange(12, at).contentEquals(expected.copyOfRange(12, expected.size))) return false
        val count = word(6)
        if (count !in 1..1024) return false
        var address = false
        repeat(count) {
            at = name(at)
            if (at < 0 || at + 10 > bytes.size) return false
            val type = word(at); val recordClass = word(at + 2); val length = word(at + 8); at += 10
            if (at + length > bytes.size) return false
            if (type == 1 && recordClass == 1 && length == 4) address = true
            at += length
        }
        return address
    }
}
