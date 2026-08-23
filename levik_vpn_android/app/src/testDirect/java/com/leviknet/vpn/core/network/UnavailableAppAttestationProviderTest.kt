package com.leviknet.vpn.core.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class UnavailableAppAttestationProviderTest {
    @Test
    fun `direct provider never produces an integrity token`() = runTest {
        val result = UnavailableAppAttestationProvider.token("request-hash")

        assertTrue(result is AttestationResult.Unavailable)
    }
}
