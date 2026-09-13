package com.leviknet.vpn.core.auth

import com.leviknet.vpn.core.network.DevicePairingRequest
import com.leviknet.vpn.core.network.MobileApiClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DevicePairingTest {
    private val token = "aB_9-".repeat(8) + "xYz"
    private val uri = "https://leviknet.com/activate?pair=$token"

    @Test
    fun `both App Links and the in-app scanner preserve case sensitive pairing tokens`() {
        assertEquals(DeepLinkDestination.PAIRING, DeepLinkRouter.route(uri))
        assertEquals(token, DeepLinkRouter.pairingToken(uri))
        assertEquals(uri, ActivationCodeParser.parseQr("  $uri  "))
        assertNull(ActivationCodeParser.parse(uri))
        assertNull(DeepLinkRouter.activationCode(uri))
    }

    @Test
    fun `rejects noncanonical pairing links and never accepts raw credentials`() {
        listOf(
            "http://leviknet.com/activate?pair=$token",
            "https://evil.example/activate?pair=$token",
            "https://leviknet.com.evil.example/activate?pair=$token",
            "https://leviknet.com:443/activate?pair=$token",
            "https://user@leviknet.com/activate?pair=$token",
            "https://leviknet.com/activate/?pair=$token",
            "$uri&pair=$token", "$uri&code=ABCD-EFGH-JKMN-PQRS", "$uri#fragment",
            "https://leviknet.com/activate?pair=short",
            "https://leviknet.com/activate?pair=${"a".repeat(44)}",
            "https://leviknet.com/activate?pair=${"a".repeat(42)}%2F",
            "https://leviknet.com/activate?pair=%ZZ",
            "https://leviknet.com/activate?accessToken=$token", token,
        ).forEach { value ->
            assertNull(value, DeepLinkRouter.pairingToken(value))
            assertNull(value, ActivationCodeParser.parseQr(value))
        }
    }

    @Test
    fun `keeps the existing app-to-app activation scanner compatible`() {
        assertEquals("ABCD-EFGH-JKMN-PQRS", ActivationCodeParser.parseQr("https://leviknet.com/activate?code=ABCD-EFGH-JKMN-PQRS"))
    }

    @Test
    fun `pairs through the device-bound mobile route without caller supplied account or device ID`() {
        assertEquals("/api/mobile/v1/devices/pair", MobileApiClient.DEVICE_PAIRING_PATH)
        val encoded = Json.encodeToString(DevicePairingRequest(
            pairingToken = token, publicKeySpki = "public-key", deviceLabel = "Pixel 9",
            deviceModel = "Pixel 9", deviceOs = "Android 16", appVersion = "2.0.0",
            requestSigningAlgorithm = "PS256", profileEncryptionAlgorithm = "RSA-OAEP-256+A256GCM",
        ))
        val fields = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(token, fields.getValue("pairingToken").jsonPrimitive.content)
        assertEquals(8, fields.size)
        assertFalse(fields.containsKey("accountId"))
        assertFalse(fields.containsKey("deviceId"))
        assertFalse(fields.containsKey("accessToken"))
    }
}
