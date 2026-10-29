package com.leviknet.vpn.vpn

import android.app.Application
import android.content.Context
import android.system.Os
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leviknet.vpn.BuildConfig
import com.leviknet.vpn.data.RoutingPreset
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import libXray.DialerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real pinned Android JNI core and private UDS control, with local VLESS exits.
 * The synthetic SOCKS inbound replaces only the TUN; no VPN permission, account,
 * public destination, or production credential is used. Run with HotSwitchTestRunner.
 */
@RunWith(AndroidJUnit4::class)
class HotSwitchInstrumentedTest {
    @Test fun standaloneTcpAndUdpSurviveAtoBtoA() = retainedFlows(mux = false, serviceOverlay = false)
    @Test fun standaloneMuxTcpAndUdpSurviveAtoBtoA() = retainedFlows(mux = true, serviceOverlay = false)
    @Test fun serviceOverlayTcpAndUdpSurviveAtoBtoA() = retainedFlows(mux = false, serviceOverlay = true)
    @Test fun serviceOverlayMuxTcpAndUdpSurviveAtoBtoA() = retainedFlows(mux = true, serviceOverlay = true)
    @Test fun runtimeOwnerReconcilesServicesAndCompensatesLostAck() = retainedFlows(mux = false, serviceOverlay = true, throughOwner = true)
    @Test fun runtimeOwnerMuxReconcilesServicesAndCompensatesLostAck() = retainedFlows(mux = true, serviceOverlay = true, throughOwner = true)

    private fun retainedFlows(mux: Boolean, serviceOverlay: Boolean, throughOwner: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Use the isolated test runner; the normal Application starts account workers", Application::class.java, context.javaClass)
        assertEquals("v26.7.28", BuildConfig.LIBXRAY_VERSION)
        val directory = File(context.filesDir, "hs-${UUID.randomUUID().toString().take(6)}").apply { mkdirs() }
        MarkerEndpoint("A").use { a -> MarkerEndpoint("B").use { b ->
            val ports = reserveDistinctPorts(3)
            val servers = listOf(server("A", ports[0], mux), server("B", ports[1], mux))
            val rules = listOf(
                ServiceRouteRule("follows-main", listOf("full:service.test"), ServiceRouteTarget("main"), ServiceRouteTarget("block"), "domain", "Local service"),
            ) + if (throughOwner) listOf(
                ServiceRouteRule("fixed-country", listOf("full:fixed.test"), ServiceRouteTarget("country", "DE"), ServiceRouteTarget("block"), "domain", "Pinned country"),
                ServiceRouteRule("fallback-main", listOf("full:fallback.test"), ServiceRouteTarget("country", "FR"), ServiceRouteTarget("main"), "domain", "Fallback to main"),
            ) else emptyList()
            val policy = if (serviceOverlay) ServiceRouteProfile(1, 1, "a".repeat(64), rules,
                servers.map { source -> ServiceRouteExit(source.id, source.name, source.countryCode, "enabled", "ordinary",
                listOf(ServiceRouteEndpoint(requireNotNull(source.syncHost()), requireNotNull(source.syncPort()), "vless"))) }) else null
            val profile = PreparedTunnelProfile(1, "local-proof", "synthetic", "2026-10-29T00:00:00Z", servers = servers, serviceRoutes = policy)
            val service = policy?.let { requireNotNull(ServiceRouteConfig.prepare(profile, servers.first(), directory, allowMainFailover = true)) }
            val live = SmartAutoLiveConfig(servers, servers.first().id, directory, service)
            val generated = json.parseToJsonElement(XrayConfigBuilder(json).build(profile, servers.first().id, 42,
                routingPreset = RoutingPreset.GLOBAL, primaryDnsIp = "127.0.0.1", secondaryDnsIp = "127.0.0.1", serviceRouteSession = service)).jsonObject
            val config = localFixture(live.apply(generated), ports, a.port, b.port)
            assertFalse(config.containsKey("env"))
            assertTrue(config.getValue("inbounds").jsonArray.none { it.jsonObject["protocol"] == JsonPrimitive("tun") })
            val owner = System.nanoTime()
            runtime.claimOwner(owner)
            var lease: Long? = null
            try {
                lease = runtime.start(owner, config.toString(), object : DialerController {
                    override fun protectFd(fd: Long): Boolean = true
                }, null, replaceActive = false)
                (if (service == null) ServiceRouteControl(live) else ServiceRouteControl(service, live.candidates.map { it.tag }.toSet())).use { control ->
                    val inode = Os.stat(live.controlPath).st_ino
                    assertEquals(0x180, Os.stat(live.controlPath).st_mode and 0x1ff)
                    val policyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    val policyRuntime = if (throughOwner) ServiceRouteRuntime(requireNotNull(service), policyScope, live,
                        ServiceRouteRuntimeEffects(
                            write = { balancer, tag -> control.override(balancer, tag) },
                            probe = { candidate -> privateBody(candidate.probePath, candidate.exitId) },
                            // Every fixture destination is local and literal; DNS-wire failure
                            // policy is covered separately, without a public resolver here.
                            dns = { true },
                        ), report = {}) else null
                    var failPreflight = false
                    var loseNextAck = false
                    val ownerControl = if (throughOwner) HotSwitchMainOwner("A", servers.map { it.id }.toSet(),
                        preflight = { id -> privateBody(live.candidates.first { it.id == id }.path, id) && !failPreflight },
                        install = { id, guard ->
                            check(guard())
                            val candidate = live.candidates.first { it.id == id }
                            control.override(ServiceRouteConfig.MAIN, candidate.tag)
                            if (loseNextAck) { loseNextAck = false; error("Synthetic reply lost after native MAIN applied") }
                            // Remember a real ACK even if a later guard invalidates the request.
                            requireNotNull(policyRuntime).commitExternalMain(id, candidate.tag, guard)
                            check(guard())
                        }, confirmed = live::installed) else null
                    if (policyRuntime != null) runBlocking { policyRuntime.refresh() }
                    fun ownerSelect(id: String) = runBlocking { requireNotNull(ownerControl).select(id, canCommit = { true }) }
                    fun select(id: String) {
                        val candidate = live.candidates.first { it.id == id }
                        if (ownerControl != null) {
                            assertEquals(HotSwitchMainOwner.Result(HotSwitchMainOwner.Outcome.APPLIED, id), ownerSelect(id))
                            assertTrue(ownerControl.certain)
                        } else {
                            control.override(ServiceRouteConfig.MAIN, candidate.tag)
                            if (service != null) control.override(ServiceRouteConfig.routeTag(0), candidate.tag)
                        }
                        assertEquals("The core's private API socket must not be recreated", inode, Os.stat(live.controlPath).st_ino)
                    }
                    val target = if (serviceOverlay) "service.test" else "198.18.0.42"
                    try { tcpFlow(ports[2], target).use { oldTcp -> udpFlow(ports[2], target).use { oldUdp ->
                        assertEquals("A:before", oldTcp.message("before"))
                        assertEquals("A:before", oldUdp.message("before"))
                        val payload = Base64.encodeToString(Random(42).nextBytes(512 * 1024), Base64.NO_WRAP)
                        oldTcp.begin(payload)
                        select("B")
                        val delivered = oldTcp.finish()
                        assertTrue("The in-flight large transfer must keep its original exit", delivered.startsWith("A:"))
                        assertArrayEquals("The transfer must stay intact across the acknowledged override",
                            sha256(payload), sha256(delivered.removePrefix("A:")))
                        assertEquals("A:after", oldTcp.message("after"))
                        assertEquals("A:after", oldUdp.message("after"))
                        tcpFlow(ports[2], target).use { nextTcp -> udpFlow(ports[2], target).use { nextUdp ->
                            assertEquals("B:new", nextTcp.message("new"))
                            assertEquals("B:new", nextUdp.message("new"))
                            if (throughOwner) {
                                tcpFlow(ports[2], "fixed.test").use { assertEquals("A:pinned", it.message("pinned")) }
                                udpFlow(ports[2], "fallback.test").use { assertEquals("B:fallback", it.message("fallback")) }
                                failPreflight = true
                                assertEquals(HotSwitchMainOwner.Outcome.FAILED, ownerSelect("A").outcome)
                                failPreflight = false
                                loseNextAck = true
                                assertEquals(HotSwitchMainOwner.Outcome.FAILED, ownerSelect("A").outcome)
                                assertTrue(requireNotNull(ownerControl).certain)
                                tcpFlow(ports[2], target).use { assertEquals("B:compensated", it.message("compensated")) }
                                udpFlow(ports[2], "fallback.test").use { assertEquals("B:compensated", it.message("compensated")) }
                            }
                            select("A")
                            assertEquals("A:retained", oldTcp.message("retained"))
                            assertEquals("A:retained", oldUdp.message("retained"))
                            assertEquals("B:retained", nextTcp.message("retained"))
                            assertEquals("B:retained", nextUdp.message("retained"))
                            tcpFlow(ports[2], target).use { finalTcp -> udpFlow(ports[2], target).use { finalUdp ->
                                assertEquals("A:final", finalTcp.message("final"))
                                assertEquals("A:final", finalUdp.message("final"))
                            } }
                        } }
                    } } } finally { ownerControl?.close(); policyRuntime?.close(); policyScope.cancel() }
                }
            } finally {
                runtime.stop(owner, lease)
                runtime.retireOwner(owner)
                live.close()
                service?.close()
                assertTrue("Only the session-owned empty directory should remain", directory.delete())
            }
        } }
    }

    private fun server(id: String, port: Int, mux: Boolean) = TunnelServer(id, "fixture-$id", id,
        if (id == "A") "DE" else "NL", json.parseToJsonElement("""{
          "tag":"fixture-$id","protocol":"vless",
          "settings":{"vnext":[{"address":"198.51.100.${if (id == "A") 1 else 2}","port":$port,"users":[{"id":"$TEST_UUID","encryption":"none"}]}]},
          "streamSettings":{"network":"tcp","security":"none"},
          "mux":{"enabled":$mux,"concurrency":8}
        }""").jsonObject)

    private fun localFixture(base: JsonObject, ports: List<Int>, a: Int, b: Int): JsonObject {
        fun inbound(id: String, port: Int) = json.parseToJsonElement("""{
          "tag":"fixture-in-$id","listen":"127.0.0.1","port":$port,"protocol":"vless",
          "settings":{"clients":[{"id":"$TEST_UUID"}],"decryption":"none"}
        }""")
        fun exit(id: String, port: Int) = json.parseToJsonElement("""{
          "tag":"fixture-deliver-$id","protocol":"freedom",
          "settings":{"finalRules":[{"action":"allow","ip":["127.0.0.1/32"]}],"redirect":"127.0.0.1:$port"}
        }""")
        val routing = base.getValue("routing").jsonObject
        return JsonObject(base - "env" + mapOf(
            "log" to buildJsonObject { put("loglevel", "none") },
            "inbounds" to JsonArray(base.getValue("inbounds").jsonArray.filter { it.jsonObject["protocol"] != JsonPrimitive("tun") } + listOf(
                json.parseToJsonElement("""{"tag":"levik-tun-in","listen":"127.0.0.1","port":${ports[2]},"protocol":"socks","settings":{"auth":"noauth","udp":true}}"""),
                inbound("A", ports[0]), inbound("B", ports[1]),
            )),
            // Ordinary identities deliberately use documentation-only addresses:
            // production rejects loopback as a shared server identity. Remap only
            // this generated test config to its exact local VLESS listeners.
            "outbounds" to JsonArray(base.getValue("outbounds").jsonArray.map { value ->
                val outbound = value.jsonObject
                if (outbound["protocol"] != JsonPrimitive("vless")) value else {
                    val settings = outbound.getValue("settings").jsonObject
                    JsonObject(outbound + ("settings" to JsonObject(settings + ("vnext" to JsonArray(
                        settings.getValue("vnext").jsonArray.map { endpoint -> JsonObject(endpoint.jsonObject + ("address" to JsonPrimitive("127.0.0.1"))) },
                    )))))
                }
            } + listOf(exit("A", a), exit("B", b))),
            "routing" to JsonObject(routing + ("rules" to buildJsonArray {
                for (id in listOf("A", "B")) add(buildJsonObject {
                    put("type", "field"); put("inboundTag", buildJsonArray { add(JsonPrimitive("fixture-in-$id")) })
                    put("network", "tcp,udp"); put("outboundTag", "fixture-deliver-$id")
                })
                routing.getValue("rules").jsonArray.forEach(::add)
            })),
        ))
    }

    private class TcpFlow(private val socket: Socket) : AutoCloseable {
        private val input = DataInputStream(socket.getInputStream())
        private val output = DataOutputStream(socket.getOutputStream())
        private var pending: ByteArray? = null
        fun begin(value: String) {
            check(pending == null)
            val bytes = value.toByteArray(Charsets.UTF_8)
            val midpoint = bytes.size / 2
            output.writeInt(bytes.size); output.write(bytes, 0, midpoint); output.flush()
            // The remaining half is deliberately withheld until after the MAIN ACK.
            pending = bytes.copyOfRange(midpoint, bytes.size)
        }
        fun finish(): String {
            output.write(requireNotNull(pending)); output.flush(); pending = null
            return readFrame(input)
        }
        fun message(value: String): String { begin(value); return finish() }
        override fun close() = socket.close()
    }

    private class UdpFlow(private val socket: DatagramSocket, private val control: Socket, private val relay: InetSocketAddress, private val target: ByteArray) : AutoCloseable {
        fun message(value: String): String {
            val frame = byteArrayOf(0, 0, 0) + target + value.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(frame, frame.size, relay))
            val answer = DatagramPacket(ByteArray(2048), 2048)
            socket.receive(answer)
            val input = DataInputStream(ByteArrayInputStream(answer.data, 0, answer.length))
            assertEquals(0, input.readUnsignedShort()); assertEquals(0, input.readUnsignedByte())
            readAddress(input); input.readUnsignedShort()
            return input.readBytes().toString(Charsets.UTF_8)
        }
        override fun close() { socket.close(); control.close() }
    }

    private class MarkerEndpoint(private val label: String) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val executor = Executors.newCachedThreadPool { action -> Thread(action, "hot-switch-local-fixture").apply { isDaemon = true } }
        private val clients = ConcurrentHashMap.newKeySet<Socket>()
        private val tcp = ServerSocket(0, 8, LOOPBACK)
        val port: Int get() = tcp.localPort
        private val udp = DatagramSocket(tcp.localPort, LOOPBACK)
        init {
            executor.execute {
                while (!closed.get()) {
                    val client = try { tcp.accept() } catch (_: SocketException) { break }
                    clients.add(client)
                    executor.execute {
                        client.use { socket ->
                            val input = DataInputStream(socket.getInputStream())
                            val output = DataOutputStream(socket.getOutputStream())
                            try { while (!closed.get()) {
                                val value = readFrame(input)
                                val answer = "$label:$value".toByteArray(Charsets.UTF_8)
                                output.writeInt(answer.size); output.write(answer); output.flush()
                            } }
                            catch (_: EOFException) { /* The test closed a completed flow. */ }
                            catch (_: SocketException) { /* Closing the fixture interrupts a pending read. */ }
                            finally { clients.remove(socket) }
                        }
                    }
                }
            }
            executor.execute {
                while (!closed.get()) {
                    val packet = DatagramPacket(ByteArray(2048), 2048)
                    try {
                        udp.receive(packet)
                        val answer = "$label:".toByteArray(Charsets.UTF_8) + packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                        udp.send(DatagramPacket(answer, answer.size, packet.socketAddress))
                    } catch (_: SocketException) { break }
                }
            }
        }
        override fun close() {
            closed.set(true); tcp.close(); udp.close(); clients.forEach(Socket::close)
            executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val TEST_UUID = "cae83204-f7b1-43d7-a15c-fda5bf548d6b"
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        val json = Json { ignoreUnknownKeys = true }
        val runtime = XrayRuntime(json)

        fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        fun privateBody(path: String, expected: String): Boolean {
            PrivateUnixSocketFactory(path).createSocket().use { socket ->
                socket.soTimeout = 5_000
                socket.connect(InetSocketAddress(LOOPBACK, 9), 3_000)
                socket.getOutputStream().apply {
                    write("CONNECT 198.18.0.42:12345 HTTP/1.1\r\nHost: 198.18.0.42:12345\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
                }
                val header = ByteArrayOutputStream()
                while (header.size() < 4096 && !header.toString(Charsets.US_ASCII.name()).endsWith("\r\n\r\n")) {
                    val next = socket.getInputStream().read(); check(next >= 0); header.write(next)
                }
                check(header.toString(Charsets.US_ASCII.name()).substringBefore("\r\n").split(' ').getOrNull(1) == "200")
                TcpFlow(socket).use { flow ->
                    val payload = "private-proof-" + "p".repeat(64 * 1024)
                    val answer = flow.message(payload)
                    assertTrue("The private probe must use its assigned exit", answer.startsWith("$expected:"))
                    assertArrayEquals(sha256(payload), sha256(answer.removePrefix("$expected:")))
                }
                return true
            }
        }
        fun readFrame(input: DataInputStream): String {
            val length = input.readInt()
            require(length in 0..1024 * 1024) { "Invalid local fixture frame" }
            return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
        }

        fun reserveDistinctPorts(count: Int): List<Int> {
            val reservations = mutableListOf<ServerSocket>()
            return try { repeat(count) { reservations.add(ServerSocket(0, 1, LOOPBACK)) }; reservations.map { it.localPort } }
            finally { reservations.forEach(ServerSocket::close) }
        }
        fun socksControl(port: Int): Socket {
            val socket = Socket().apply { soTimeout = 5_000; connect(InetSocketAddress(LOOPBACK, port), 3_000) }
            try {
                socket.getOutputStream().apply { write(byteArrayOf(5, 1, 0)); flush() }
                val input = DataInputStream(socket.getInputStream())
                assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                return socket
            } catch (error: Throwable) { socket.close(); throw error }
        }
        fun address(host: String, port: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
            val output = DataOutputStream(bytes)
            val name = host.toByteArray(Charsets.US_ASCII)
            output.writeByte(3); output.writeByte(name.size); output.write(name); output.writeShort(port)
        }.toByteArray()
        fun reply(input: DataInputStream): InetSocketAddress {
            assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
            val host = readAddress(input)
            val port = input.readUnsignedShort()
            return InetSocketAddress(if (host.isAnyLocalAddress) LOOPBACK else host, port)
        }
        fun readAddress(input: DataInputStream): InetAddress = when (val kind = input.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully))
            4 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully))
            3 -> { input.readFully(ByteArray(input.readUnsignedByte())); LOOPBACK }
            else -> error("Unexpected SOCKS address kind: $kind")
        }
        fun tcpFlow(port: Int, target: String): TcpFlow {
            val socket = socksControl(port)
            return try {
                socket.getOutputStream().apply { write(byteArrayOf(5, 1, 0) + address(target, 12345)); flush() }
                reply(DataInputStream(socket.getInputStream()))
                TcpFlow(socket)
            } catch (error: Throwable) { socket.close(); throw error }
        }
        fun udpFlow(port: Int, target: String): UdpFlow {
            val control = socksControl(port)
            var socket: DatagramSocket? = null
            return try {
                control.getOutputStream().apply { write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)); flush() }
                val relay = reply(DataInputStream(control.getInputStream()))
                val datagram = DatagramSocket(0, LOOPBACK).apply { soTimeout = 5_000 }; socket = datagram
                UdpFlow(datagram, control, relay, address(target, 12345))
            } catch (error: Throwable) { socket?.close(); control.close(); throw error }
        }
    }
}
