package com.leviknet.vpn.vpn

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android UDS + OkHttp TLS cancellation, with no account or Internet traffic. */
@RunWith(AndroidJUnit4::class)
class SmartRouteCancellationInstrumentedTest {
    @Test(timeout = 10_000) fun closingFactoryWakesBlockedReaderAndRejectsNewSockets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.filesDir, "close-${UUID.randomUUID().toString().take(6)}.sock")
        val bound = LocalSocket().apply { bind(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM)) }
        val server = LocalServerSocket(bound.fileDescriptor)
        val factory = PrivateUnixSocketFactory(path.path)
        val reader = Executors.newSingleThreadExecutor()
        try {
            factory.createSocket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", 9))
                server.accept().use {
                    val started = CountDownLatch(1)
                    val result = reader.submit<Boolean> {
                        started.countDown()
                        try { client.inputStream.read(); true } catch (_: IOException) { true }
                    }
                    assertTrue(started.await(1, TimeUnit.SECONDS))
                    Thread.sleep(50)
                    factory.close()
                    assertTrue(result.get(1, TimeUnit.SECONDS))
                    assertTrue(client.isClosed)
                    assertThrows(IOException::class.java) { factory.createSocket() }
                }
            }
        } finally {
            factory.close()
            runCatching { server.close() }
            runCatching { bound.close() }
            reader.shutdownNow()
            path.delete()
        }
    }

    @Test(timeout = 20_000) fun cancellingTlsHandshakeOnPrivateSocketCompletes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.filesDir, "cancel-${UUID.randomUUID().toString().take(6)}.sock")
        val bound = LocalSocket().apply { bind(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM)) }
        val server = LocalServerSocket(bound.fileDescriptor)
        val peers = ConcurrentLinkedQueue<LocalSocket>()
        val connected = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newCachedThreadPool { task -> Thread(task, "probe-blackhole").apply { isDaemon = true } }
        pool.submit {
            val peer = server.accept()
            peers.add(peer)
            val input = peer.inputStream
            var suffix = ""
            while (!suffix.endsWith("\r\n\r\n")) {
                val next = input.read()
                if (next < 0) return@submit
                suffix = (suffix + next.toChar()).takeLast(4)
            }
            peer.outputStream.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            peer.outputStream.flush()
            check(input.read() == 0x16) { "TLS ClientHello was not sent" }
            connected.countDown()
            // Accept the proxy CONNECT, then deliberately blackhole TLS.
            release.await(15, TimeUnit.SECONDS)
        }
        try {
            runBlocking {
                SmartRouteProber(listOf(SmartRouteProbeConfig.Candidate("local", "local", path.path))).use { prober ->
                    val request = async(Dispatchers.IO) { prober.verifyData("local") }
                    assertTrue("Proxy CONNECT was not received", connected.await(5, TimeUnit.SECONDS))
                    val started = System.nanoTime()
                    request.cancelAndJoin()
                    assertTrue("Cancellation waited for blackholed TLS", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
                }
            }
        } finally {
            release.countDown()
            peers.forEach { runCatching { it.close() } }
            runCatching { server.close() }
            runCatching { bound.close() }
            pool.shutdownNow()
            path.delete()
        }
    }
}
