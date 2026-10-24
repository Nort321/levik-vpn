package com.leviknet.vpn.core.drop

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** docs/levik-drop.md "Relay": TLS with the pinned key, then the join frame. */

data class RelayEndpoint(val host: String, val port: Int, /** base64url SHA-256 of the certificate's SPKI. */ val pin: String)

sealed class RelayControl {
    data object Wait : RelayControl()
    data object Peer : RelayControl()
    data object Gone : RelayControl()
    data class End(val reason: String) : RelayControl()
}

/** Joins the relay for one transfer; tests replace it with a plain connection. */
fun interface RelayJoiner {
    suspend fun join(endpoints: List<RelayEndpoint>, token: String, scope: CoroutineScope): FrameChannel
}

object DropRelay {
    private const val DIAL_TIMEOUT_MS = 6_000
    private val HOST = Regex("^[A-Za-z0-9.:-]{1,253}$")
    private val PIN = Regex("^[A-Za-z0-9_-]{43}$")

    fun spkiPin(certificate: X509Certificate): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded))

    /** The certificate is self-signed: its key must be the pinned one, checked before a byte of ours goes out. */
    private class PinnedTrust(private val pin: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("Client certificates are not used")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("No relay certificate")
            if (!MessageDigest.isEqual(spkiPin(leaf).toByteArray(), pin.toByteArray())) {
                throw CertificateException("The relay answered with another key")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Connects with a deadline; cancelling closes the socket. */
    suspend fun connect(host: String, port: Int, timeoutMs: Int): Socket = coroutineScope {
        val socket = Socket()
        val connected = AtomicBoolean(false)
        // Cancelling closes the socket, which ends a connect in progress.
        val closer = launch {
            try {
                awaitCancellation()
            } finally {
                if (!connected.get()) runCatching { socket.close() }
            }
        }
        try {
            withContext(Dispatchers.IO) { socket.connect(InetSocketAddress(host, port), timeoutMs) }
            connected.set(true)
            socket
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        } finally {
            closer.cancel()
        }
    }

    private suspend fun dialOne(endpoint: RelayEndpoint): SSLSocket {
        val raw = connect(endpoint.host, endpoint.port, DIAL_TIMEOUT_MS)
        return try {
            withContext(Dispatchers.IO) {
                val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrust(endpoint.pin)), null) }
                val tls = context.socketFactory.createSocket(raw, endpoint.host, endpoint.port, true) as SSLSocket
                tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                tls.soTimeout = DIAL_TIMEOUT_MS
                tls.startHandshake()
                tls.soTimeout = 0
                tls
            }
        } catch (error: Exception) {
            runCatching { raw.close() }
            throw error
        }
    }

    /** Tries the endpoints in order and joins the first that answers with its pin. */
    val tls = RelayJoiner { endpoints, token, scope ->
        var last: Exception = IOException("no relay endpoints")
        for (endpoint in endpoints) {
            try {
                val frames = FrameChannel(dialOne(endpoint), scope)
                frames.send(joinFrame(token))
                frames.startKeepalive()
                return@RelayJoiner frames
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                last = error
            }
        }
        throw last
    }

    fun joinFrame(token: String): ByteArray =
        JsonObject(mapOf("v" to JsonPrimitive(1), "token" to JsonPrimitive(token))).toString().encodeToByteArray()

    /** A frame from the relay itself (first byte 0x00), or null for one from the other app. */
    fun control(frame: ByteArray): RelayControl? {
        if (frame.isEmpty() || frame[0] != 0.toByte()) return null
        val value = runCatching { Json.parseToJsonElement(frame.decodeToString(1, frame.size)) }.getOrNull() as? JsonObject
            ?: return RelayControl.End("bad_control")
        val type = (value["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (type) {
            "wait" -> RelayControl.Wait
            "peer" -> RelayControl.Peer
            "gone" -> RelayControl.Gone
            "end" -> RelayControl.End((value["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(40) ?: "ended")
            else -> RelayControl.End("bad_control")
        }
    }

    fun parseEndpoints(value: Any?): List<RelayEndpoint> {
        val items = value as? JsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val host = (obj["host"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(HOST::matches) ?: return@mapNotNull null
            val port = (obj["port"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 1..65_535 } ?: return@mapNotNull null
            val pin = (obj["pin"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(PIN::matches) ?: return@mapNotNull null
            RelayEndpoint(host, port, pin)
        }.take(4)
    }
}
