package com.leviknet.vpn.data

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DailyTraffic(
    val date: String, // YYYY-MM-DD
    val downloadedBytes: Long = 0L,
    val uploadedBytes: Long = 0L,
)

/**
 * Keeps daily traffic totals for the last 30 days. Samples arrive every second,
 * so persistence is batched: the JSON snapshot is written at most once per
 * [FLUSH_INTERVAL_MS] or on explicit [flush].
 */
class TrafficHistoryStore(
    context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val persistMutex = Mutex()
    private val flushScheduled = AtomicBoolean(false)
    private val mutableHistory = MutableStateFlow(loadHistory())
    @Volatile
    private var dirty = false

    val history: StateFlow<List<DailyTraffic>> = mutableHistory.asStateFlow()

    fun recordTraffic(downloaded: Long, uploaded: Long) {
        if (downloaded <= 0 && uploaded <= 0) return
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        mutableHistory.update { current ->
            val index = current.indexOfFirst { it.date == today }
            val updated = if (index >= 0) {
                val existing = current[index]
                current.toMutableList().also {
                    it[index] = existing.copy(
                        downloadedBytes = existing.downloadedBytes + downloaded,
                        uploadedBytes = existing.uploadedBytes + uploaded,
                    )
                }
            } else {
                current + DailyTraffic(
                    date = today,
                    downloadedBytes = downloaded,
                    uploadedBytes = uploaded,
                )
            }
            // Keep last 30 days, oldest first
            updated.sortedByDescending { it.date }.take(30).reversed()
        }
        dirty = true
        scheduleFlush()
    }

    suspend fun flush() {
        persistIfDirty()
    }

    /** Fire-and-forget flush for non-suspend lifecycle callbacks. */
    fun flushAsync() {
        scope.launch { persistIfDirty() }
    }

    fun clearHistory() {
        dirty = false
        mutableHistory.value = emptyList()
        preferences.edit(commit = true) {
            remove(KEY_TRAFFIC_DATA)
        }
    }

    fun exportHistoryCsv(): String {
        val items = mutableHistory.value
        val sb = StringBuilder("Date,Downloaded_Bytes,Uploaded_Bytes,Total_Bytes\n")
        for (item in items) {
            val total = item.downloadedBytes + item.uploadedBytes
            sb.append("${item.date},${item.downloadedBytes},${item.uploadedBytes},${total}\n")
        }
        return sb.toString()
    }

    fun exportHistoryJson(): String {
        return json.encodeToString(mutableHistory.value)
    }

    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(FLUSH_INTERVAL_MS)
            flushScheduled.set(false)
            persistIfDirty()
        }
    }

    private suspend fun persistIfDirty() {
        if (!dirty) return
        val snapshot = mutableHistory.value
        persistMutex.withLock {
            if (!dirty) return@withLock
            dirty = false
            saveHistory(snapshot)
        }
    }

    private fun loadHistory(): List<DailyTraffic> {
        val raw = preferences.getString(KEY_TRAFFIC_DATA, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<DailyTraffic>>(raw)
        }.getOrDefault(emptyList())
    }

    private fun saveHistory(list: List<DailyTraffic>) {
        val serialized = json.encodeToString(list)
        preferences.edit(commit = true) {
            putString(KEY_TRAFFIC_DATA, serialized)
        }
    }

    companion object {
        private const val PREFS_NAME = "levik_traffic_history_v1"
        private const val KEY_TRAFFIC_DATA = "traffic_history_json"
        private const val FLUSH_INTERVAL_MS = 30_000L
    }
}
