package com.leviknet.vpn.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NetworkChangeCoordinatorTest {
    @Test fun `changing wifi target supersedes return while the cellular carrier stays retained`() = runTest {
        val owner = NetworkChangeCoordinator<String?>()
        val first = requireNotNull(owner.begin(1, networkHandoverTarget("cellular", "wifi-a", true)))
        val work = launch { CompletableDeferred<Unit>().await() }
        owner.track(first, work)
        val second = requireNotNull(owner.begin(1, networkHandoverTarget("cellular", "wifi-b", true)))
        assertTrue(work.isCancelled)
        assertTrue(owner.current(second))
        assertEquals("cellular", networkHandoverTarget("cellular", "wifi-b", false))
        owner.reset()
    }

    @Test fun `a settled no-op target can be requested again after a network bounce`() = runTest {
        val owner = NetworkChangeCoordinator<String>()
        val first = requireNotNull(owner.begin(1, "cellular"))
        val noOp = launch { /* The physical network already returned to Wi-Fi before acquiring the lock. */ }
        owner.track(first, noOp)
        noOp.join()
        val second = requireNotNull(owner.begin(1, "cellular"))
        assertNotEquals(first, second)
        assertFalse(owner.current(first))
        owner.reset()
    }

    @Test fun `capability and link updates for same target do not restart handover`() = runTest {
        val owner = NetworkChangeCoordinator<String>()
        val revision = requireNotNull(owner.begin(1, "cellular"))
        val work = launch { CompletableDeferred<Unit>().await() }
        owner.track(revision, work)
        repeat(10) { assertNull(owner.begin(1, "cellular")) }
        assertFalse(work.isCancelled)
        owner.reset()
    }

    @Test fun `returning wifi cancels cellular and late cellular job cannot replace it`() = runTest {
        val owner = NetworkChangeCoordinator<String>()
        val cell = requireNotNull(owner.begin(1, "cellular"))
        val cellular = launch { CompletableDeferred<Unit>().await() }
        owner.track(cell, cellular)
        val wifi = requireNotNull(owner.begin(1, "wifi"))
        assertTrue(cellular.isCancelled)
        val wireless = launch { CompletableDeferred<Unit>().await() }
        owner.track(wifi, wireless)
        val late = launch { CompletableDeferred<Unit>().await() }
        owner.track(cell, late)
        assertTrue(late.isCancelled)
        assertFalse(wireless.isCancelled)
        assertFalse(owner.current(cell))
        owner.reset()
    }

    @Test fun `off invalidates pending work and a new on may reuse same physical network`() = runTest {
        val owner = NetworkChangeCoordinator<String?>()
        val old = requireNotNull(owner.begin(1, null))
        val waiting = launch { CompletableDeferred<Unit>().await() }
        owner.track(old, waiting)
        owner.reset()
        assertTrue(waiting.isCancelled)
        assertFalse(owner.current(old))
        val next = requireNotNull(owner.begin(3, null))
        assertTrue(owner.current(next))
        owner.reset()
    }
}
