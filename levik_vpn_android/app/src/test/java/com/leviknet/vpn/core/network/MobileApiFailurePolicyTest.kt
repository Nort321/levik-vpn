package com.leviknet.vpn.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileApiFailurePolicyTest {
    @Test
    fun `clears authentication only for terminal session failures`() {
        assertTrue(
            mobileApiExceptionForHttpFailure(
                401,
                ApiFailure("authentication_required", false),
            ) is ApiException.Unauthorized,
        )
        assertTrue(
            mobileApiExceptionForHttpFailure(
                401,
                ApiFailure("session_expired", false),
            ) is ApiException.Unauthorized,
        )
    }

    @Test
    fun `preserves authentication for nonterminal request failures`() {
        val exception = mobileApiExceptionForHttpFailure(
            401,
            ApiFailure("stale_request", false),
        )

        assertTrue(exception is ApiException.Rejected)
        exception as ApiException.Rejected
        assertEquals("stale_request", exception.code)
        assertEquals(401, exception.status)
        assertFalse(exception.retryable)
    }

    @Test
    fun `does not discard a session for an unstructured unauthorized response`() {
        val exception = mobileApiExceptionForHttpFailure(401, null)

        assertTrue(exception is ApiException.Rejected)
        exception as ApiException.Rejected
        assertEquals("http_401", exception.code)
        assertFalse(exception.retryable)
    }
}
