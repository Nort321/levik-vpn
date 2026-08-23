package com.leviknet.vpn.vpn

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyXhttpNormalizerTest {
    private val json = Json
    private val normalizer = LegacyXhttpNormalizer(json)

    @Test
    fun `copies legacy fields only inside vless xhttp extra`() {
        val extra = """
            {"sessionPlacement":"header","sessionKey":"sid","enableXmux":true,"unknown":7}
        """.trimIndent()
        val link = "vless://id@example.com:443?type=xhttp&security=reality&extra=${
            URLEncoder.encode(extra, StandardCharsets.UTF_8)
        }#LTE"

        val normalized = normalizer.normalize(link)
        val encodedExtra = normalized.substringAfter("extra=").substringBefore('#')
        val parsed = json.parseToJsonElement(
            URLDecoder.decode(encodedExtra, StandardCharsets.UTF_8),
        ).jsonObject

        assertEquals("header", parsed.getValue("sessionIDPlacement").jsonPrimitive.content)
        assertEquals("sid", parsed.getValue("sessionIDKey").jsonPrimitive.content)
        assertEquals("header", parsed.getValue("sessionPlacement").jsonPrimitive.content)
        assertEquals("7", parsed.getValue("unknown").jsonPrimitive.content)
        assertFalse("xmux" in parsed)
    }

    @Test
    fun `normalizes direct xray xhttp settings and nested extra`() {
        val source = """
            {
              "outbounds": [{
                "protocol": "vless",
                "tag": "LTE",
                "streamSettings": {
                  "network": "xhttp",
                  "xhttpSettings": {
                    "sessionPlacement": "path",
                    "sessionKey": "outer",
                    "extra": {
                      "sessionPlacement": "header",
                      "sessionKey": "inner"
                    }
                  }
                }
              }]
            }
        """.trimIndent()

        val normalized = json.parseToJsonElement(normalizer.normalize(source)).jsonObject
        val settings = normalized.getValue("outbounds").jsonArray[0].jsonObject
            .getValue("streamSettings").jsonObject
            .getValue("xhttpSettings").jsonObject
        val extra = settings.getValue("extra").jsonObject

        assertEquals("path", settings.getValue("sessionIDPlacement").jsonPrimitive.content)
        assertEquals("outer", settings.getValue("sessionIDKey").jsonPrimitive.content)
        assertEquals("header", extra.getValue("sessionIDPlacement").jsonPrimitive.content)
        assertEquals("inner", extra.getValue("sessionIDKey").jsonPrimitive.content)
    }

    @Test
    fun `normalizes url-safe base64 blob without padding`() {
        val extra = """{"sessionPlacement":"заголовок","sessionKey":"sid"}"""
        val link = "vless://id@example.com:443?type=xhttp&extra=${
            URLEncoder.encode(extra, StandardCharsets.UTF_8)
        }"
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(link.encodeToByteArray())

        val normalized = normalizer.normalize(encoded)
        val decoded = Base64.getUrlDecoder().decode(normalized).decodeToString()

        assertTrue(decoded.contains("sessionIDPlacement"))
        assertTrue(decoded.contains("sessionIDKey"))
        assertTrue(decoded.contains("%D0%B7%D0%B0%D0%B3%D0%BE%D0%BB%D0%BE%D0%B2%D0%BE%D0%BA"))
    }

    @Test
    fun `normalizes url-safe base64 xray json without padding`() {
        val source = """
            {
              "outbounds": [{
                "protocol": "vless",
                "streamSettings": {
                  "network": "xhttp",
                  "xhttpSettings": {"sessionPlacement":"query","sessionKey":"sid"}
                }
              }]
            }
        """.trimIndent()
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(source.encodeToByteArray())

        val normalized = normalizer.normalize(encoded)
        val decoded = Base64.getUrlDecoder().decode(normalized).decodeToString()
        val settings = json.parseToJsonElement(decoded).jsonObject
            .getValue("outbounds").jsonArray[0].jsonObject
            .getValue("streamSettings").jsonObject
            .getValue("xhttpSettings").jsonObject

        assertEquals("query", settings.getValue("sessionIDPlacement").jsonPrimitive.content)
        assertEquals("sid", settings.getValue("sessionIDKey").jsonPrimitive.content)
    }

    @Test
    fun `detects direct and base64 full xray configs only`() {
        val config = """{"outbounds":[{"protocol":"vless"}]}"""
        val encodedConfig = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(config.encodeToByteArray())

        assertTrue(normalizer.isFullXrayConfig(config))
        assertTrue(normalizer.isFullXrayConfig(encodedConfig))
        assertFalse(normalizer.isFullXrayConfig("vless://id@example.com:443#1.2.3.4"))
    }
}
