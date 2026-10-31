package com.leviknet.vpn.vpn

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import libXray.DialerController
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in: public latency targets through a direct probe core, then a bounded teardown. */
@RunWith(AndroidJUnit4::class)
class SmartRouteProbeStopInstrumentedTest {
    @Test
    fun probeTeardownDoesNotWaitForIdlePublicConnections() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("smartRoutePublicProbe") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.filesDir.canonicalFile, "sa-stop-test-0.sock").path
        File(path).delete()
        val config = buildJsonObject {
            put("log", buildJsonObject { put("loglevel", "none") })
            put("inbounds", buildJsonArray {
                add(buildJsonObject { put("tag", "levik-sa-0"); put("listen", "$path,0600"); put("protocol", "http"); put("settings", buildJsonObject {}) })
            })
            put("outbounds", buildJsonArray {
                add(buildJsonObject { put("tag", "direct"); put("protocol", "freedom"); put("settings", buildJsonObject {}) })
            })
        }
        val owner = System.nanoTime()
        runtime.claimOwner(owner)
        var lease: Long? = null
        try {
            lease = runtime.start(owner, config.toString(), object : DialerController {
                override fun protectFd(fd: Long): Boolean = true
            }, null, replaceActive = false)
            var measured = 0
            val prober = SmartRouteProber(listOf(SmartRouteProbeConfig.Candidate("probe", "direct", path)))
            val closeMs = try {
                runBlocking { prober.measureLatency { _, value -> measured = value.samplesMs.size } }
                Log.i(TAG, "latency samples=$measured")
                val started = System.nanoTime()
                val caller = Thread.currentThread()
                Thread {
                    Thread.sleep(5_000L)
                    if (!caller.isAlive) return@Thread
                    Thread.getAllStackTraces().forEach { (thread, frames) ->
                        if (frames.any { it.className.startsWith("okhttp3") || it.className.contains("conscrypt") || it.className.contains("LocalSocket") || thread === caller })
                            Log.i(TAG, "STACK ${thread.name} ${thread.state}\n" + frames.take(25).joinToString("\n") { "  at $it" })
                    }
                }.apply { isDaemon = true; start() }
                prober.close()
                (System.nanoTime() - started) / 1_000_000
            } catch (error: Throwable) { prober.close(); throw error }
            val stopLease = lease
            val started = System.nanoTime()
            val stopper = Thread { runtime.stop(owner, stopLease) }.apply { isDaemon = true; start() }
            stopper.join(120_000L)
            val stopMs = (System.nanoTime() - started) / 1_000_000
            Log.i(TAG, "close=${closeMs}ms stop=${stopMs}ms alive=${stopper.isAlive}")
            lease = null
            assertTrue("Probe measured nothing; the public targets are unreachable", measured > 0)
            assertTrue("Probe core stop took ${stopMs} ms", stopMs < 3_000L)
        } finally {
            lease?.let { runtime.stop(owner, it) }
            runtime.retireOwner(owner)
            File(path).delete()
        }
    }

    private companion object {
        const val TAG = "SmartRouteProbeStop"
        val runtime = XrayRuntime(Json { ignoreUnknownKeys = true })
    }
}
