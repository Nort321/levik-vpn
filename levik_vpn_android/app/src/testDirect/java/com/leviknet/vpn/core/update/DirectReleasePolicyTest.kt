package com.leviknet.vpn.core.update

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectReleasePolicyTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `parses the single latest stable release response`() {
        val selected = DirectReleaseParser.parseLatestStableRelease(
            json.encodeToString(release(tag = "v2.0.0")).encodeToByteArray(),
            json,
        )

        assertEquals("v2.0.0", selected?.tagName)
    }

    @Test
    fun `rejects a draft returned by the latest endpoint`() {
        val selected = DirectReleaseParser.parseLatestStableRelease(
            json.encodeToString(release(tag = "draft", draft = true)).encodeToByteArray(),
            json,
        )

        assertNull(selected)
    }

    @Test
    fun `rejects a prerelease returned by the latest endpoint`() {
        val selected = DirectReleaseParser.parseLatestStableRelease(
            json.encodeToString(release(tag = "preview", prerelease = true)).encodeToByteArray(),
            json,
        )

        assertNull(selected)
    }

    @Test
    fun `rejects an unsupported channel or malformed stable tag`() {
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            DirectReleaseParser.parseLatestStableRelease(
                json.encodeToString(release(tag = "v2.0.0").copy(channel = "beta"))
                    .encodeToByteArray(),
                json,
            )
        }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            DirectReleaseParser.parseLatestStableRelease(
                json.encodeToString(release(tag = "../v2.0.0")).encodeToByteArray(),
                json,
            )
        }
    }

    @Test
    fun `normal interval is between twelve and twenty four hours`() {
        val twelveHours = 12L * 60 * 60 * 1000
        val twentyFourHours = 24L * 60 * 60 * 1000

        assertTrue(UpdateCheckSchedule.SUCCESS_INTERVAL_MS in twelveHours..twentyFourHours)
    }

    @Test
    fun `transient and rate limit backoff stay bounded`() {
        assertEquals(30L * 60 * 1000, UpdateCheckSchedule.transientBackoffMs(1))
        assertEquals(12L * 60 * 60 * 1000, UpdateCheckSchedule.transientBackoffMs(99))
        assertEquals(
            UpdateCheckSchedule.MAX_BACKOFF_MS,
            UpdateCheckSchedule.rateLimitBackoffMs(
                nowMillis = 0L,
                retryAfterSeconds = 48L * 60 * 60,
                resetEpochSeconds = null,
            ),
        )
    }

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ): DirectReleaseFeed = DirectReleaseFeed(
        schemaVersion = 1,
        channel = "stable",
        tagName = tag,
        draft = draft,
        prerelease = prerelease,
        assets = listOf(
            DirectReleaseAsset(
                name = DirectReleaseClient.MANIFEST_ASSET_NAME,
                size = 512,
                url = "$RELEASE_PREFIX/$tag/update.json",
            ),
            DirectReleaseAsset(
                name = DirectReleaseClient.SIGNATURE_ASSET_NAME,
                size = 96,
                url = "$RELEASE_PREFIX/$tag/update.json.sig",
            ),
        ),
    )

    companion object {
        private const val RELEASE_PREFIX =
            "https://leviknet.com/downloads/android/stable"
    }
}
