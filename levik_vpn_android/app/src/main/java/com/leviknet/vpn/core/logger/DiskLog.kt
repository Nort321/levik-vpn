package com.leviknet.vpn.core.logger

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val MAX_FILE_BYTES = 1024L * 1024
private const val FLUSH_DELAY_MS = 1_000L
private const val MAX_BUFFERED_LINES = 2_000
private const val MAX_LINE_LENGTH = 1_000
private val OWN_HOST = Regex("^(?:[a-z0-9-]+\\.)*leviknet\\.(?:org|com)$", RegexOption.IGNORE_CASE)
private val FILE_NAME = Regex("^\\w+\\.(?:kt|java|so|json|xml|apk)$", RegexOption.IGNORE_CASE)
private val CONTROL = Regex("[\\u0000-\\u0008\\u000b-\\u001f]")
private val EMAIL = Regex("\\b[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+\\b")
private val UUID = Regex("\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b", RegexOption.IGNORE_CASE)
private val QUERY = Regex("\\?[^\\s\"'<>]+")
private val IPV4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
// Full or "::"-compressed IPv6; clock times such as 13:14:04 do not match.
private val IPV6 = Regex(
    "(?<![\\w:.])(?:(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}|" +
        "(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*)?::(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*)?)(?![\\w:])",
    RegexOption.IGNORE_CASE,
)
private val HOST = Regex("\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}\\b", RegexOption.IGNORE_CASE)
private val SECRET = Regex("\\b[A-Za-z0-9_-]{40,}\\b")

/**
 * Removes what could identify the user or what they visit: addresses,
 * domains other than Levik's own, identifiers, e-mails and URL queries.
 * Stack traces keep their class and file names.
 */
fun redactLogLine(line: String): String = line
    .replace(CONTROL, " ")
    .replace(EMAIL, "<email>")
    .replace(UUID, "<id>")
    .replace(QUERY, "?<query>")
    .replace(IPV4, "<ip>")
    .replace(IPV6, "<ip6>")
    .replace(HOST) { match ->
        val host = match.value
        val isCode = host.startsWith("com.leviknet.") || host.startsWith("java.") ||
            host.startsWith("javax.") || host.startsWith("kotlin.") || host.startsWith("kotlinx.") ||
            host.startsWith("android.") || host.startsWith("androidx.")
        if (isCode || OWN_HOST.matches(host) || FILE_NAME.matches(host)) host else "<host>"
    }
    .replace(SECRET, "<secret>")
    .take(MAX_LINE_LENGTH)

/**
 * Local diagnostics for support requests. Stays on this device; the user
 * decides whether to attach it to a ticket. Two files of at most 1 MiB.
 */
class DiskLog(
    private val directory: File,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "levik-disk-log").apply { isDaemon = true }
    },
) {
    private val buffer = ArrayDeque<String>()
    private var flushScheduled = false

    fun write(timestamp: String, line: String) {
        val text = redactLogLine(line).lines().joinToString("\n") { "$timestamp $it" }
        synchronized(buffer) {
            while (buffer.size >= MAX_BUFFERED_LINES) buffer.removeFirst()
            buffer.addLast(text)
            if (flushScheduled) return
            flushScheduled = true
        }
        runCatching { executor.schedule(::flush, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS) }
    }

    /** Writes buffered lines now; runs on the caller's thread. */
    fun flush() {
        val chunk = synchronized(buffer) {
            flushScheduled = false
            if (buffer.isEmpty()) return
            val text = buffer.joinToString(separator = "\n", postfix = "\n")
            buffer.clear()
            text
        }
        synchronized(this) {
            runCatching {
                directory.mkdirs()
                val current = file("")
                if (current.length() + chunk.toByteArray().size > MAX_FILE_BYTES) {
                    val previous = file(".1")
                    previous.delete()
                    current.renameTo(previous)
                }
                current.appendText(chunk)
            }
        }
    }

    /** The newest lines, oldest first, for a support report. */
    fun read(maxBytes: Int = MAX_FILE_BYTES.toInt()): String {
        flush()
        val text = synchronized(this) {
            listOf(file(".1"), file("")).joinToString("") { runCatching { it.readText() }.getOrDefault("") }
        }
        if (text.length <= maxBytes) return text
        val start = text.indexOf('\n', text.length - maxBytes)
        return if (start < 0) "" else text.substring(start + 1)
    }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        synchronized(this) {
            file("").delete()
            file(".1").delete()
        }
    }

    private fun file(suffix: String) = File(directory, "levik-vpn.log$suffix")
}
