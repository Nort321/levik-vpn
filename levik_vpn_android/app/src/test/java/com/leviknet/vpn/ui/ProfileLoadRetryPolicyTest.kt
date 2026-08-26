package com.leviknet.vpn.ui

import com.leviknet.vpn.core.network.ApiException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileLoadRetryPolicyTest {
    @Test
    fun `retries temporary network and api failures with bounded backoff`() {
        assertEquals(
            400L,
            profileLoadRetryDelayMillis(ApiException.Network(IOException()), 0),
        )
        assertEquals(
            1_200L,
            profileLoadRetryDelayMillis(
                ApiException.Rejected("profile_upstream_unavailable", true, 503),
                1,
            ),
        )
        assertEquals(
            2_400L,
            profileLoadRetryDelayMillis(ApiException.Network(IOException()), 2),
        )
        assertNull(
            profileLoadRetryDelayMillis(ApiException.Network(IOException()), 3),
        )
    }

    @Test
    fun `does not retry permanent api and parsing failures`() {
        assertNull(
            profileLoadRetryDelayMillis(
                ApiException.Rejected("subscription_not_found", false, 404),
                0,
            ),
        )
        assertNull(
            profileLoadRetryDelayMillis(ApiException.InvalidResponse("invalid"), 0),
        )
    }
}
