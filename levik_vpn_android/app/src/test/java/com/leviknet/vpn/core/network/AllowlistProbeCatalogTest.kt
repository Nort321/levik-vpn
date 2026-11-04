package com.leviknet.vpn.core.network

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowlistProbeCatalogTest {
    private fun probe(url: String, kind: String, minBytes: Int) =
        """{"url":"$url","kind":"$kind","minBytes":$minBytes}"""

    private fun document(
        sequence: Long = 7,
        foreignBytes: Int = 32768,
        defaultDomestic: String = "\"ozon\",\"yandex\",\"avito\",\"vk\"",
        scopes: String = """{"RU-KOS":{"domestic":["gosuslugi","yandex","vk"],"foreign":["python","mozilla","ovh","wikipedia"]},"RU-KOS/3216":{"domestic":["gosuslugi","yandex","mailru"],"foreign":["python","mozilla","ovh","wikipedia"]},"RU-BAD":{"domestic":["ozon"],"foreign":[]},"moscow":{"domestic":["ozon","yandex","vk"],"foreign":["python","mozilla","ovh","wikipedia"]}}""",
        extraProbe: String = "",
    ) = """
        {"schema":1,"sequence":$sequence,"generatedAt":1,"expiresAt":9999999999999,
         "probes":{
           "ozon":${probe("https://www.ozon.ru/", "domestic", 0)},
           "yandex":${probe("https://ya.ru/", "domestic", 0)},
           "avito":${probe("https://www.avito.ru/", "domestic", 0)},
           "vk":${probe("https://vk.com/", "domestic", 0)},
           "gosuslugi":${probe("https://www.gosuslugi.ru/", "domestic", 0)},
           "mailru":${probe("https://mail.ru/", "domestic", 0)},
           "cloudflare":${probe("https://speed.cloudflare.com/__down?bytes=32768", "foreign", foreignBytes)},
           "mozilla":${probe("https://www.mozilla.org/en-US/", "foreign", 32768)},
           "ovh":${probe("https://proof.ovh.net/files/1Mb.dat", "foreign", 32768)},
           "github":${probe("https://github.com/", "foreign", 32768)},
           "wikipedia":${probe("https://www.wikipedia.org/", "foreign", 32768)},
           "python":${probe("https://www.python.org/", "foreign", 32768)},
           "jsdelivr":${probe("https://cdn.jsdelivr.net/npm/jquery@3.7.1/dist/jquery.js", "foreign", 32768)}$extraProbe
         },
         "default":{"domestic":[$defaultDomestic],"foreign":["cloudflare","mozilla","ovh","github","wikipedia"]},
         "scopes":$scopes,
         "candidates":["gosuslugi","mailru","python","jsdelivr","unknown"]}
    """.trimIndent().toByteArray()

    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun sign(bytes: ByteArray): String = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").apply { initSign(keys.private); update(bytes) }.sign(),
    )

    @Test fun picksTheOperatorThenTheRegionThenTheDefault() {
        val catalog = AllowlistProbeCatalog.parse(document())!!
        assertEquals(listOf("gosuslugi", "yandex", "mailru", "python", "mozilla", "ovh", "wikipedia"),
            catalog.probesFor(AllowlistProbeScope("RU-KOS", 3216)).map { it.id })
        assertEquals("RU-KOS", catalog.setNameFor(AllowlistProbeScope("RU-KOS", 8359)))
        assertEquals(listOf("gosuslugi", "yandex", "vk"), catalog.probesFor(AllowlistProbeScope("RU-KOS", null)).filter { it.domestic }.map { it.id })
        assertEquals("default", catalog.setNameFor(AllowlistProbeScope("RU-MOW", null)))
        assertEquals("default", catalog.setNameFor(null))
        // Malformed or too small region sets are skipped, not trusted.
        assertEquals("default", catalog.setNameFor(AllowlistProbeScope("RU-BAD", null)))
    }

    @Test fun candidatesNeverRepeatTheDecidingProbes() {
        val catalog = AllowlistProbeCatalog.parse(document())!!
        val scope = AllowlistProbeScope("RU-KOS", null)
        repeat(20) { seed ->
            val picked = catalog.candidatesFor(scope, 4, Random(seed)).map { it.id }
            assertTrue(picked.none { it in setOf("gosuslugi", "python") })
            assertTrue(picked.all { it in setOf("mailru", "jsdelivr") })
        }
        assertEquals(emptyList<AllowlistProbe>(), catalog.candidatesFor(scope, 0))
    }

    @Test fun refusesCatalogsThatWouldWeakenDetection() {
        assertNotNull(AllowlistProbeCatalog.parse(document()))
        // A foreign site that counts after a few kilobytes is exactly the Beeline trap.
        assertNull(AllowlistProbeCatalog.parse(document(foreignBytes = 4096)))
        assertNull(AllowlistProbeCatalog.parse(document(defaultDomestic = "\"ozon\",\"yandex\"")))
        assertNull(AllowlistProbeCatalog.parse(document(defaultDomestic = "\"ozon\",\"yandex\",\"github\"")))
        assertNull(AllowlistProbeCatalog.parse(document(extraProbe = ",\"local\":${probe("http://192.168.1.1/", "domestic", 0)}")))
        assertNull(AllowlistProbeCatalog.parse(document(sequence = 0)))
        for (url in listOf("https://10.0.0.1/", "https://ya.ru:8443/", "https://user@ya.ru/", "https://ya.ru/#a", "https://Ya.ru/", "https://localhost/")) {
            assertFalse(url, AllowlistProbeCatalog.safeUrl(url))
        }
        assertTrue(AllowlistProbeCatalog.safeUrl("https://cdn.jsdelivr.net/npm/jquery@3.7.1/dist/jquery.js"))
    }

    @Test fun acceptsOnlyTheSignedBytes() {
        val bytes = document()
        val signature = sign(bytes)
        assertNotNull(AllowlistProbeCatalog.verify(bytes, signature, keys.public))
        val edited = document(scopes = "{}")
        assertNull(AllowlistProbeCatalog.verify(edited, signature, keys.public))
        // The production key did not sign it.
        assertNull(AllowlistProbeCatalog.verify(bytes, signature))
    }

    @Test fun builtInSetMatchesTheClassifierLimits() {
        val probes = AllowlistProbeCatalog.BUILT_IN.probesFor(null)
        assertEquals(4, probes.count { it.domestic })
        assertEquals(5, probes.count { !it.domestic && it.minBytes >= AllowlistProbeCatalog.MIN_FOREIGN_BYTES })
    }

    @Test fun readsScopeFromRegionTokenClaims() {
        fun token(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray()) + ".sig"
        assertEquals(AllowlistProbeScope("RU-KOS", 3216), AllowlistProbeScope.fromRegionToken(token("""{"region":"RU-KOS","asn":3216}""")))
        assertEquals(AllowlistProbeScope("RU-MOW", null), AllowlistProbeScope.fromRegionToken(token("""{"region":"RU-MOW"}""")))
        assertNull(AllowlistProbeScope.fromRegionToken(token("""{"region":"Moscow"}""")))
        assertNull(AllowlistProbeScope.fromRegionToken("not-a-token"))
    }

    @Test fun storeKeepsTheNewestValidCatalogAndFallsBackWhenExpired() {
        val directory = Files.createTempDirectory("probes").toFile()
        val file = File(directory, "allowlist-probes.json")
        var wall = 1_000L
        var elapsed = 0L
        var served: ByteArray? = null
        val fetched = mutableListOf<String>()
        fun envelope(bytes: ByteArray) =
            """{"schema":1,"sequence":7,"payload":"${Base64.getEncoder().encodeToString(bytes)}","signature":"${sign(bytes)}"}""".toByteArray()
        fun store() = AllowlistProbeCatalogStore(
            file = file,
            fetcher = { url, _ -> fetched += url; served ?: throw IOException("blocked") },
            origins = { listOf("https://api.example.org", ServiceEndpoints.ALLOWLIST_API_ORIGIN) },
            wallMillis = { wall },
            elapsedMillis = { elapsed },
            verify = { payload, signature -> AllowlistProbeCatalog.verify(payload, signature, keys.public) },
        )
        val first = store()
        assertFalse(first.refreshIfDue())
        assertSame(AllowlistProbeCatalog.BUILT_IN, first.current())
        assertEquals(listOf("https://api.example.org$ALLOWLIST_PROBES_PATH", "${ServiceEndpoints.ALLOWLIST_API_ORIGIN}$ALLOWLIST_PROBES_PATH"), fetched)

        served = envelope(document())
        assertFalse("a failure waits 30 minutes", first.refreshIfDue())
        elapsed += 31 * 60_000L
        assertTrue(first.refreshIfDue())
        assertEquals(7, first.current().sequence)

        // A restart verifies the saved bytes again.
        val second = store()
        second.load()
        assertEquals(7, second.current().sequence)
        // Another document under the saved signature: refused on load.
        file.writeText(
            """{"payload":"${Base64.getEncoder().encodeToString(document(scopes = "{}"))}","signature":"${sign(document())}"}""",
        )
        val tampered = store()
        tampered.load()
        assertSame(AllowlistProbeCatalog.BUILT_IN, tampered.current())

        wall = 9_999_999_999_999L
        assertSame(AllowlistProbeCatalog.BUILT_IN, second.current())
        directory.deleteRecursively()
    }

    @Test fun scopeMemoryLastsADayPerOperator() {
        val directory = Files.createTempDirectory("scope").toFile()
        var wall = 0L
        val memory = AllowlistScopeMemory(File(directory, "scope.json")) { wall }
        memory.remember("25099", AllowlistProbeScope("RU-KOS", 3216))
        memory.remember("bad", AllowlistProbeScope("RU-MOW", null))
        assertEquals(AllowlistProbeScope("RU-KOS", 3216), AllowlistScopeMemory(File(directory, "scope.json")) { wall }.scopeFor("25099"))
        assertNull(memory.scopeFor("25001"))
        assertNull(memory.scopeFor(null))
        wall += 25 * 60 * 60_000L
        assertNull(memory.scopeFor("25099"))
        directory.deleteRecursively()
    }
}
