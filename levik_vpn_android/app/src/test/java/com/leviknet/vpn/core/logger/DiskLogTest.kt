package com.leviknet.vpn.core.logger

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiskLogTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("disk-log").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `redaction removes addresses, foreign hosts and identifiers`() {
        val line = redactLogLine(
            "user a.b@mail.ru via 203.0.113.7 and 2001:db8::1 to youtube.com?x=1 " +
                "id 8a1c0000-0000-4000-8000-000000000001 at 13:14:04 on leviknet.org " +
                "token ${"ab".repeat(30)} in LevikVpnService.kt at com.leviknet.vpn.vpn.Foo",
        )

        assertEquals(
            "user <email> via <ip> and <ip6> to <host>?<query> id <id> at 13:14:04 on leviknet.org " +
                "token <secret> in LevikVpnService.kt at com.leviknet.vpn.vpn.Foo",
            line,
        )
    }

    @Test
    fun `rotates at one megabyte and reads the newest lines`() {
        val log = DiskLog(directory)
        val chunk = "w ".repeat(400)
        repeat(3_000) { index ->
            log.write("t", "$index $chunk")
            if (index % 500 == 0) log.flush()
        }
        log.flush()

        assertTrue(File(directory, "levik-vpn.log.1").exists())
        assertTrue(File(directory, "levik-vpn.log").length() <= 1024 * 1024)
        val tail = log.read(10_000)
        assertTrue(tail.length <= 10_000)
        assertTrue(tail.trimEnd().endsWith(chunk.trimEnd()))
        assertTrue(tail.lines().last { it.isNotBlank() }.startsWith("t 2999 "))

        log.clear()
        assertFalse(File(directory, "levik-vpn.log").exists())
    }
}
