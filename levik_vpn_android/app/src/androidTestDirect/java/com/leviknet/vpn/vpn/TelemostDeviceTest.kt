package com.leviknet.vpn.vpn

import android.net.ConnectivityManager
import android.content.Intent
import com.leviknet.vpn.MainActivity
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import android.view.WindowManager
import java.io.DataInputStream
import java.io.File
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in private lease; neither conference nor credentials are included in the APK. */
@RunWith(AndroidJUnit4::class)
class TelemostDeviceTest {
    @Serializable private data class Lease(val conferenceUrl: String, val sharedKey: String, val leaseRef: String, val expiresAt: Long)

    @Test(timeout = 240_000) fun protectedNativeTunnelReachesManagedExit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "telemost-canary.json")
        assumeTrue("A disposable private test lease is required", file.isFile)
        // OEM background freezing also suspends child processes. Real connections
        // run in the foreground VPN service; keep this isolated native test visible.
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach {
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        val progress = File(context.cacheDir, "telemost-test-phase")
        fun phase(value: String) { progress.writeText(value) }
        VpnService.prepare(context)?.let { consent ->
            phase("awaiting VPN permission")
            context.startActivity(consent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val until = android.os.SystemClock.elapsedRealtime() + 90_000
            while (VpnService.prepare(context) != null && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(500)
            check(VpnService.prepare(context) == null) { "Test app VPN consent is required" }
        }
        phase("loading")
        val bytes = file.readBytes()
        val lease = try { Json { ignoreUnknownKeys = true }.decodeFromString<Lease>(bytes.decodeToString()) } finally { bytes.fill(0) }
        val network = requireNotNull(NetworkMonitor(context, {}, {}).activeNetwork())
        val caps = context.getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network)
        phase("DNS preflight wifi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}")
        check(network.getAllByName("cloud-api.yandex.ru").isNotEmpty())
        val protected = AtomicInteger()
        val failures = AtomicInteger()
        val native = AndroidYandexNativeSessionFactory(File(context.applicationInfo.nativeLibraryDir,"liblevikyandex.so").path).create(
            YandexServerConfig(telemost = TelemostBootstrap(1,"a".repeat(64),lease.conferenceUrl,lease.leaseRef,lease.sharedKey,lease.expiresAt)),
            TunnelEngineEnvironment(network, TunnelFileDescriptorProtector { fd ->
                phase("protecting socket")
                val ok = VpnService().protect(fd.toInt())
                if (ok) ParcelFileDescriptor.fromFd(fd.toInt()).use { network.bindSocket(it.fileDescriptor) }
                if (ok) protected.incrementAndGet()
                phase("protected=${protected.get()} ok=$ok")
                ok
            }, "1.1.1.1", { false }, { failures.incrementAndGet() }),
        )
        try {
            phase("preparing")
            val proxy = native.prepare()
            phase("prepared")
            native.start()
            phase("started")
            val probes = InstrumentationRegistry.getArguments().getString("probeCount", "1").toInt().coerceIn(1, 4)
            repeat(probes) { probe ->
            if (probe > 0) Thread.sleep(35_000)
            Socket(proxy.address,proxy.port).use { socket ->
                socket.soTimeout=45_000
                val output=socket.getOutputStream()
                val input=DataInputStream(socket.getInputStream())
                output.write(byteArrayOf(5,1,2));output.flush()
                assertEquals(5,input.readUnsignedByte());assertEquals(2,input.readUnsignedByte())
                val user=proxy.username.toByteArray();val password=proxy.password.toByteArray()
                output.write(byteArrayOf(1,user.size.toByte()));output.write(user);output.write(password.size);output.write(password);output.flush()
                assertEquals(1,input.readUnsignedByte());assertEquals(0,input.readUnsignedByte())
                output.write(byteArrayOf(5,1,0,1,1,1,1,1,1,0xbb.toByte()));output.flush()
                assertEquals(5,input.readUnsignedByte());assertEquals(0,input.readUnsignedByte())
                input.readUnsignedByte()
                when(input.readUnsignedByte()) { 1 -> input.readFully(ByteArray(4)); 4 -> input.readFully(ByteArray(16)); else -> error("Unexpected SOCKS address") }
                input.readUnsignedShort()
                ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, "one.one.one.one", 443, true) as SSLSocket).use { tls ->
                    tls.soTimeout = 45_000
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                    tls.outputStream.write("GET /cdn-cgi/trace HTTP/1.1\r\nHost: one.one.one.one\r\nConnection: close\r\n\r\n".toByteArray())
                    tls.outputStream.flush()
                    val response = tls.inputStream.bufferedReader()
                    val status = response.readLine()?.split(' ')?.getOrNull(1)?.toIntOrNull()
                    assertEquals("HTTPS status through managed exit", 200, status)
                    var received = 0
                    val buffer = CharArray(4096)
                    while (true) { val count = response.read(buffer); if (count < 0) break; received += count; check(received < 65536) }
                    assertTrue("Response body must reach the phone", received > 0)
                }
            }
            }
            assertTrue("Every provider socket must use the protect/bind channel",protected.get() >= 6)
            assertEquals(0,failures.get())
        } finally {
            phase("stopping")
            native.stop()
            File(context.cacheDir, "telemost-test-counters").writeText(
                com.leviknet.vpn.core.logger.AppLogger.getLogs()
                    .filter { it.tag == "YandexTunnel" && it.message.startsWith("Carrier session=") }
                    .joinToString("\n") { it.message }
            )
            phase("stopped")
        }
    }
}
