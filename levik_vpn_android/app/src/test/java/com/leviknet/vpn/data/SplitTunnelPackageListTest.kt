package com.leviknet.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelPackageListTest {
    @Test
    fun `parses mixed separators and ignores empty entries`() {
        assertEquals(
            setOf("com.android.chrome", "com.google.android.youtube", "org.example.app"),
            SplitTunnelPackageList.parse(
                " \r\ncom.android.chrome, ,\tcom.google.android.youtube\rorg.example.app\n ",
            ),
        )
        assertTrue(SplitTunnelPackageList.parse(" ,\t\r\n ").isEmpty())
        assertTrue(SplitTunnelPackageList.parse("").isEmpty())
    }

    @Test
    fun `supports unicode whitespace from copied text`() {
        assertEquals(
            setOf("com.example.one", "com.example.two", "com.example.three"),
            SplitTunnelPackageList.parse("\u00a0com.example.one\u2003com.example.two\u2028com.example.three"),
        )
    }

    @Test
    fun `rejects malformed identifiers without extracting partial matches`() {
        assertEquals(
            setOf("com.valid.app"),
            SplitTunnelPackageList.parse(
                "Chrome com..app .com.app com.app. 1com.app com.1app com._app " +
                    "com.bad-app com.app/path https://com.example/app \"com.quoted\" " +
                    "com.кириллица com.app! com.valid.app",
            ),
        )
    }

    @Test
    fun `deduplicates while preserving case digits and underscores`() {
        assertEquals(
            setOf("com.Example.app_2", "com.example.app_2"),
            SplitTunnelPackageList.parse("com.Example.app_2,com.Example.app_2 com.example.app_2"),
        )
    }

    @Test
    fun `accepts android framework package but rejects other bare words`() {
        assertEquals(setOf("android"), SplitTunnelPackageList.parse("android Android chrome system"))
    }

    @Test
    fun `formats in stable order with one package per line and no trailing newline`() {
        assertEquals(
            "com.android.chrome\ncom.google.android.youtube",
            SplitTunnelPackageList.format(setOf("com.google.android.youtube", "com.android.chrome")),
        )
        assertEquals("", SplitTunnelPackageList.format(emptySet()))
        assertEquals("com.example.app", SplitTunnelPackageList.format(setOf("com.example.app")))
    }

    @Test
    fun `export and import preserve a large selection including system packages`() {
        val packages = (1..10_000).mapTo(mutableSetOf()) { "com.example.app$it" } + "android"
        assertEquals(packages, SplitTunnelPackageList.parse(SplitTunnelPackageList.format(packages)))
    }

    @Test
    fun `handles a malformed token with many segments without recursive matching`() {
        assertTrue(SplitTunnelPackageList.parse("com.".repeat(10_000)).isEmpty())
    }
}
