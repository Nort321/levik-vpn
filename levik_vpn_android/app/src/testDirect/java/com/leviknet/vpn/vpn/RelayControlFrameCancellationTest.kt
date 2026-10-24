package com.leviknet.vpn.vpn

import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RelayControlFrameCancellationTest(private val provider: String) {
    @Test
    fun `cancelling a silent native handshake ends its read at the next socket poll`() = runBlocking {
        val waitingForNative = CountDownLatch(1)
        val socketPoll = CountDownLatch(1)
        val reads = AtomicInteger()
        val cancelledRead = AtomicBoolean()
        val input = object : InputStream() {
            override fun read(): Int = when (reads.incrementAndGet()) {
                1 -> '{'.code // A native peer may go silent partway through a frame too.
                2 -> {
                    waitingForNative.countDown()
                    assertTrue(socketPoll.await(2, TimeUnit.SECONDS))
                    throw SocketTimeoutException("native peer is silent")
                }
                else -> -1 // Keep a regressed test bounded instead of waiting for two minutes.
            }
        }
        val job = launch(Dispatchers.IO) {
            val context = currentCoroutineContext()
            try {
                readFrame(input) { context.ensureActive() }
            } catch (error: CancellationException) {
                cancelledRead.set(true)
                throw error
            }
        }
        try {
            assertTrue(waitingForNative.await(2, TimeUnit.SECONDS))
            job.cancel()
            socketPoll.countDown()
            withTimeout(2_000L) { job.join() }
            assertTrue("Cancellation must propagate from the polling read", cancelledRead.get())
            assertEquals("A cancelled handshake must not start another read", 2, reads.get())
        } finally {
            socketPoll.countDown()
            job.cancelAndJoin()
        }
    }

    @Test
    fun `live handshake keeps partial control bytes across socket timeout polls`() {
        val bytes = "{\"version\":2}\n".encodeToByteArray()
        var position = 0
        var timeoutNext = true
        val input = object : InputStream() {
            override fun read(): Int {
                timeoutNext = !timeoutNext
                if (timeoutNext) throw SocketTimeoutException("still waiting")
                return if (position < bytes.size) bytes[position++].toInt() else -1
            }
        }

        val payload = readFrame(input)

        assertEquals("{\"version\":2}", payload)
    }

    private fun readFrame(input: InputStream, checkCancellation: () -> Unit = {}): String =
        when (provider) {
            "VK" -> {
                val reader = LocalSocketFrameReader(input, 1_024, { null }, { 0L })
                try {
                    reader.readFrame(120_000L, checkCancellation) { true }.also {
                        assertTrue(it.fileDescriptors.isEmpty())
                    }.payload
                } finally {
                    reader.closePendingDescriptors()
                }
            }
            "Yandex" -> {
                val reader = YandexLocalSocketFrameReader(input, 1_024, { null }, { 0L })
                try {
                    reader.readFrame(120_000L, checkCancellation) { true }.also {
                        assertTrue(it.fileDescriptors.isEmpty())
                    }.payload
                } finally {
                    reader.closePendingDescriptors()
                }
            }
            else -> error("Unknown test provider")
        }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun providers(): List<Array<String>> = listOf(arrayOf("VK"), arrayOf("Yandex"))
    }
}
