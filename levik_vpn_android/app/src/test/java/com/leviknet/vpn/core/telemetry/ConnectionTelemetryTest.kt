package com.leviknet.vpn.core.telemetry

import android.app.ApplicationExitInfo
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val CLIENT = TelemetryClientInfo(app = "2.8.0", os = "15", oem = "xiaomi")
private val SETTINGS = TelemetrySessionSettings(
    killSwitch = true,
    autoRecovery = true,
    splitTunnel = false,
    batteryUnrestricted = false,
)

class SessionRecorderTest {
    private var now = 1_000_000L

    private fun recorder() = SessionRecorder(CLIENT, SessionTrigger.TILE, SETTINGS, { now }, sid = "8a1c0000-0000-4000-8000-000000000001")

    @Test
    fun `snapshot follows the wire contract`() {
        val session = recorder()
        session.setNetwork(NetworkType.CELLULAR)
        session.attempt("Germany 1", TelemetryProtocol.VLESS_REALITY, AttemptCause.INITIAL)
        now += 1_840
        session.connected()
        val body = session.snapshot()

        assertEquals(1, body["v"]!!.jsonPrimitive.int)
        assertEquals(0, body["seq"]!!.jsonPrimitive.int)
        assertEquals(false, body["final"]!!.jsonPrimitive.content.toBoolean())
        assertFalse("end must be absent until the session ends", body.containsKey("end"))
        assertEquals("tile", body["trigger"]!!.jsonPrimitive.content)
        assertEquals("android", body["client"]!!.jsonObject["platform"]!!.jsonPrimitive.content)
        assertEquals("xiaomi", body["client"]!!.jsonObject["oem"]!!.jsonPrimitive.content)
        assertEquals("cellular", body["net"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(body["net"]!!.jsonObject.containsKey("token"))
        val timeline = body["timeline"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("attempt", "connected"), timeline.map { it["e"]!!.jsonPrimitive.content })
        assertEquals("vless-reality", timeline[0]["proto"]!!.jsonPrimitive.content)
        assertEquals(1_840L, timeline[1]["t"]!!.jsonPrimitive.long)
        assertEquals(1, session.snapshot()["seq"]!!.jsonPrimitive.int)
    }

    @Test
    fun `ended session is final and ignores later events`() {
        val session = recorder()
        session.attempt("Finland 1", TelemetryProtocol.HYSTERIA2, AttemptCause.INITIAL)
        now += 65_000
        session.end(EndBy.USER, "user")
        session.connected()
        val body = session.snapshot()

        assertTrue(body["final"]!!.jsonPrimitive.content.toBoolean())
        val end = body["end"]!!.jsonObject
        assertEquals("user", end["by"]!!.jsonPrimitive.content)
        assertEquals("user", end["code"]!!.jsonPrimitive.content)
        assertEquals(65L, end["durationS"]!!.jsonPrimitive.long)
        assertEquals(1, body["timeline"]!!.jsonArray.size)
    }

    @Test
    fun `probe failures are counted and codes are sanitized`() {
        val session = recorder()
        session.probeFailed(listOf("timeout", "timeout", "Bad Code!", "tls", "dns", "reset"))
        session.probeFailed(emptyList())
        session.probeSucceeded()
        session.probeSucceeded()
        val timeline = session.snapshot()["timeline"]!!.jsonArray.map { it.jsonObject }

        assertEquals(listOf("timeout", "other", "tls", "dns"), timeline[0]["codes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, timeline[1]["n"]!!.jsonPrimitive.int)
        assertEquals(listOf("other"), timeline[1]["codes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("probe_ok", timeline[2]["e"]!!.jsonPrimitive.content)
        assertEquals(2, timeline[2]["afterFailures"]!!.jsonPrimitive.int)
        assertEquals(3, timeline.size)
    }

    @Test
    fun `timeline keeps the start and the latest events`() {
        val session = recorder()
        repeat(MAX_TIMELINE_EVENTS + 50) { index ->
            now += 1
            session.attemptFailed(AttemptStage.HANDSHAKE, "code_$index")
        }
        val timeline = session.snapshot()["timeline"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }

        assertEquals(MAX_TIMELINE_EVENTS, timeline.size)
        assertEquals("code_0", timeline.first())
        assertEquals("code_119", timeline[119])
        assertEquals("code_249", timeline.last())
    }

    @Test
    fun `client fields match the server formats`() {
        val client = telemetryClientInfo("2.8.0-debug", "15.0.1", "Samsung Électronique")

        assertEquals("2.8.0-debug", client.app)
        assertEquals("15", client.os)
        assertEquals("samsunglectronique", client.oem)
        assertEquals("unknown", telemetryClientInfo("", "", "").oem)
    }
}

class TelemetryQueueTest {
    private lateinit var directory: File
    private var now = 1_760_000_000_000L

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("telemetry-queue").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun entry(sid: String, seq: Int, savedAt: Long = now) = QueuedSession(
        startedAt = now - 60_000,
        savedAt = savedAt,
        body = buildJsonObject {
            put("sid", sid)
            put("seq", seq)
            put("final", false)
            put("ageS", 0)
        },
    )

    @Test
    fun `keeps the latest checkpoint and acknowledges only what was sent`() {
        val queue = TelemetryQueue(directory) { now }
        queue.put(entry("a", 1))
        queue.put(entry("a", 0))
        queue.put(entry("b", 0))
        queue.acknowledge(mapOf("a" to 1, "b" to 0))
        queue.put(entry("a", 2))

        assertEquals(listOf("a" to 2), TelemetryQueue(directory) { now }.pending().map { it.sid to it.seq })
    }

    @Test
    fun `newer checkpoint survives acknowledgement of an older one`() {
        val queue = TelemetryQueue(directory) { now }
        queue.put(entry("a", 3))
        queue.acknowledge(mapOf("a" to 2))

        assertEquals(1, queue.pending().size)
    }

    @Test
    fun `discards reports older than seven days and rotates the install id daily`() {
        val queue = TelemetryQueue(directory) { now }
        queue.put(entry("old", 0, savedAt = now - MAX_QUEUE_AGE_MS - 1))
        queue.put(entry("new", 0))
        val first = queue.installId()

        assertEquals(listOf("new"), queue.pending().map { it.sid })
        assertEquals(first, queue.installId())
        now += 24 * 60 * 60 * 1_000L
        assertTrue(first != queue.installId())
    }

    @Test
    fun `clear removes the file and damaged files start over`() {
        val queue = TelemetryQueue(directory) { now }
        queue.put(entry("a", 0))
        queue.clear()
        assertFalse(File(directory, "queue.json").exists())
        assertTrue(queue.pending().isEmpty())

        File(directory, "queue.json").writeText("{broken")
        assertTrue(TelemetryQueue(directory) { now }.pending().isEmpty())
    }

    @Test
    fun `batches respect count and size limits and recompute age`() {
        val entries = (0 until 45).map { entry("s$it", 0) }
        val oversized = QueuedSession(now, now, buildJsonObject {
            put("sid", "huge")
            put("seq", 0)
            put("pad", "x".repeat(MAX_BATCH_BYTES))
        })
        val batches = buildTelemetryBatches("install", entries + oversized, now)

        assertEquals(listOf(20, 20, 5), batches.map { it.sessions.size })
        assertTrue(batches.all { it.body.toByteArray().size <= MAX_BATCH_BYTES })
        val first = Json.parseToJsonElement(batches[0].body).jsonObject
        assertEquals("install", first["install"]!!.jsonPrimitive.content)
        assertEquals(60L, first["sessions"]!!.jsonArray[0].jsonObject["ageS"]!!.jsonPrimitive.long)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionTelemetryFacadeTest {
    private lateinit var directory: File
    private var now = 1_760_000_000_000L
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private class FakeTransport(var outcome: SendOutcome = SendOutcome.SENT) : TelemetryTransport {
        val bodies = mutableListOf<JsonObject>()
        override suspend fun send(body: String): SendOutcome {
            bodies += Json.parseToJsonElement(body).jsonObject
            return outcome
        }
    }

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("telemetry-facade").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun telemetry(
        transport: TelemetryTransport,
        interrupted: (QueuedSession) -> InterruptedSessionEnd = { InterruptedSessionEnd(SessionEnd(EndBy.UNKNOWN, null), null) },
    ) = ConnectionTelemetry(directory, CLIENT, transport, scope, interrupted) { now }

    @Test
    fun `finished session is sent and removed from the queue`() = scope.runTest {
        val transport = FakeTransport()
        val telemetry = telemetry(transport)
        telemetry.setEnabled(true)
        telemetry.begin(SessionTrigger.USER, SETTINGS)
        telemetry.prepareNetwork("wifi-1", NetworkType.WIFI) { NetworkToken("signed", now + 60_000) }
        runCurrent()
        telemetry.record { it.attempt("Germany 1", TelemetryProtocol.TUIC, AttemptCause.INITIAL) }
        telemetry.record { it.attemptFailed(AttemptStage.HANDSHAKE, "udp_blocked") }
        telemetry.finish(EndBy.ERROR, "udp_blocked")
        runCurrent()

        val session = transport.bodies.single()["sessions"]!!.jsonArray.single().jsonObject
        assertEquals("error", session["end"]!!.jsonObject["by"]!!.jsonPrimitive.content)
        assertEquals("signed", session["net"]!!.jsonObject["token"]!!.jsonPrimitive.content)
        assertEquals("wifi", session["net"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(telemetry.active)
        assertTrue(TelemetryQueue(directory) { now }.pending().isEmpty())
        telemetry.setEnabled(false)
    }

    @Test
    fun `retry keeps reports for the next flush`() = scope.runTest {
        val transport = FakeTransport(SendOutcome.RETRY)
        val telemetry = telemetry(transport)
        telemetry.setEnabled(true)
        telemetry.begin(SessionTrigger.BOOT, SETTINGS)
        telemetry.finish(EndBy.USER, "user")
        runCurrent()
        assertEquals(1, TelemetryQueue(directory) { now }.pending().size)

        transport.outcome = SendOutcome.REJECTED
        telemetry.flush()
        assertTrue(TelemetryQueue(directory) { now }.pending().isEmpty())
        telemetry.setEnabled(false)
    }

    @Test
    fun `disabled telemetry records nothing and deletes the queue`() = scope.runTest {
        val transport = FakeTransport(SendOutcome.RETRY)
        val telemetry = telemetry(transport)
        telemetry.begin(SessionTrigger.USER, SETTINGS)
        assertFalse(telemetry.active)

        telemetry.setEnabled(true)
        telemetry.begin(SessionTrigger.USER, SETTINGS)
        telemetry.record { it.pause() }
        telemetry.persist()
        assertTrue(File(directory, "queue.json").exists())
        telemetry.setEnabled(false)
        telemetry.finish(EndBy.USER, "user")
        runCurrent()

        assertFalse(File(directory, "queue.json").exists())
        assertTrue(transport.bodies.isEmpty())
    }

    @Test
    fun `session left by a dead process ends with the recorded exit reason`() = scope.runTest {
        val startedAt = now - 3_600_000
        TelemetryQueue(directory) { now }.put(
            QueuedSession(startedAt, savedAt = now - 1_800_000, body = buildJsonObject {
                put("sid", "dead")
                put("seq", 4)
                put("final", false)
                put("timeline", kotlinx.serialization.json.JsonArray(emptyList()))
            }),
        )
        val transport = FakeTransport()
        val telemetry = telemetry(transport) {
            InterruptedSessionEnd(exitReasonEnd(ApplicationExitInfo.REASON_LOW_MEMORY), now - 600_000)
        }
        telemetry.setEnabled(true)
        runCurrent()

        val session = transport.bodies.single()["sessions"]!!.jsonArray.single().jsonObject
        assertEquals(5, session["seq"]!!.jsonPrimitive.int)
        assertTrue(session["final"]!!.jsonPrimitive.content.toBoolean())
        val end = session["end"]!!.jsonObject
        assertEquals("os_killed", end["by"]!!.jsonPrimitive.content)
        assertEquals("low_memory", end["code"]!!.jsonPrimitive.content)
        assertEquals(3_000L, end["durationS"]!!.jsonPrimitive.long)
        telemetry.setEnabled(false)
    }

    @Test
    fun `exit reasons map to contract codes`() {
        assertEquals(SessionEnd(EndBy.OS_KILLED, "freezer"), exitReasonEnd(ApplicationExitInfo.REASON_FREEZER))
        assertEquals(SessionEnd(EndBy.OS_KILLED, "user_force_stop"), exitReasonEnd(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals(SessionEnd(EndBy.SYSTEM, "app_update"), exitReasonEnd(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
        assertEquals(SessionEnd(EndBy.UNKNOWN, null), exitReasonEnd(ApplicationExitInfo.REASON_UNKNOWN))
        assertEquals(SessionEnd(EndBy.OS_KILLED, "unknown"), exitReasonEnd(ApplicationExitInfo.REASON_DEPENDENCY_DIED))
    }

    @Test
    fun `network token response is validated`() {
        val token = parseNetworkToken("""{"token":"abc","asn":8359,"country":"RU","expiresAt":"2026-10-11T00:00:00Z"}""")
        assertEquals("abc", token?.token)
        assertNull(parseNetworkToken("""{"token":null,"asn":null,"country":null,"expiresAt":null}"""))
        assertNull(parseNetworkToken("not json"))
    }
}
