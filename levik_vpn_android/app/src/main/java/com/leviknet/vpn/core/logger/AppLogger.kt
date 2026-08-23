package com.leviknet.vpn.core.logger

import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class LogEntry(
    val timestamp: Instant = Instant.now(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwableText: String? = null,
) {
    fun toFormattedString(formatter: DateTimeFormatter = DEFAULT_FORMATTER): String {
        val timeStr = formatter.format(timestamp.atZone(ZoneId.systemDefault()))
        val levelStr = level.name.take(1)
        val basic = "[$timeStr] [$levelStr/$tag]: $message"
        return if (throwableText != null) {
            "$basic\n$throwableText"
        } else {
            basic
        }
    }

    companion object {
        private val DEFAULT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}

object AppLogger {
    private const val MAX_LOGS = 500
    private val logBuffer = ConcurrentLinkedDeque<LogEntry>()

    private val SENSITIVE_TOKEN_REGEX = Regex(
        "(?i)(bearer\\s+[a-zA-Z0-9._~+/-]+=*|" +
            "token[\"':= ]+([a-zA-Z0-9._~+/-]+)|" +
            "password[\"':= ]+([^\"'\\s,]+)|" +
            "key[\"':= ]+([^\"'\\s,]+)|" +
            "id[\"':= ]+([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}))"
    )

    fun d(tag: String, message: String) {
        Log.d(tag, platformMessage(log(LogLevel.DEBUG, tag, message)))
    }

    fun i(tag: String, message: String) {
        Log.i(tag, platformMessage(log(LogLevel.INFO, tag, message)))
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, platformMessage(log(LogLevel.WARN, tag, message, throwable)))
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, platformMessage(log(LogLevel.ERROR, tag, message, throwable)))
    }

    private fun log(
        level: LogLevel,
        tag: String,
        rawMessage: String,
        throwable: Throwable? = null,
    ): LogEntry {
        val sanitized = sanitize(rawMessage)
        val sanitizedThrowable = throwable
            ?.stackTraceToString()
            ?.let(::sanitize)
        val entry = LogEntry(
            level = level,
            tag = tag,
            message = sanitized,
            throwableText = sanitizedThrowable,
        )
        logBuffer.addLast(entry)
        while (logBuffer.size > MAX_LOGS) {
            logBuffer.pollFirst()
        }
        return entry
    }

    private fun platformMessage(entry: LogEntry): String =
        entry.throwableText?.let { "${entry.message}\n$it" } ?: entry.message

    fun getLogs(): List<LogEntry> = logBuffer.toList()

    fun getFormattedLogs(): String =
        logBuffer.joinToString("\n") { it.toFormattedString() }

    fun clear() {
        logBuffer.clear()
    }

    internal fun sanitize(input: String): String {
        return input.replace(SENSITIVE_TOKEN_REGEX) { match ->
            val full = match.value
            val prefix = full.substringBefore(":")
            if (prefix != full) {
                "$prefix: [REDACTED]"
            } else {
                "[REDACTED]"
            }
        }
    }
}
