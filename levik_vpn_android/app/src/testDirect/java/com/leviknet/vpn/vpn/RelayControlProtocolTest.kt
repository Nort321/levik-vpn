package com.leviknet.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayControlProtocolTest {
    private val codec = RelayControlCodec()

    @Test
    fun `strict state machine accepts the documented prepare and start sequence`() {
        val machine = RelayControlStateMachine(codec)
        machine.markInitSent()

        assertEquals(
            RelayControlAction.Continue,
            machine.accept(event("""{"version":2,"type":"ready","phase":"control"}""")),
        )
        assertEquals(
            RelayControlAction.ConnectProtectChannel,
            machine.accept(
                event(
                    """{"version":2,"type":"ready","phase":"PROTECT_CHANNEL_LISTENING"}""",
                ),
            ),
        )
        assertEquals(
            RelayControlAction.Continue,
            machine.accept(
                event("""{"version":2,"type":"ready","phase":"PROTECT_CHANNEL_READY"}"""),
            ),
        )
        assertEquals(
            RelayControlAction.Continue,
            machine.accept(
                event(
                    """{"version":2,"type":"ready","phase":"TRANSPORT_LISTENING","data":{"localPort":"32001"}}""",
                ),
            ),
        )
        val prepared = machine.accept(
            event(
                """{"version":2,"type":"proxy_plan","phase":"PREPARED","data":{"address":"127.0.0.1","port":32123}}""",
            ),
        ) as RelayControlAction.PreparedProxy
        assertEquals("127.0.0.1", prepared.address)
        assertEquals(32123, prepared.port)

        assertEquals(
            RelayControlAction.Running,
            machine.accept(
                event(
                    """{"version":2,"type":"ready","phase":"RUNNING","data":{"protocolVersion":2}}""",
                ),
            ),
        )
        assertEquals(RelayControlState.RUNNING, machine.state)
        assertEquals(
            RelayControlAction.Continue,
            machine.accept(
                event(
                    """{"version":2,"type":"stats","data":{"at":"2026-08-31T00:00:00Z","activeConnections":1,"bytesUp":2,"bytesDown":3,"protectedExternalSockets":4,"rejectedUnprotectedSockets":0}}""",
                ),
            ),
        )
    }

    @Test
    fun `unknown JSON fields versions and out of order phases fail closed`() {
        assertThrows(RelayProtocolException::class.java) {
            event("""{"version":2,"type":"ready","phase":"control","extra":true}""")
        }
        assertThrows(RelayProtocolException::class.java) {
            event("""{"version":3,"type":"ready","phase":"control"}""")
        }

        val machine = RelayControlStateMachine(codec)
        machine.markInitSent()
        assertThrows(RelayProtocolException::class.java) {
            machine.accept(
                event("""{"version":2,"type":"ready","phase":"PROTECT_CHANNEL_READY"}"""),
            )
        }
    }

    @Test
    fun `protect channel requires exact request shape and produces stable ack`() {
        val request = codec.decodeProtectRequest(
            """{"version":2,"type":"PROTECT_SOCKET","requestId":42,"network":"tcp4","address":"203.0.113.1:443"}""",
        )
        assertEquals(42L, request.requestId)

        val success = codec.encodeProtectAck(request.requestId, success = true)
        assertTrue(success.contains("\"type\":\"PROTECT_SOCKET_ACK\""))
        assertTrue(success.contains("\"ok\":true"))
        val failure = codec.encodeProtectAck(request.requestId, success = false)
        assertTrue(failure.contains("\"code\":\"protect_bind_failed\""))

        assertThrows(RelayProtocolException::class.java) {
            codec.decodeProtectRequest(
                """{"version":2,"type":"PROTECT_SOCKET","requestId":42,"network":"tcp4","address":"203.0.113.1:443","extra":"rejected"}""",
            )
        }
    }

    @Test
    fun `native error exposes only its stable code`() {
        val machine = RelayControlStateMachine(codec)
        machine.markInitSent()
        val action = machine.accept(
            event(
                """{"version":2,"type":"error","code":"server_key_mismatch","message":"native transport operation failed"}""",
            ),
        )

        assertEquals(
            RelayControlAction.NativeFailure("server_key_mismatch"),
            action,
        )
        assertEquals(RelayControlState.FAILED, machine.state)
    }

    @Test
    fun `native diagnostic accepts only allowlisted stable codes`() {
        val machine = RelayControlStateMachine(codec)
        machine.markInitSent()
        val action = machine.accept(
            event("""{"version":2,"type":"diagnostic","code":"turn_tls_failed"}"""),
        )

        assertEquals(RelayControlAction.Diagnostic("turn_tls_failed"), action)
        assertThrows(RelayProtocolException::class.java) {
            event("""{"version":2,"type":"diagnostic","code":"turn_url_secret"}""")
        }
    }

    @Test
    fun `init uses bounded workers and keeps secret fields only in control JSON`() {
        val payload = codec.encodeInit(
            RelayNativeInit(
                peer = "relay.example.com:443",
                turnHashes = listOf("abcdefghijklmnop"),
                accessToken = "ABCDEFGHJKLMNPQR",
                deviceId = "a".repeat(64),
                workers = 18,
                turnFrontSni = "front.example.com",
                protectFdSocket = "@levik_wlr_protect_abcdefghijklmnop",
                serverPublicKey = "A".repeat(43),
                vkAuthMode = "account",
                proxyUsername = "u".repeat(24),
                proxyPassword = "p".repeat(48),
            ),
        )

        assertTrue(payload.contains("\"workers\":18"))
        assertTrue(payload.contains("\"turnStreamFirst\":true"))
        assertTrue(payload.contains("\"turnSni\":\"front.example.com\""))
        assertTrue(payload.contains("\"password\":\"ABCDEFGHJKLMNPQR\""))
        assertTrue(payload.contains("\"vkAuthMode\":\"account\""))
    }

    @Test
    fun `VK account request is accepted only while preparing and credentials stay typed`() {
        val machine = RelayControlStateMachine(codec)
        machine.markInitSent()
        machine.accept(event("""{"version":2,"type":"ready","phase":"control"}"""))
        machine.accept(event("""{"version":2,"type":"ready","phase":"PROTECT_CHANNEL_LISTENING"}"""))
        machine.accept(event("""{"version":2,"type":"ready","phase":"PROTECT_CHANNEL_READY"}"""))

        val action = machine.accept(
            event(
                """{"version":2,"type":"vk_auth_required","data":{"requestId":"1-2","hash":"abcdefghijklmnop"}}""",
            ),
        ) as RelayControlAction.RequestVkAuth
        assertEquals("1-2", action.request.requestId)
        assertEquals("abcdefghijklmnop", action.request.hash)

        val response = codec.encodeTurnCredentials(
            action.request,
            RelayTurnCredentials(
                username = "temporary-user",
                password = "temporary-password",
                urls = listOf("turn:203.0.113.2:3478?transport=udp"),
            ),
        )
        assertTrue(response.contains("\"type\":\"TURN_CREDS\""))
        assertTrue(response.contains("\"requestId\":\"1-2\""))
        assertTrue(response.contains("\"hash\":\"abcdefghijklmnop\""))
    }

    @Test
    fun `native process command and environment contain no profile secrets`() {
        val builder = relayProcessBuilder(
            executablePath = "/data/app/lib/liblevikrelay.so",
            controlSocketName = "@levik_wlr_control_ZYXWVUTSRQPONMLK",
        )

        assertEquals(
            listOf(
                "/data/app/lib/liblevikrelay.so",
                "-levik-control-sock=@levik_wlr_control_ZYXWVUTSRQPONMLK",
            ),
            builder.command(),
        )
        assertTrue(builder.environment().isEmpty())
        val diagnosticsSurface = builder.command().joinToString(" ")
        assertTrue("ABCDEFGHJKLMNPQR" !in diagnosticsSurface)
        assertTrue("abcdefghijklmnop" !in diagnosticsSurface)
    }

    private fun event(payload: String): RelayControlEvent = codec.decodeEvent(payload)
}
