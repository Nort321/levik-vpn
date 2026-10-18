package com.leviknet.vpn.core.network

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiEndpointsTest {
    @After
    fun reset() = ServiceEndpoints.use(null)

    @Test(timeout = 5_000)
    fun `allow-list fallback wins while public host is blocked and cancels its socket`() {
        val blockedStarted = CountDownLatch(1)
        lateinit var blocked: FakeConnection
        val endpoints = ApiEndpoints(DEFAULT_API_ORIGIN, probeBudgetNanos = TimeUnit.MILLISECONDS.toNanos(500), openHealthConnection = { url ->
            when (url.host) {
                "api.cloudnote-sync.xyz" -> FakeConnection(url) {
                    blockedStarted.countDown()
                    assertTrue(disconnectedSignal.await(3, TimeUnit.SECONDS))
                    throw IOException("cancelled")
                }.also { blocked = it }
                "leva.levikfartik.ru" -> FakeConnection(url) {
                    assertTrue(blockedStarted.await(3, TimeUnit.SECONDS))
                    200
                }
                else -> FakeConnection(url, status = { 503 })
            }
        })

        assertEquals(ServiceEndpoints.ALLOWLIST_API_ORIGIN, endpoints.resolve().toString())
        assertTrue(blocked.disconnected.get())
        assertEquals("GET", blocked.requestMethod)
        assertEquals("/api/health", blocked.url.path)
        assertFalse(blocked.instanceFollowRedirects)
        assertEquals(mapOf("Accept" to listOf("application/json")), blocked.requestProperties)
    }

    @Test(timeout = 5_000)
    fun `prefers a healthy ordinary origin over an earlier healthy allow-list fallback`() {
        val primaryStarted = CountDownLatch(1)
        val fallbackStarted = CountDownLatch(1)
        val releasePrimary = CountDownLatch(1)
        val endpoints = ApiEndpoints(
            DEFAULT_API_ORIGIN,
            probeBudgetNanos = TimeUnit.SECONDS.toNanos(3),
            openHealthConnection = { url ->
                when (url.host) {
                    "api.cloudnote-sync.xyz" -> FakeConnection(url) {
                        primaryStarted.countDown()
                        assertTrue(releasePrimary.await(3, TimeUnit.SECONDS))
                        200
                    }
                    "leva.levikfartik.ru" -> FakeConnection(url) {
                        fallbackStarted.countDown()
                        200
                    }
                    else -> FakeConnection(url, status = { 503 })
                }
            },
        )
        val caller = Executors.newSingleThreadExecutor()
        try {
            val result = caller.submit<URL> { endpoints.resolve() }
            assertTrue(primaryStarted.await(1, TimeUnit.SECONDS))
            assertTrue(fallbackStarted.await(1, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { result.get(250, TimeUnit.MILLISECONDS) }
            releasePrimary.countDown()
            assertEquals(DEFAULT_API_ORIGIN, result.get(1, TimeUnit.SECONDS).toString())
        } finally {
            releasePrimary.countDown()
            caller.shutdownNow()
        }
    }

    @Test(timeout = 5_000)
    fun `returns a healthy allow-list fallback as soon as ordinary probes have failed`() {
        val endpoints = ApiEndpoints(DEFAULT_API_ORIGIN, openHealthConnection = { url ->
            FakeConnection(url, status = { if (url.host == "leva.levikfartik.ru") 200 else 503 })
        })

        assertEquals(ServiceEndpoints.ALLOWLIST_API_ORIGIN, endpoints.resolve().toString())
    }

    @Test
    fun `requires a successful bounded JSON health body before trusting an origin`() {
        for ((status, type, body) in listOf(
            Triple(503, "application/json", """{"ok":true}"""),
            Triple(200, "text/html", """{"ok":true}"""),
            Triple(200, "application/json", "not-json"),
            Triple(200, "application/json", """{"ok":false}"""),
            Triple(200, "application/json", """{"ok":"true"}"""),
            Triple(200, "application/json", """{"ok":true,"padding":"${"x".repeat(1_024)}"}"""),
        )) {
            val endpoints = ApiEndpoints(DEFAULT_API_ORIGIN, openHealthConnection = { url ->
                FakeConnection(url, body, type, { status })
            })
            assertThrows(IOException::class.java) { endpoints.resolve() }
        }
    }

    @Test
    fun `uses a verified origin for at most sixty seconds and invalidates it explicitly`() {
        var now = 1L
        val opened = AtomicInteger()
        val endpoints = ApiEndpoints(
            DEFAULT_API_ORIGIN,
            openHealthConnection = { url ->
                opened.incrementAndGet()
                FakeConnection(url, status = { if (url.host == "leva.levikfartik.ru") 200 else 503 })
            },
            nanoTime = { now },
        )
        val first = endpoints.resolve()
        val initialRequests = opened.get()
        now += 59_999_999_999L
        assertEquals(first, endpoints.resolve())
        assertEquals(initialRequests, opened.get())
        now++
        assertEquals(first, endpoints.resolve())
        assertEquals(initialRequests * 2, opened.get())
        endpoints.invalidate()
        assertEquals(first, endpoints.resolve())
        assertEquals(initialRequests * 3, opened.get())
    }

    @Test
    fun `explicit development origin neither probes nor refreshes production origins`() {
        val endpoints = ApiEndpoints(
            "https://development.example",
            refreshEndpoints = { throw AssertionError("Production endpoints must stay isolated") },
            openHealthConnection = { throw AssertionError("Development override must never probe production") },
        )

        assertEquals("https://development.example", endpoints.resolve().toString())
        endpoints.invalidate()
        assertEquals("https://development.example", endpoints.resolve().toString())
    }

    @Test
    fun `releases earlier connections if a later connection factory fails`() {
        lateinit var created: FakeConnection
        val endpoints = ApiEndpoints(DEFAULT_API_ORIGIN, openHealthConnection = { url ->
            if (url.host == "api.cloudnote-sync.xyz") {
                FakeConnection(url).also { created = it }
            } else {
                throw IllegalStateException("factory failed")
            }
        })

        assertThrows(IllegalStateException::class.java) { endpoints.resolve() }
        assertTrue(created.disconnected.get())
    }

    @Test(timeout = 5_000)
    fun `runs no more than four health probes concurrently`() {
        ServiceEndpoints.use(
            ServiceEndpointsDocument(
                1,
                (1..8).map { "https://mirror$it.example" },
                "https://leviknet.org",
                emptyList(),
                emptyList(),
            ),
        )
        val fourStarted = CountDownLatch(4)
        val release = CountDownLatch(1)
        val running = AtomicInteger()
        val maximum = AtomicInteger()
        val endpoints = ApiEndpoints(DEFAULT_API_ORIGIN, openHealthConnection = { url ->
            FakeConnection(url) {
                val active = running.incrementAndGet()
                maximum.updateAndGet { maxOf(it, active) }
                fourStarted.countDown()
                try {
                    assertTrue(release.await(3, TimeUnit.SECONDS))
                    if (url.host == "leva.levikfartik.ru") 200 else 503
                } finally {
                    running.decrementAndGet()
                }
            }
        })
        val caller = Executors.newSingleThreadExecutor()
        try {
            val result = caller.submit<URL> { endpoints.resolve() }
            assertTrue(fourStarted.await(3, TimeUnit.SECONDS))
            assertEquals(4, maximum.get())
            release.countDown()
            assertEquals(ServiceEndpoints.ALLOWLIST_API_ORIGIN, result.get(3, TimeUnit.SECONDS).toString())
            assertEquals(4, maximum.get())
        } finally {
            release.countDown()
            caller.shutdownNow()
        }
    }

    @Test
    fun `refreshes signed endpoints only after all known health probes fail`() {
        val refreshed = AtomicBoolean(false)
        val endpoints = ApiEndpoints(
            DEFAULT_API_ORIGIN,
            refreshEndpoints = {
                refreshed.set(true)
                ServiceEndpoints.use(
                    ServiceEndpointsDocument(
                        2, listOf("https://new.example"), "https://leviknet.org", emptyList(), emptyList(),
                    ),
                )
                true
            },
            openHealthConnection = { url ->
                FakeConnection(url, status = { if (url.host == "new.example") 200 else 503 })
            },
        )

        assertEquals("https://new.example", endpoints.resolve().toString())
        assertTrue(refreshed.get())
    }

    private class FakeConnection(
        url: URL,
        private val body: String = """{"ok":true}""",
        private val responseType: String = "application/json; charset=utf-8",
        private val status: FakeConnection.() -> Int = { 200 },
    ) : HttpsURLConnection(url) {
        val disconnected = AtomicBoolean(false)
        val disconnectedSignal = CountDownLatch(1)

        override fun getResponseCode(): Int = status()
        override fun getContentType(): String = responseType
        override fun getContentLengthLong(): Long = body.encodeToByteArray().size.toLong()
        override fun getInputStream() = ByteArrayInputStream(body.encodeToByteArray())
        override fun disconnect() {
            disconnected.set(true)
            disconnectedSignal.countDown()
        }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}
