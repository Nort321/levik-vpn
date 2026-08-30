package com.leviknet.vpn.core.logger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AppLoggerTest {
    @Test
    fun `sanitizes token values embedded in uri text`() {
        val sanitized = AppLogger.sanitize(
            "Rejected https://leviknet.com/activate?token=top-secret-token&source=app",
        )

        assertFalse(sanitized.contains("top-secret-token"))
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun `formatted throwable text cannot retain credential values`() {
        val rawSecret = "top-secret-token"
        val throwableText = AppLogger.sanitize(
            IllegalStateException("token=$rawSecret").stackTraceToString(),
        )
        val formatted = LogEntry(
            timestamp = Instant.EPOCH,
            level = LogLevel.ERROR,
            tag = "test",
            message = "request failed",
            throwableText = throwableText,
        ).toFormattedString()

        assertFalse(formatted.contains(rawSecret))
        assertTrue(formatted.contains("[REDACTED]"))
    }

    @Test
    fun `sanitizes bare account and server identifiers`() {
        val subscriptionId = "123e4567-e89b-12d3-a456-426614174000"
        val serverId = "a".repeat(64)
        val sanitized = AppLogger.sanitize(
            "Subscription $subscriptionId failed while switching to server $serverId",
        )

        assertFalse(sanitized.contains(subscriptionId))
        assertFalse(sanitized.contains(serverId))
        assertTrue(sanitized.contains("[REDACTED]"))
    }
}
