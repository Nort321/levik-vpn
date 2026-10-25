package com.leviknet.vpn.vpn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import libXray.DialerController
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real bundled libXray + private Android sockets. Public TLS probe is explicitly opt-in. */
@RunWith(AndroidJUnit4::class)
class ServiceRouteControlInstrumentedTest {
    @Test
    fun privateUnixRoutingApiAcceptsConfirmedBalancerOverrides() {
        withCore { config, server ->
            val retired = ServiceRouteControl(config)
            retired.use { control ->
                control.override(ServiceRouteConfig.routeTag(0), server.tag)
                control.override(ServiceRouteConfig.routeTag(0), ServiceRouteConfig.BLOCK)
                control.override(ServiceRouteConfig.MAIN, server.tag)
            }
            ServiceRouteControl(config).use { replacement ->
                replacement.override(ServiceRouteConfig.routeTag(0), server.tag)
                assertTrue("Retired control must never address a replacement core", runCatching {
                    retired.override(ServiceRouteConfig.routeTag(0), ServiceRouteConfig.BLOCK)
                }.exceptionOrNull() is IllegalStateException)
            }
            assertTrue(java.io.File(config.controlPath).exists())
        }
    }

    @Test
    fun optInPublicTlsProbeUsesWrappedPrivateUnixSocket() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("serviceRoutePublicTlsProbe") == "true")
        withCore(direct = true) { config, _ ->
            // Neutral certificate-validated public endpoints only; no customer credentials or nodes.
            assertTrue("TLS probe over the private Unix socket failed", probeServiceExit(config.candidates.single()))
        }
    }

    private fun withCore(direct: Boolean = false, block: (ServiceRouteConfig, TunnelServer) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outbound = buildJsonObject {
            put("tag", "test-exit")
            put("protocol", "socks")
            put("settings", buildJsonObject {
                put("servers", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject { put("address", "192.0.2.1"); put("port", 9) })
                })
            })
        }
        val server = TunnelServer("test-server", "test-exit", "Test", "DE", outbound)
        val policy = ServiceRouteProfile(1, 1, "a".repeat(64), listOf(
            ServiceRouteRule("test-rule", listOf("domain:example.test"), ServiceRouteTarget("country", "DE"), ServiceRouteTarget("block"), "domain", "example.test"),
        ), listOf(ServiceRouteExit("test-exit-id", "Test", "DE", "enabled", "ordinary", listOf(ServiceRouteEndpoint("192.0.2.1", 9, "socks")))))
        val profile = PreparedTunnelProfile(1, "test", "test-subscription", "2026-10-25T00:00:00Z", servers = listOf(server), serviceRoutes = policy)
        val config = requireNotNull(ServiceRouteConfig.prepare(profile, server, context.filesDir))
        val withTun = json.parseToJsonElement(XrayConfigBuilder(json).build(profile, server.id, 42, serviceRouteSession = config)).jsonObject
        var withoutTun = JsonObject(withTun - "env" + ("inbounds" to JsonArray(withTun.getValue("inbounds").jsonArray.filter {
            it.jsonObject["protocol"]?.jsonPrimitive?.content != "tun"
        })))
        if (direct) withoutTun = JsonObject(withoutTun + ("outbounds" to JsonArray(withoutTun.getValue("outbounds").jsonArray.map { value ->
            if (value.jsonObject["tag"]?.jsonPrimitive?.content == server.tag) buildJsonObject {
                put("tag", server.tag); put("protocol", "freedom")
                put("settings", buildJsonObject { put("domainStrategy", "UseIPv4") })
            } else value
        })))
        val owner = System.nanoTime()
        runtime.claimOwner(owner)
        var lease: Long? = null
        try {
            lease = runtime.start(owner, withoutTun.toString(), object : DialerController {
                override fun protectFd(fd: Long): Boolean = true
            }, null)
            block(config, server)
        } finally {
            runtime.stop(owner, lease)
            runtime.retireOwner(owner)
            config.close()
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val runtime = XrayRuntime(json)
    }
}
