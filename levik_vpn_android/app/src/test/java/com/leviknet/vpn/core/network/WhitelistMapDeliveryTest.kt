package com.leviknet.vpn.core.network

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitelistMapDeliveryTest {
    private data class Call(
        val channel: WhitelistMapChannel,
        val operation: WhitelistMapOperation,
        val network: String?,
        val body: String?,
    )

    private class Fixture {
        var enabled = true
        var current = true
        var elapsed = 10_000L
        var wall = 1_000_000L
        var detections = 0
        val calls = mutableListOf<Call>()
        var handler: suspend (Call) -> WhitelistMapResponse? = { call ->
            if (call.operation == WhitelistMapOperation.REGION) region() else WhitelistMapResponse(204)
        }
        val delivery = WhitelistMapDelivery<String>({ enabled }, { elapsed }, { wall }) { channel, operation, network, body ->
            val call = Call(channel, operation, network, body)
            calls += call
            handler(call)
        }
        fun region(token: String = "signed-token", ttl: Long = 60_000) = WhitelistMapResponse(
            200, "{\"token\":\"$token\",\"expiresAt\":${wall + ttl}}",
        )
        var outcomes: Map<String, String> = emptyMap()
        var scopes = mutableListOf<AllowlistProbeScope?>()
        suspend fun collect(network: String? = "cellular-A", detect: () -> WhitelistMode = { WhitelistMode.ACTIVE }) {
            delivery.collect(network, { current }) { scope ->
                detections++
                scopes += scope
                WhitelistObservation(detect(), outcomes)
            }
        }
    }

    @Test fun normalReportUsesSignedPhysicalRegionAndAccepts204WithoutRetry() = runTest {
        val f = Fixture()
        f.collect()
        assertEquals(2, f.calls.size)
        assertEquals("cellular-A", f.calls[0].network)
        assertNull(f.calls[1].network)
        assertEquals(WhitelistMapChannel.WEBSITE, f.calls[1].channel)
        assertEquals("{\"token\":\"signed-token\",\"signal\":\"active\",\"transport\":\"cellular\"}", f.calls[1].body)
    }

    @Test fun blockedWebsiteAcquiresAndReportsRegionOverPhysicalServiceChannel() = runTest {
        val f = Fixture()
        f.handler = { call ->
            if (call.channel == WhitelistMapChannel.WEBSITE) throw IOException("blocked")
            if (call.operation == WhitelistMapOperation.REGION) f.region() else WhitelistMapResponse(204)
        }
        f.collect()
        assertEquals(listOf(WhitelistMapChannel.WEBSITE, WhitelistMapChannel.SERVICE, WhitelistMapChannel.SERVICE), f.calls.map { it.channel })
        assertTrue(f.calls.all { it.network == "cellular-A" })
        f.calls.clear()
        f.collect()
        assertEquals(1, f.calls.size)
        assertEquals(WhitelistMapChannel.SERVICE, f.calls.single().channel)
        assertEquals(WhitelistMapOperation.REPORT, f.calls.single().operation)
        assertEquals("cellular-A", f.calls.single().network)
    }

    @Test fun existingWebsiteTokenCanUseServiceWhenReportRouteBecomesBlocked() = runTest {
        val f = Fixture()
        f.collect()
        f.calls.clear()
        f.handler = { call -> if (call.channel == WhitelistMapChannel.WEBSITE) null else WhitelistMapResponse(204) }
        f.collect()
        assertEquals(2, f.calls.size)
        assertNull(f.calls[0].network)
        assertEquals("cellular-A", f.calls[1].network)
        assertEquals(f.calls[0].body, f.calls[1].body)
        assertTrue(f.calls.all { it.operation == WhitelistMapOperation.REPORT })
    }

    @Test fun serviceTokenCanReportThroughWebsiteWhenServiceFails() = runTest {
        val f = Fixture()
        f.handler = { call ->
            if (call.channel == WhitelistMapChannel.WEBSITE && call.operation == WhitelistMapOperation.REGION) null
            else if (call.operation == WhitelistMapOperation.REGION) f.region()
            else if (call.channel == WhitelistMapChannel.SERVICE) null
            else WhitelistMapResponse(204)
        }
        f.collect()
        assertEquals(4, f.calls.size)
        assertTrue(f.calls.take(3).all { it.network == "cellular-A" })
        assertEquals(WhitelistMapChannel.WEBSITE, f.calls.last().channel)
        assertEquals(WhitelistMapOperation.REPORT, f.calls.last().operation)
        assertNull(f.calls.last().network)
        assertEquals(f.calls[2].body, f.calls[3].body)
    }

    @Test fun serviceTokenIsNotRetriedThroughWebsiteAfterExpiryOr422() = runTest {
        for (expired in listOf(false, true)) {
            val f = Fixture()
            f.handler = { call ->
                if (call.channel == WhitelistMapChannel.WEBSITE) null
                else if (call.operation == WhitelistMapOperation.REGION) f.region()
                else {
                    if (expired) f.elapsed += 60_000
                    WhitelistMapResponse(if (expired) 503 else 422)
                }
            }
            f.collect()
            assertEquals(3, f.calls.size)
            assertEquals(WhitelistMapChannel.SERVICE, f.calls.last().channel)
        }
    }

    @Test fun invalidWebsiteRegionFallsBackButMissingRegionNeverReports() = runTest {
        for (response in listOf(null, WhitelistMapResponse(204), WhitelistMapResponse(302), WhitelistMapResponse(200, "invalid"))) {
            val f = Fixture()
            f.handler = { response }
            f.collect()
            assertEquals(2, f.calls.size)
            assertEquals(0, f.detections)
            assertTrue(f.calls.all { it.operation == WhitelistMapOperation.REGION })
        }
    }

    @Test fun expiredOrOverlongTokenLifetimeIsRejected() = runTest {
        for (ttl in listOf(-1L, 0L, WHITELIST_MAP_MAX_TOKEN_AGE_MS + 1)) {
            val f = Fixture()
            f.handler = { f.region(ttl = ttl) }
            f.collect()
            assertEquals(0, f.detections)
        }
    }

    @Test fun networkChangeAndExpiryRequireFreshRegion() = runTest {
        val f = Fixture()
        f.collect()
        f.calls.clear()
        f.collect("cellular-B")
        assertEquals(WhitelistMapOperation.REGION, f.calls.first().operation)
        assertEquals("cellular-B", f.calls.first().network)
        f.calls.clear()
        f.elapsed += 60_000
        f.collect("cellular-B")
        assertEquals(WhitelistMapOperation.REGION, f.calls.first().operation)
    }

    @Test fun ineligibleNetworkClearsRegionAndDoesNoNetworkWork() = runTest {
        val f = Fixture()
        f.collect()
        f.calls.clear()
        f.collect(null)
        assertTrue(f.calls.isEmpty())
        f.collect()
        assertEquals(WhitelistMapOperation.REGION, f.calls.first().operation)
    }

    @Test fun optOutClearsRegionAndDoesNoNetworkWork() = runTest {
        val f = Fixture()
        f.collect()
        f.calls.clear()
        f.enabled = false
        f.collect()
        assertTrue(f.calls.isEmpty())
        f.enabled = true
        f.collect()
        assertEquals(WhitelistMapOperation.REGION, f.calls.first().operation)
    }

    @Test fun networkChangeDuringRegionLookupDoesNotDetectOrReport() = runTest {
        val f = Fixture()
        f.handler = { f.current = false; f.region() }
        f.collect()
        assertEquals(1, f.calls.size)
        assertEquals(0, f.detections)
    }

    @Test fun networkChangeOrOptOutDuringDetectionPreventsReport() = runTest {
        for (optOut in listOf(true, false)) {
            val f = Fixture()
            f.collect {
                if (optOut) f.enabled = false else f.current = false
                WhitelistMode.ACTIVE
            }
            assertEquals(1, f.calls.size)
        }
    }

    @Test fun optOutDuringFailedReportPreventsFallback() = runTest {
        val f = Fixture()
        f.handler = { call ->
            if (call.operation == WhitelistMapOperation.REGION) f.region()
            else { f.enabled = false; null }
        }
        f.collect()
        assertEquals(2, f.calls.size)
    }

    @Test fun unknownMeasurementNeverReports() = runTest {
        val f = Fixture()
        f.collect { WhitelistMode.UNKNOWN }
        assertEquals(1, f.calls.size)
    }

    @Test fun invalidatedTokenIsClearedWithoutRetryingItThroughService() = runTest {
        val f = Fixture()
        f.handler = { call -> if (call.operation == WhitelistMapOperation.REGION) f.region() else WhitelistMapResponse(422) }
        f.collect()
        assertEquals(2, f.calls.size)
        f.calls.clear()
        f.collect()
        assertEquals(WhitelistMapOperation.REGION, f.calls.first().operation)
    }

    @Test fun cancellationDoesNotBecomeAFallbackRequest() = runTest {
        val f = Fixture()
        f.handler = { throw CancellationException("cancelled") }
        var cancelled = false
        try { f.collect() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(1, f.calls.size)
    }

    @Test fun failuresDoNotQueueOldSignalsForTheNextRun() = runTest {
        val f = Fixture()
        f.handler = { call -> if (call.operation == WhitelistMapOperation.REGION) f.region() else null }
        f.collect { WhitelistMode.ACTIVE }
        f.calls.clear()
        f.collect { WhitelistMode.INACTIVE }
        assertEquals(2, f.detections)
        assertFalse(f.calls.any { it.body?.contains("\"signal\":\"active\"") == true })
        assertTrue(f.calls.all { it.body?.contains("\"signal\":\"inactive\"") == true })
    }

    @Test fun probeOutcomesTravelWithTheReportAndScopeComesFromTheToken() = runTest {
        val f = Fixture()
        val claims = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"region\":\"RU-KOS\",\"source\":\"x\",\"epoch\":1,\"asn\":3216}".toByteArray())
        val token = "$claims.signature"
        f.handler = { call -> if (call.operation == WhitelistMapOperation.REGION) f.region(token) else WhitelistMapResponse(204) }
        f.outcomes = mapOf("gosuslugi" to "ok", "Bad Id" to "ok", "github" to "short_body")
        f.collect()
        assertEquals(listOf(AllowlistProbeScope("RU-KOS", 3216)), f.scopes)
        assertEquals(
            "{\"token\":\"$token\",\"signal\":\"active\",\"transport\":\"cellular\",\"probes\":{\"gosuslugi\":\"ok\",\"github\":\"short_body\"}}",
            f.calls[1].body,
        )
    }

    @Test fun relayWithoutProbeSupportStillGetsThePlainReport() = runTest {
        val f = Fixture()
        f.outcomes = mapOf("gosuslugi" to "ok")
        f.handler = { call ->
            when {
                call.operation == WhitelistMapOperation.REGION -> f.region()
                call.body!!.contains("probes") -> WhitelistMapResponse(400)
                else -> WhitelistMapResponse(204)
            }
        }
        f.collect()
        assertEquals(3, f.calls.size)
        assertEquals("{\"token\":\"signed-token\",\"signal\":\"active\",\"transport\":\"cellular\"}", f.calls[2].body)
        assertEquals(f.calls[1].channel, f.calls[2].channel)
    }
}
