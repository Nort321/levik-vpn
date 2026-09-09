package com.leviknet.vpn.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundUpdateCheckTest {
    @Test
    fun `new verified release notifies once and later release notifies again`() = runTest {
        val manager = FakeUpdateManager(update(12))
        var lastNotified = 0
        val notifications = mutableListOf<Int>()
        suspend fun check() = checkAndNotifyUpdate(
            manager, 10, lastNotified,
            notify = { notifications += it.latestVersionCode; true },
            recordNotifiedVersion = { lastNotified = it },
        )

        check()
        check()
        manager.update = update(13)
        check()

        assertEquals(listOf(12, 13), notifications)
        assertEquals(13, lastNotified)
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test
    fun `blocked notification is retried after notifications become available`() = runTest {
        val manager = FakeUpdateManager(update(12))
        var lastNotified = 0
        checkAndNotifyUpdate(manager, 10, lastNotified, { false }, { lastNotified = it })
        assertEquals(0, lastNotified)
        checkAndNotifyUpdate(manager, 10, lastNotified, { true }, { lastNotified = it })
        assertEquals(12, lastNotified)
    }

    @Test
    fun `absent installed older or previously notified release never posts`() = runTest {
        for ((release, installed, notified) in listOf(
            Triple(null, 10, 0),
            Triple(update(10), 10, 0),
            Triple(update(9), 10, 0),
            Triple(update(11), 10, 12),
        )) {
            checkAndNotifyUpdate(
                FakeUpdateManager(release), installed, notified,
                notify = { error("Unexpected notification") },
                recordNotifiedVersion = { error("Unexpected persistence") },
            )
        }
    }

    @Test
    fun `notification failure does not consume the version`() = runTest {
        var recorded = false
        val failure = runCatching {
            checkAndNotifyUpdate(
                FakeUpdateManager(update(12)), 10, 0,
                notify = { throw IllegalStateException("Notification unavailable") },
                recordNotifiedVersion = { recorded = true },
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(false, recorded)
    }

    @Test
    fun `worker cancellation propagates without posting`() = runTest {
        val failure = runCatching {
            checkAndNotifyUpdate(
                FakeUpdateManager(null, cancelled = true), 10, 0,
                notify = { error("Unexpected notification") },
                recordNotifiedVersion = { error("Unexpected persistence") },
            )
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    private class FakeUpdateManager(
        var update: AppUpdateDto?,
        val cancelled: Boolean = false,
    ) : AppUpdateManager {
        override val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
        override suspend fun checkForUpdates(silent: Boolean): AppUpdateDto? =
            error("Background work must not run a foreground check")

        override suspend fun checkForUpdatesInBackground(): AppUpdateDto? {
            if (cancelled) throw CancellationException("Worker stopped")
            return update
        }

        override suspend fun downloadAndInstall(update: AppUpdateDto) = error("Unexpected download")
        override fun dismiss() = error("Unexpected dialog dismissal")
    }

    private fun update(version: Int) = AppUpdateDto(
        packageName = "com.leviknet.vpn",
        latestVersionCode = version,
        latestVersionName = "1.$version.0",
        downloadUrl = "https://leviknet.com/downloads/android/stable/v1.$version.0/update.apk",
        apkSize = 1L,
        sha256 = "a".repeat(64),
        signingCertificateSha256 = "b".repeat(64),
    )
}
