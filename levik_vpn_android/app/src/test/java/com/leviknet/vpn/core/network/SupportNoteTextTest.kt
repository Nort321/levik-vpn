package com.leviknet.vpn.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportNoteTextTest {
    @Test
    fun keepsTheNewestWholeLinesWithinTheNoteLimit() {
        val log = (1..400).joinToString("\n") { "2026-10-10T12:00:00Z I/Vpn: подключение $it" }
        val text = supportNoteText("REPORT\n", log)

        assertTrue(text.startsWith("REPORT\n\n=== Recent app log ===\n"))
        assertTrue(text.endsWith("подключение 400\n"))
        assertFalse(text.contains("подключение 1\n"))
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= MAX_SUPPORT_NOTE_BYTES)
    }

    @Test
    fun leavesTheReportAloneWithoutLogOrRoom() {
        assertEquals("REPORT", supportNoteText("REPORT", "   "))
        assertEquals("REPORT", supportNoteText("REPORT", "line", maxBytes = 10))
    }
}
