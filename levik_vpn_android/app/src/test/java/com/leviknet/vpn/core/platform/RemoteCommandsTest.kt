package com.leviknet.vpn.core.platform

import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandsTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `there is no command to disconnect`() {
        assertNull(RemoteCommand.of("disconnect"))
        assertEquals(
            listOf("connect", "auto_server", "refresh_profile", "check_update", "collect_diagnostics"),
            RemoteCommand.entries.map(RemoteCommand::wire),
        )
    }

    @Test
    fun `keeps delivered commands with unknown names so they are answered unsupported`() {
        val delivered = RemoteCommands.parseDelivered(
            json("""[
                {"id":"00000000-0000-4000-8000-000000000001","command":"connect","replyKey":null},
                {"id":"00000000-0000-4000-8000-000000000002","command":"disconnect"},
                {"id":"not-a-uuid","command":"connect"},
                "junk"
            ]"""),
        )
        assertEquals(
            listOf(DeliveredCommand("00000000-0000-4000-8000-000000000001", "connect", null), DeliveredCommand("00000000-0000-4000-8000-000000000002", "disconnect", null)),
            delivered,
        )
    }

    @Test
    fun `knows a command from the family owner and lets it only repair`() {
        val delivered = RemoteCommands.parseDelivered(
            json("""[{"id":"00000000-0000-4000-8000-000000000003","command":"refresh_profile","via":"family"}]"""),
        ).single()
        assertTrue(delivered.fromFamily)
        assertEquals(
            setOf(RemoteCommand.AUTO_SERVER, RemoteCommand.REFRESH_PROFILE, RemoteCommand.CHECK_UPDATE),
            RemoteCommands.FAMILY_COMMANDS,
        )
        assertTrue(RemoteCommand.CONNECT !in RemoteCommands.FAMILY_COMMANDS)
    }

    @Test
    fun `parses devices and issued commands strictly`() {
        val devices = RemoteCommands.parseDevices(
            json("""[
                {"deviceId":"mac","name":"MacBook","platform":"macos","appVersion":"2.7.0","current":false,"remote":true,
                 "presence":{"online":true,"vpnConnected":true,"country":"DE"}},
                {"deviceId":"tv","name":"TV","platform":"tizen"},
                {"deviceId":"pc","name":"PC","platform":"windows","presence":{"online":true,"country":"<b>"}}
            ]"""),
        )
        assertEquals(
            listOf(
                RemoteDevice("mac", "MacBook", RemotePlatform.MACOS, "2.7.0", false, true, true, true, "DE"),
                RemoteDevice("pc", "PC", RemotePlatform.WINDOWS, null, false, false, true, false, null),
            ),
            devices,
        )
        val issued = RemoteCommands.parseIssued(
            json("""[
                {"id":"00000000-0000-4000-8000-000000000001","deviceId":"mac","command":"check_update","state":"done","detail":"available:2.8.0"},
                {"id":"00000000-0000-4000-8000-000000000002","deviceId":"mac","command":"disconnect","state":"done","detail":null},
                {"id":"00000000-0000-4000-8000-000000000003","deviceId":"mac","command":"connect","state":"lost"}
            ]"""),
        )
        assertEquals(listOf(IssuedCommand("00000000-0000-4000-8000-000000000001", "mac", RemoteCommand.CHECK_UPDATE, RemoteCommandState.DONE, "available:2.8.0")), issued)
        assertEquals(issued.first(), RemoteSnapshot(commands = issued).latestFor("mac"))
    }

    @Test
    fun `keeps the version detail within what the server accepts`() {
        assertEquals("available:2.8.0-beta.1", RemoteCommands.availableDetail("2.8.0-beta.1"))
        assertTrue(Regex("^[A-Za-z0-9._:-]{1,80}$").matches(RemoteCommands.availableDetail("версия 3 / test" + "x".repeat(100))))
    }

    @Test
    fun `seals the diagnostics link so only the sender opens it`() {
        val (replyKey, privateKey) = RemoteCommands.createReplyKey()
        val sealed = RemoteCommands.seal(replyKey, NOTE)
        assertTrue("note.leviknet" !in sealed)
        assertEquals(NOTE, RemoteCommands.open(privateKey, sealed))
        assertNull(RemoteCommands.open(RemoteCommands.createReplyKey().second, sealed))
        assertNull(RemoteCommands.open(privateKey, RemoteCommands.seal(replyKey, "https://evil.example/x#y")))
    }

    @Test
    fun `opens a link sealed by the website and the desktop apps`() {
        // Sealed with Node's publicEncrypt(RSA_PKCS1_OAEP_PADDING, oaepHash "sha256"), as WebCrypto RSA-OAEP SHA-256 does.
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(NODE_PKCS8)))
        assertEquals(NOTE, RemoteCommands.open(privateKey, NODE_SEALED))
        assertEquals(NOTE, RemoteCommands.open(privateKey, RemoteCommands.seal(NODE_SPKI, NOTE)))
    }

    private companion object {
        const val NOTE = "https://note.leviknet.org/abcDEF123#key_-9"

        /** A throwaway test key; it protects nothing. */
        const val NODE_PKCS8 =
            "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDnKdVFaaYXpwrQj9S46mSxJZenLoYVU7I0gLPjvDwU/QQoBP53" +
            "8KhlRHKw2zEwTRG8DEZlCUa0RBMrSxKOkHpPaRNYl2nchwdaRi/iKttjPGs7/b7xw52SwYCApQG9PzMb3y1OoZ2moaOAiI7oTGPV" +
            "zQgDZhIpGKcJWQDGbMm0EvAAFFTPDTgsR4E6FDFZwG1ZMzh9RA+RWSixjCt8KkFQgDJ1nQnSDET01z0oWgf14b6sC/rTxE9YoR6q" +
            "X1QJvkzuD7we4d3ZbXsbsPsiZvj/bWLubt6uvERUWKy/+uEr4Tovootg5bchEnfIDpncau48YdOZ+gtg3DxJfFuzi8bDAgMBAAEC" +
            "ggEAGPf2SYNCBaUChd47TRFC+LVwSNu9b46fnYyB9xPNGOaZbSnY7fidynkXEE5PZQG2FtiWk/GUDwLutSBB7AnkBDF70ve2igjr" +
            "EjpUOrrZVpc0mAFuVKvbQyTz2Ia6MN2ji3D9nP7Xn04vRxdjpD4ivHm4fLF+Pa0kLF4jjMs8YDRE20+y4U2ZqN2eEfdZT8/V3HOn" +
            "pYV6x+Ps3VtJR15ziN2v9yQcvBQScnEdg9hklmjCobp5Tv9jVRBU/ghY1Vvh2p5J5deKy/Hm0Cl0L8PgzbIUnu1XS/UvakLYhsLX" +
            "0nRDrEOeH/amirNhqynWSgPduRCJ/7AkQ2kCLBOkADZ5kQKBgQD9DGwoK40oweAPpPWIap1Bcn70BjTFFU8ooYwXeFvLSga40NIO" +
            "UzqwmnGRG5ppCXe3NQytSjQCq/xSx43REhoQjDiEYVzKichO2eddJu8lSKozpDl1WkXqafvcEELwmKhnohTWf8B7/MX6ldwJ2QrZ" +
            "+QXzyxamroFZQG8L1e9ksQKBgQDp3BBYJOJcm/gVo12G+eizKa36M57/EaL9T1MFlGsp7jNJYgHIF9w8JfNrROijr60LvqJ32bbj" +
            "BDu/5X6MwqjHYQvDf3t+MdnQAYa5lfDfOTd9P1lFrF86llNiTz5JXACQha7wfLOb7bAKqBSUAjZipTi+mGC1vRqh2jhc0uIPswKB" +
            "gGiw/QZZjlswauzOCK9ZvJnZLuDyuF5ZhJiAzZ7gMsoWZFWOP3mhwYRTQW0RiH6wZhEsJ4SCUKZSVWBCI1A+Nv5lpPRP+73n5zGI" +
            "QYpukz5Po+sdpJACuy5Sjtrq92bnIbVOf2SHEjG50Ibt/U0tuKmZt3a3rTVkuSsd11OEPTjhAoGANrwuCyNe48QRlabt3giiAUUR" +
            "Fk18ukB7gIRPby/kwSSiVludBSMohd9wELxJIv6cJvKQfjKzzoCQJ4LkUrfA3yGEqlYtJkUusLOav9crUGPI7ASmtVr50vGaRJk9" +
            "f9gLvce8QUc+lpFUMbpTTq0R45a+KEXr5L2E/TrhFEC7bmcCgYEAkAmjpZb6JjnHuY+gxBwkrldci4YNYfbVLFX1riIaIyY1wHBy" +
            "OSL5tnmYUSSZKt6RQGPOW5EuzZTl+y9dAk4/GGPSmy5P7XWQTbwrQuPXH6Gd043SFU+iHhNyo31IObD0MQhjb/yUj0BmhVDuJ4qt" +
            "FV05J7KrusbSwirIfmbVS8k="
        const val NODE_SPKI =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA5ynVRWmmF6cK0I/UuOpksSWXpy6GFVOyNICz47w8FP0EKAT+d/CoZURy" +
            "sNsxME0RvAxGZQlGtEQTK0sSjpB6T2kTWJdp3IcHWkYv4irbYzxrO/2+8cOdksGAgKUBvT8zG98tTqGdpqGjgIiO6Exj1c0IA2YS" +
            "KRinCVkAxmzJtBLwABRUzw04LEeBOhQxWcBtWTM4fUQPkVkosYwrfCpBUIAydZ0J0gxE9Nc9KFoH9eG+rAv608RPWKEeql9UCb5M" +
            "7g+8HuHd2W17G7D7Imb4/21i7m7errxEVFisv/rhK+E6L6KLYOW3IRJ3yA6Z3GruPGHTmfoLYNw8SXxbs4vGwwIDAQAB"
        const val NODE_SEALED =
            "EQZ2t+bnKIx+2ksrHfL/C556Hd3+KR1OIlw3RZz7nSdDpZhgDVXxj48idpCncrVwrouLxLCm3kb5VHAFvHruO7CgblADj3ylSk2r" +
            "m/nsV/8cXh3WyyNrDmmpsJ38zHh7KeyTgAU0GLnembFAmUdT7FuubRH9WONseF31SJLD4SeErCA5VP7SKN/5HzrYA+H01e7ddGQ4" +
            "NhPwa5eXcyY+fifDRbYWrHFZmgQKHNN4gu5lJgG6ttlWBvvV/L4ei2/pJxEu7i+3o58xMeyhDtVWDhKIRUz55/bbD4q0CXl8LRCY" +
            "sFNM6TBNl6oqQh4AYzxKsxzEZYJ/mDTuSb6InZiLXg=="
    }
}
