package com.leviknet.vpn.core.platform

import com.leviknet.vpn.core.network.EndpointsFetcher
import com.leviknet.vpn.core.network.ServiceEndpoints
import com.leviknet.vpn.data.SplitTunnelMode
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCatalogTest {
    private val testKeys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    private fun document(sequence: Long, extraApp: String = "") = """
        {
          "schema": 1,
          "sequence": $sequence,
          "categories": [
            {"id": "banks", "title": {"ru": "Банки", "en": "Banks"}},
            {"id": "BAD", "title": {"ru": "x", "en": "x"}}
          ],
          "apps": [
            {"id": "sber", "category": "banks", "name": {"ru": "Сбер", "en": "Sber"},
             "android": ["ru.sberbankmobile", "not a package"], "domains": ["Sberbank.ru", "-bad-"], "suggestDirect": true},
            {"id": "vtb", "category": "banks", "name": {"ru": "ВТБ", "en": "VTB"},
             "android": ["ru.vtb24.mobilebanking.android"], "suggestDirect": true},
            {"id": "quiet", "category": "banks", "name": {"ru": "Тихий", "en": "Quiet"},
             "android": ["com.example.quiet"], "suggestDirect": false},
            {"id": "orphan", "category": "games", "name": {"ru": "x", "en": "x"}, "android": ["com.example.orphan"]},
            {"id": "empty", "category": "banks", "name": {"ru": "x", "en": "x"}, "android": [], "domains": []}
            $extraApp
          ]
        }
    """.trimIndent()

    @Test
    fun `the catalog built into the app is signed with the Levik key`() {
        val body = File("src/main/assets/${AppCatalogPolicy.FILE_NAME}").readBytes()
        val signature = File("src/main/assets/${AppCatalogPolicy.FILE_NAME}.sig").readText()
        val catalog = AppCatalogPolicy.verify(body, signature)
        assertNotNull(catalog)
        assertTrue(catalog!!.apps.any { it.id == "sber" && "ru.sberbankmobile" in it.androidPackages && it.suggestDirect })
        // The copy in the app is the published one.
        val published = File("../../config/${AppCatalogPolicy.FILE_NAME}")
        if (published.isFile) assertArrayEquals(published.readBytes(), body)
    }

    @Test
    fun `parses the catalog and drops invalid entries`() {
        val (body, signature) = signed(document(3))
        val catalog = AppCatalogPolicy.verify(body, signature, testKeys.public)!!
        assertEquals(3L, catalog.sequence)
        assertEquals(listOf("banks"), catalog.categories.map(CatalogCategory::id))
        assertEquals(listOf("sber", "vtb", "quiet"), catalog.apps.map(CatalogApp::id))
        val sber = catalog.apps.first()
        assertEquals(listOf("ru.sberbankmobile"), sber.androidPackages)
        assertEquals(listOf("sberbank.ru"), sber.domains)
        assertEquals("Сбер", sber.name(russian = true))
        assertEquals("Sber", sber.name(russian = false))
    }

    @Test
    fun `rejects a wrong signature or edited bytes`() {
        val (body, signature) = signed(document(3))
        assertNull(AppCatalogPolicy.verify(body, signature))
        val edited = body.copyOf().also { it[it.size - 3] = 'X'.code.toByte() }
        assertNull(AppCatalogPolicy.verify(edited, signature, testKeys.public))
        assertNull(AppCatalogPolicy.verify(body, "not base64!", testKeys.public))
        val (unsupported, unsupportedSignature) = signed(document(3).replace("\"schema\": 1", "\"schema\": 2"))
        assertNull(AppCatalogPolicy.verify(unsupported, unsupportedSignature, testKeys.public))
    }

    @Test
    fun `knows which apps go through the tunnel`() {
        assertTrue(AppCatalogPolicy.goesThroughVpn(SplitTunnelMode.OFF, setOf("a.b"), "a.b"))
        assertFalse(AppCatalogPolicy.goesThroughVpn(SplitTunnelMode.DISALLOWED, setOf("a.b"), "a.b"))
        assertTrue(AppCatalogPolicy.goesThroughVpn(SplitTunnelMode.DISALLOWED, setOf("c.d"), "a.b"))
        assertTrue(AppCatalogPolicy.goesThroughVpn(SplitTunnelMode.ALLOWED, setOf("a.b"), "a.b"))
        assertFalse(AppCatalogPolicy.goesThroughVpn(SplitTunnelMode.ALLOWED, setOf("c.d"), "a.b"))
    }

    @Test
    fun `lets one app bypass the VPN and keeps the others`() {
        assertEquals(
            SplitTunnelMode.DISALLOWED to setOf("bank.app"),
            AppCatalogPolicy.routeDirect(SplitTunnelMode.OFF, setOf("old.hidden"), "bank.app"),
        )
        assertEquals(
            SplitTunnelMode.DISALLOWED to setOf("other.app", "bank.app"),
            AppCatalogPolicy.routeDirect(SplitTunnelMode.DISALLOWED, setOf("other.app"), "bank.app"),
        )
        assertEquals(
            SplitTunnelMode.ALLOWED to setOf("other.app"),
            AppCatalogPolicy.routeDirect(SplitTunnelMode.ALLOWED, setOf("other.app", "bank.app"), "bank.app"),
        )
    }

    @Test
    fun `suggests only installed banks that are still in the tunnel and not answered`() {
        val catalog = AppCatalogPolicy.parse(document(1).toByteArray())!!
        val installed = setOf("ru.sberbankmobile", "ru.vtb24.mobilebanking.android", "com.example.quiet")
        fun next(mode: SplitTunnelMode = SplitTunnelMode.OFF, packages: Set<String> = emptySet(), answered: Set<String> = emptySet()) =
            AppCatalogPolicy.nextSuggestion(catalog, mode, packages, answered, installed::contains)

        assertEquals("ru.sberbankmobile", next()?.packageName)
        assertEquals("ru.vtb24.mobilebanking.android", next(answered = setOf("ru.sberbankmobile"))?.packageName)
        assertEquals(
            "ru.vtb24.mobilebanking.android",
            next(SplitTunnelMode.DISALLOWED, setOf("ru.sberbankmobile"))?.packageName,
        )
        assertNull(next(answered = setOf("ru.sberbankmobile", "ru.vtb24.mobilebanking.android")))
        assertNull(next(SplitTunnelMode.ALLOWED, setOf("com.example.quiet")))
        assertNull(AppCatalogPolicy.nextSuggestion(catalog, SplitTunnelMode.OFF, emptySet(), emptySet()) { false })
        assertNull(AppCatalogPolicy.nextSuggestion(null, SplitTunnelMode.OFF, emptySet(), emptySet()) { true })
    }

    @Test
    fun `the catalog sits next to the service host list on every mirror`() {
        assertEquals(
            listOf(
                "https://api.example.org/downloads/config/${AppCatalogPolicy.FILE_NAME}",
                "https://cdn.example.net/gh/levik@main/config/${AppCatalogPolicy.FILE_NAME}",
            ),
            AppCatalogPolicy.urls(
                listOf(
                    "https://api.example.org/downloads/config/levik-config-v1.json",
                    "https://cdn.example.net/gh/levik@main/config/levik-config-v1.json",
                ),
            ),
        )
    }

    @Test
    fun `takes a newer signed catalog from the mirrors, skips stale ones and keeps it`() {
        val directory = Files.createTempDirectory("catalog").toFile()
        val file = File(directory, "app-catalog.json")
        val builtIn = signed(document(2))
        val urls = AppCatalogPolicy.urls(ServiceEndpoints.current().configUrls)
        val alfa = """,{"id": "alfa", "category": "banks", "name": {"ru": "Альфа", "en": "Alfa"}, "android": ["ru.alfabank.mobile.android"], "suggestDirect": true}"""
        val newer = signed(document(3, alfa))
        fun store(served: Map<String, Pair<ByteArray, String>>) =
            AppCatalogStore(file, { SignedCatalog(builtIn.first, builtIn.second) }, mirrors(served), testKeys.public)

        // The same sequence as ours: the check ends at the first valid mirror.
        val same = store(mapOf(urls[0] to signed(document(2)), urls[1] to newer)).apply { load() }
        assertEquals(2L, same.catalog.value?.sequence)
        assertFalse(same.refresh())

        // A down mirror and a stale one (jsDelivr caches for hours) are skipped.
        val skipping = store(mapOf(urls[1] to signed(document(1)), urls[2] to newer)).apply { load() }
        assertTrue(skipping.refresh())
        assertEquals(3L, skipping.catalog.value?.sequence)

        // Kept on disk and verified again on the next start.
        val restarted = store(emptyMap()).apply { load() }
        assertEquals(3L, restarted.catalog.value?.sequence)
        assertTrue(restarted.catalog.value!!.apps.any { it.id == "alfa" })

        // An edited copy on disk is ignored; the built-in one is used.
        file.writeText(
            file.readText().replace(
                Base64.getEncoder().encodeToString(newer.first),
                Base64.getEncoder().encodeToString(document(9).toByteArray()),
            ),
        )
        assertEquals(2L, store(emptyMap()).apply { load() }.catalog.value?.sequence)
        directory.deleteRecursively()
    }

    private fun mirrors(served: Map<String, Pair<ByteArray, String>>) = EndpointsFetcher { url, _ ->
        val document = served[url.removeSuffix(".sig")]
        when {
            document == null -> null
            url.endsWith(".sig") -> document.second.toByteArray()
            else -> document.first
        }
    }

    private fun signed(text: String): Pair<ByteArray, String> {
        val bytes = text.toByteArray()
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(testKeys.private)
            update(bytes)
        }
        return bytes to Base64.getEncoder().encodeToString(signer.sign())
    }
}
