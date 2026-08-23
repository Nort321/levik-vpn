package com.leviknet.vpn.core.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.leviknet.vpn.BuildConfig
import com.leviknet.vpn.core.logger.AppLogger
import com.leviknet.vpn.core.network.AppUpdateDto
import com.leviknet.vpn.core.network.MobileApiClient
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val info: AppUpdateDto) : UpdateState
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : UpdateState
    data class ReadyToInstall(val apkFile: File, val info: AppUpdateDto) : UpdateState
    data object UpToDate : UpdateState
    data class Error(val message: String) : UpdateState
}

class AppUpdateManager(
    private val context: Context,
    private val apiClient: MobileApiClient,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    suspend fun checkForUpdates(silent: Boolean = false): AppUpdateDto? = withContext(Dispatchers.IO) {
        if (!silent) _state.value = UpdateState.Checking
        try {
            val response = apiClient.checkForUpdates()
            val update = response.update
            if (update != null && update.latestVersionCode > BuildConfig.VERSION_CODE) {
                AppLogger.i(LOG_TAG, "Update available: v${update.latestVersionName} (${update.latestVersionCode})")
                _state.value = UpdateState.Available(update)
                return@withContext update
            } else {
                AppLogger.d(LOG_TAG, "App is up to date (current: v${BuildConfig.VERSION_NAME} code ${BuildConfig.VERSION_CODE})")
                if (!silent) _state.value = UpdateState.UpToDate
                return@withContext null
            }
        } catch (e: Exception) {
            AppLogger.e(LOG_TAG, "Failed to check for updates", e)
            if (!silent) _state.value = UpdateState.Error(e.message ?: "Failed to check for updates")
            return@withContext null
        }
    }

    suspend fun downloadAndInstall(update: AppUpdateDto) = withContext(Dispatchers.IO) {
        try {
            _state.value = UpdateState.Downloading(0, 0, 0)
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updatesDir, "LevikVPN-${update.latestVersionName}.apk")
            if (apkFile.exists()) {
                apkFile.delete()
            }

            val url = URL(update.downloadUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LevikVPN-Android/${BuildConfig.VERSION_NAME}")
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    var lastReportTime = System.currentTimeMillis()
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val now = System.currentTimeMillis()
                        if (now - lastReportTime > 150) {
                            val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                            _state.value = UpdateState.Downloading(percent, downloadedBytes, totalBytes)
                            lastReportTime = now
                        }
                    }
                }
            }

            // Verify SHA-256 if provided
            if (!update.sha256.isNullOrBlank()) {
                val calculatedSha = calculateSha256(apkFile)
                if (!calculatedSha.equals(update.sha256.trim(), ignoreCase = true)) {
                    apkFile.delete()
                    throw IllegalStateException("SHA-256 verification failed (expected ${update.sha256}, got $calculatedSha)")
                }
            }

            _state.value = UpdateState.ReadyToInstall(apkFile, update)
            installApk(apkFile)
        } catch (e: Exception) {
            AppLogger.e(LOG_TAG, "Failed to download update", e)
            _state.value = UpdateState.Error(e.message ?: "Failed to download update")
        }
    }

    fun installApk(apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            AppLogger.e(LOG_TAG, "Failed to launch package installer", e)
            _state.value = UpdateState.Error("Failed to launch package installer: ${e.message}")
        }
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val LOG_TAG = "AppUpdateManager"
    }
}
