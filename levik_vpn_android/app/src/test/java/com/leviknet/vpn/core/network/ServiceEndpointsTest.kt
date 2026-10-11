package com.leviknet.vpn.core.network

import com.leviknet.vpn.core.auth.ExternalUriPolicy
import com.leviknet.vpn.core.platform.AppLinks
import com.leviknet.vpn.core.platform.CabinetTarget
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceEndpointsTest {
    private val body = fixture("levik-config-v1.json")
    private val signature = fixture("levik-config-v1.json.sig").toString(Charsets.UTF_8)
    private val testKeys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    @After
    fun reset() = ServiceEndpoints.use(null)

    @Test
    fun `accepts the published document with the key built into the app`() {
        val document = ServiceEndpoints.verify(body, signature)
        assertNotNull(document)
        assertTrue(document!!.sequence >= 1)
        assertEquals("https://api.cloudnote-sync.xyz", document.apiOrigins.first())
        assertEquals("https://sub.cloudnote-sync.xyz", document.subscriptionOrigins.first())
    }

    @Test
    fun `rejects a document changed after signing`() {
        val forged = String(body, Charsets.UTF_8).replaceFirst("cloudnote-sync.xyz", "attacker.example")
        assertNull(ServiceEndpoints.verify(forged.toByteArray(), signature))
        assertNull(ServiceEndpoints.verify(body, "not base64!"))
    }

    @Test
    fun `keeps only clean HTTPS origins and JSON mirrors`() {
        val (document, signed) = signed(
            """{"schema":1,"sequence":7,"apiOrigins":["https://api.reserve.example","https://api.reserve.example",""" +
                """"http://plain.example","https://x.example/path","https://x.example:8443"],""" +
                """"webOrigin":"https://site.example","configUrls":["https://mirror.example/levik-config-v1.json",""" +
                """"https://mirror.example/other.txt"]}""",
        )
        assertEquals(
            ServiceEndpointsDocument(
                sequence = 7,
                apiOrigins = listOf("https://api.reserve.example"),
                webOrigin = "https://site.example",
                subscriptionOrigins = emptyList(),
                configUrls = listOf("https://mirror.example/levik-config-v1.json"),
            ),
            ServiceEndpoints.verify(document, signed, testKeys.public),
        )
        assertNull(ServiceEndpoints.verify(document, signed))
    }

    @Test
    fun `rejects invalid documents`() {
        listOf(
            """{"schema":2,"sequence":7,"apiOrigins":["https://a.example"],"webOrigin":"https://site.example"}""",
            """{"schema":1,"sequence":0,"apiOrigins":["https://a.example"],"webOrigin":"https://site.example"}""",
            """{"schema":1,"sequence":"7","apiOrigins":["https://a.example"],"webOrigin":"https://site.example"}""",
            """{"schema":1,"sequence":7,"apiOrigins":["http://a.example"],"webOrigin":"https://site.example"}""",
            """{"schema":1,"sequence":7,"apiOrigins":["https://a.example"],"webOrigin":"https://site.example/path"}""",
        ).forEach { text ->
            val (document, signed) = signed(text)
            assertNull(text, ServiceEndpoints.verify(document, signed, testKeys.public))
        }
    }

    @Test
    fun `signed hosts come first and the website follows them`() {
        ServiceEndpoints.use(
            ServiceEndpointsDocument(3, listOf("https://api.reserve.example"), "https://site.example", emptyList(), emptyList()),
        )
        assertEquals(
            listOf("https://api.reserve.example") + ServiceEndpoints.BUILT_IN.apiOrigins,
            ServiceEndpoints.current().apiOrigins,
        )
        assertEquals(ServiceEndpoints.BUILT_IN.configUrls, ServiceEndpoints.current().configUrls)
        assertEquals("https://site.example/dashboard/plans", AppLinks.cabinetFallbackUrl(CabinetTarget.PLANS))
        assertTrue(AppLinks.isHandoffUrl("https://site.example/handoff?token=${"a".repeat(43)}"))
        assertTrue(AppLinks.isHandoffUrl("https://leviknet.org/handoff?token=${"a".repeat(43)}"))
        assertTrue(ExternalUriPolicy.isAllowedHttpsHost("www.site.example"))
        assertFalse(ExternalUriPolicy.isAllowedHttpsHost("evil.example"))
    }

    @Test
    fun `skips broken mirrors and older documents`() {
        val fetcher = EndpointsFetcher { url, _ ->
            when {
                url.startsWith("https://blocked.example") -> throw IOException("blocked")
                url.startsWith("https://missing.example") -> null
                url.endsWith(".sig") -> signature.toByteArray()
                else -> body
            }
        }
        val urls = listOf("https://blocked.example/a.json", "https://missing.example/a.json", "https://good.example/a.json")
        assertEquals("https://api.cloudnote-sync.xyz", fetchEndpointsDocument(urls, 1, fetcher)?.document?.apiOrigins?.first())
        assertNull(fetchEndpointsDocument(urls, 1_000, fetcher))
    }

    @Test
    fun `stores the signed bytes, verifies them on load and checks at most twice a day`() {
        var requests = 0
        val fetcher = EndpointsFetcher { url, _ ->
            requests++
            if (url.endsWith(".sig")) signature.toByteArray() else body
        }
        val file = File(Files.createTempDirectory("levik-endpoints").toFile(), "service-endpoints.json")
        val store = ServiceEndpointsStore(file, fetcher)
        assertTrue(store.refreshIfDue(12 * 60 * 60 * 1000L))
        assertFalse(store.refreshIfDue(12 * 60 * 60 * 1000L))
        assertFalse(store.refresh(60_000L))
        assertEquals(2, requests)
        assertFalse(store.refresh())

        ServiceEndpoints.use(null)
        val reloaded = ServiceEndpointsStore(file, fetcher)
        reloaded.load()
        assertTrue(ServiceEndpoints.current().sequence >= 1)
        assertFalse(reloaded.refreshIfDue(12 * 60 * 60 * 1000L))

        val stored = Json.parseToJsonElement(file.readText()) as JsonObject
        val decoded = String(Base64.getDecoder().decode((stored["body"] as JsonPrimitive).content), Charsets.UTF_8)
        val forged = Base64.getEncoder().encodeToString(decoded.replaceFirst("cloudnote-sync.xyz", "attacker.example").toByteArray())
        file.writeText(JsonObject(stored + ("body" to JsonPrimitive(forged))).toString())
        ServiceEndpoints.use(null)
        ServiceEndpointsStore(file, fetcher).load()
        assertEquals(0L, ServiceEndpoints.current().sequence)
    }

    private fun signed(text: String): Pair<ByteArray, String> {
        val bytes = text.toByteArray()
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(testKeys.private)
            update(bytes)
        }
        return bytes to Base64.getEncoder().encodeToString(signer.sign())
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { name }.use { it.readBytes() }
}
