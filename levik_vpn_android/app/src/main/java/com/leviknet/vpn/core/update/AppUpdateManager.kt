package com.leviknet.vpn.core.update

import java.io.File
import kotlinx.coroutines.flow.StateFlow

data class AppUpdateDto(
    val packageName: String,
    val latestVersionCode: Int,
    val latestVersionName: String,
    val downloadUrl: String,
    val apkSize: Long,
    val sha256: String,
    val signingCertificateSha256: String,
    val titleRu: String? = null,
    val titleEn: String? = null,
    val changelogRu: String? = null,
    val changelogEn: String? = null,
    val forceUpdate: Boolean = false,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val info: AppUpdateDto) : UpdateState
    data class Downloading(
        val progressPercent: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : UpdateState
    data class ReadyToInstall(val apkFile: File, val info: AppUpdateDto) : UpdateState
    data object UpToDate : UpdateState
    data class Error(val message: String) : UpdateState
}

interface AppUpdateManager {
    val state: StateFlow<UpdateState>

    suspend fun checkForUpdates(silent: Boolean = false): AppUpdateDto?

    suspend fun downloadAndInstall(update: AppUpdateDto)

    fun dismiss()
}
