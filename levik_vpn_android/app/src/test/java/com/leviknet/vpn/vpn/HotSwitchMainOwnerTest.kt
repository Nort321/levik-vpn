package com.leviknet.vpn.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HotSwitchMainOwnerTest {
    private val ids = setOf("a", "b", "c")

    @Test fun `manual target is proven before its acknowledged installation is published`() = runTest {
        val events = mutableListOf<String>()
        val owner = HotSwitchMainOwner("a", ids, { events += "probe:$it"; true },
            { id, guard -> assertTrue(guard()); events += "install:$id" }, { events += "confirmed:$it" })
        assertEquals(HotSwitchMainOwner.Outcome.APPLIED, owner.select("b", { true }).outcome)
        assertEquals(listOf("probe:b", "install:b", "confirmed:b"), events)
        assertEquals("b", owner.currentId); assertTrue(owner.certain)
    }

    @Test fun `unsupported and failed preflight never write MAIN`() = runTest {
        var writes = 0
        val owner = HotSwitchMainOwner("a", ids, { false }, { _, _ -> writes++ })
        assertEquals(HotSwitchMainOwner.Outcome.UNSUPPORTED, owner.select("unknown", { true }).outcome)
        assertEquals(HotSwitchMainOwner.Outcome.FAILED, owner.select("b", { true }).outcome)
        assertEquals(0, writes); assertEquals("a", owner.currentId); assertTrue(owner.certain)
    }

    @Test fun `lost acknowledgement is compensated before returning a stable failure`() = runTest {
        var actual = "a"; val confirmed = mutableListOf<String>()
        val owner = HotSwitchMainOwner("a", ids, { true }, { id, _ ->
            actual = id; if (id == "b") error("Reply was lost")
        }, confirmed::add)
        assertEquals(HotSwitchMainOwner.Outcome.FAILED, owner.select("b", { true }).outcome)
        assertEquals("a", actual); assertEquals("a", owner.currentId); assertTrue(owner.certain)
        assertEquals(listOf("a"), confirmed)
    }

    @Test fun `failed compensation preserves uncertainty instead of claiming the old exit`() = runTest {
        var actual = "a"
        val owner = HotSwitchMainOwner("a", ids, { true }, { id, _ -> actual = id; error("No acknowledgement") })
        assertEquals(HotSwitchMainOwner.Outcome.UNCERTAIN, owner.select("b", { true }).outcome)
        assertEquals("a", actual); assertFalse(owner.certain)
    }

    @Test fun `invalidated in-flight write compensates before a newer selection can install`() = runTest {
        val gate = CompletableDeferred<Unit>(); val writes = mutableListOf<String>(); val confirmed = mutableListOf<String>()
        val owner = HotSwitchMainOwner("a", ids, { true }, { id, _ -> writes += id; if (id == "b") gate.await() }, confirmed::add)
        val old = async { owner.select("b", { true }) }; runCurrent()
        owner.invalidate()
        val latest = async { owner.select("c", { true }) }; runCurrent()
        assertEquals(listOf("b"), writes)
        gate.complete(Unit)
        assertEquals(HotSwitchMainOwner.Outcome.CANCELLED, old.await().outcome)
        assertEquals(HotSwitchMainOwner.Outcome.APPLIED, latest.await().outcome)
        assertEquals(listOf("b", "a", "c"), writes); assertEquals(listOf("a", "c"), confirmed)
    }

    @Test fun `closing an old owner never restores or publishes into a replacement core`() = runTest {
        val gate = CompletableDeferred<Unit>(); val writes = mutableListOf<String>(); val confirmed = mutableListOf<String>()
        val owner = HotSwitchMainOwner("a", ids, { true }, { id, _ -> writes += id; gate.await() }, confirmed::add)
        val selecting = async { owner.select("b", { true }) }; runCurrent()
        owner.close(); gate.complete(Unit)
        assertEquals(HotSwitchMainOwner.Outcome.CANCELLED, selecting.await().outcome)
        assertEquals(listOf("b"), writes); assertTrue(confirmed.isEmpty()); assertFalse(owner.certain)
    }

    @Test fun `automatic selection can reuse a fresh proof but still checks its guard`() = runTest {
        var probes = 0; var writes = 0
        val owner = HotSwitchMainOwner("a", ids, { probes++; true }, { _, guard -> assertTrue(guard()); writes++ })
        assertEquals(HotSwitchMainOwner.Outcome.APPLIED, owner.select("b", { true }, proven = true).outcome)
        assertEquals(HotSwitchMainOwner.Outcome.CANCELLED, owner.select("c", { false }, proven = true).outcome)
        assertEquals(0, probes); assertEquals(1, writes)
    }

    @Test fun `manual proof and recovery each have a finite time budget`() = runTest {
        var writes = 0
        val slowProof = HotSwitchMainOwner("a", ids, { delay(100_000); true }, { _, _ -> writes++ })
        assertEquals(HotSwitchMainOwner.Outcome.FAILED, slowProof.select("b", { true }).outcome)
        assertEquals(0, writes)
        val slowRecovery = HotSwitchMainOwner("a", ids, { true }, { id, _ -> if (id == "b") error("Lost reply") else delay(100_000) })
        assertEquals(HotSwitchMainOwner.Outcome.UNCERTAIN, slowRecovery.select("b", { true }).outcome)
        assertFalse(slowRecovery.certain)
    }

    @Test fun `a cancelled caller waits for its acknowledged compensation before releasing the writer`() = runTest {
        val gate = CompletableDeferred<Unit>(); var actual = "a"; val confirmed = mutableListOf<String>()
        val owner = HotSwitchMainOwner("a", ids, { true }, { id, guard ->
            actual = id
            if (id == "b") { gate.await(); assertFalse(guard()) }
        }, confirmed::add)
        val selecting = async { owner.select("b", { true }) }; runCurrent()
        selecting.cancel(); gate.complete(Unit); selecting.join()
        assertEquals("a", actual); assertEquals("a", owner.currentId); assertTrue(owner.certain)
        assertEquals(listOf("a"), confirmed)
    }

    @Test fun `an installation that never completes is bounded and compensated`() = runTest {
        var actual = "a"; val confirmed = mutableListOf<String>()
        val owner = HotSwitchMainOwner("a", ids, { true }, { id, _ ->
            actual = id
            if (id == "b") delay(100_000)
        }, confirmed::add)
        assertEquals(HotSwitchMainOwner.Outcome.FAILED, owner.select("b", { true }).outcome)
        assertEquals("a", actual); assertTrue(owner.certain); assertEquals(listOf("a"), confirmed)
    }

    @Test fun `an intent invalidated inside an awaited final guard cannot publish its target`() = runTest {
        var actual = "a"; var checks = 0; val confirmed = mutableListOf<String>()
        val owner = HotSwitchMainOwner("a", ids, { true }, { id, _ -> actual = id }, confirmed::add)
        val result = owner.select("b", canCommit = {
            checks++
            if (checks == 3) { delay(1); owner.invalidate() }
            true
        })
        assertEquals(HotSwitchMainOwner.Outcome.CANCELLED, result.outcome)
        assertEquals("a", actual); assertEquals(listOf("a"), confirmed)
    }
}
