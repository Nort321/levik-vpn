package com.leviknet.vpn.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.leviknet.vpn.BuildConfig
import com.leviknet.vpn.core.logger.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal class DirectAppUpdateManager(
    context: Context,
    manifestPublicKeyBase64: String,
    signingCertificateSha256: String,
    private val releaseClient: DirectReleaseClient = DirectReleaseClient(
        context.applicationContext,
        manifestPublicKeyBase64,
    ),
) : AppUpdateManager {
    private val context = context.applicationContext
    private val configuration = runCatching {
        val normalizedCertificate = UpdateManifestVerifier.normalizeSha256(
            signingCertificateSha256,
            "configured signing certificate",
        )
        UpdateConfiguration(
            verifier = UpdateManifestVerifier(
                publicKeyBase64 = manifestPublicKeyBase64,
                expectedSigningCertificateSha256 = normalizedCertificate,
                expectedPackageName = this.context.packageName,
                currentVersionCode = BuildConfig.VERSION_CODE,
            ),
            packageValidator = ApkPackageValidator(this.context, normalizedCertificate),
        )
    }
    private val updatesDirectory = File(this.context.cacheDir, UPDATES_DIRECTORY_NAME)
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    private var lastVerifiedUpdate: AppUpdateDto? = null

    init {
        cleanupStaleDownloads()
    }

    override suspend fun checkForUpdates(silent: Boolean): AppUpdateDto? = withContext(Dispatchers.IO) {
        val configured = configuration.getOrElse { error ->
            AppLogger.e(LOG_TAG, "Direct update verification is not configured", error)
            if (!silent) mutableState.value = UpdateState.Error(CONFIGURATION_ERROR_MESSAGE)
            return@withContext null
        }
        try {
            configured.packageValidator.validateInstalledApplication()
        } catch (error: Exception) {
            AppLogger.e(LOG_TAG, "Installed application signing validation failed", error)
            if (!silent) mutableState.value = UpdateState.Error(SIGNING_ERROR_MESSAGE)
            return@withContext null
        }

        if (!silent) mutableState.value = UpdateState.Checking
        when (val lookup = releaseClient.lookupLatestStableRelease(silent)) {
            ReleaseLookupResult.Skipped -> null
            ReleaseLookupResult.NoStableRelease -> {
                lastVerifiedUpdate = null
                if (!silent) mutableState.value = UpdateState.UpToDate
                null
            }
            is ReleaseLookupResult.Unavailable -> {
                AppLogger.e(LOG_TAG, "Direct update check failed", lookup.cause)
                if (!silent) mutableState.value = UpdateState.Error(lookup.message)
                null
            }
            is ReleaseLookupResult.Found -> {
                try {
                    val manifest = releaseClient.fetchReleaseAsset(
                        lookup.release.manifestUrl,
                        UpdateManifestVerifier.MAX_MANIFEST_BYTES,
                    )
                    val signature = releaseClient.fetchReleaseAsset(
                        lookup.release.signatureUrl,
                        UpdateManifestVerifier.MAX_SIGNATURE_FILE_BYTES,
                    )
                    val manifestSha256 = MessageDigest.getInstance("SHA-256")
                        .digest(manifest)
                        .toHex()
                    if (manifestSha256 != lookup.release.manifestSha256) {
                        throw UpdateVerificationException(
                            "Update manifest digest does not match the signed release feed",
                        )
                    }
                    val update = configured.verifier.verify(
                        manifestBytes = manifest,
                        signatureBytes = signature,
                        releaseAssets = lookup.release.assets,
                    )
                    if (update.latestVersionCode != lookup.release.versionCode) {
                        throw UpdateVerificationException(
                            "Update manifest version does not match the signed release feed",
                        )
                    }
                    releaseClient.recordVerifiedRelease(update.latestVersionCode)
                    lastVerifiedUpdate = update
                    mutableState.value = UpdateState.Available(update)
                    AppLogger.i(
                        LOG_TAG,
                        "Verified Direct update v${update.latestVersionName} (${update.latestVersionCode})",
                    )
                    update
                } catch (error: UpdateNotNewerException) {
                    lastVerifiedUpdate = null
                    if (!silent) mutableState.value = UpdateState.UpToDate
                    null
                } catch (error: Exception) {
                    releaseClient.recordAssetFailure(error)
                    AppLogger.e(LOG_TAG, "Direct update metadata validation failed", error)
                    if (!silent) mutableState.value = UpdateState.Error(VERIFICATION_ERROR_MESSAGE)
                    null
                }
            }
        }
    }

    override suspend fun downloadAndInstall(update: AppUpdateDto) = withContext(Dispatchers.IO) {
        val configured = configuration.getOrElse { error ->
            AppLogger.e(LOG_TAG, "Direct update verification is not configured", error)
            mutableState.value = UpdateState.Error(CONFIGURATION_ERROR_MESSAGE)
            return@withContext
        }
        val ready = mutableState.value as? UpdateState.ReadyToInstall
        if (ready != null && ready.info == update && ready.apkFile.isFile) {
            try {
                if (calculateSha256(ready.apkFile) != update.sha256) {
                    throw UpdateVerificationException("Ready APK SHA-256 does not match")
                }
                configured.packageValidator.validateArchive(ready.apkFile, update)
                launchPackageInstaller(ready.apkFile)
                lastVerifiedUpdate = null
                mutableState.value = UpdateState.Idle
            } catch (error: Exception) {
                ready.apkFile.delete()
                AppLogger.e(LOG_TAG, "Ready update APK validation failed", error)
                mutableState.value = UpdateState.Error(VERIFICATION_ERROR_MESSAGE)
            }
            return@withContext
        }

        if (lastVerifiedUpdate != update) {
            mutableState.value = UpdateState.Error(VERIFICATION_ERROR_MESSAGE)
            return@withContext
        }
        if (update.packageName != context.packageName || update.latestVersionCode <= BuildConfig.VERSION_CODE) {
            mutableState.value = UpdateState.Error(VERIFICATION_ERROR_MESSAGE)
            return@withContext
        }
        UpdateManifestVerifier.requireDirectReleaseAssetUrl(update.downloadUrl, expectedSuffix = ".apk")

        if (!updatesDirectory.exists() && !updatesDirectory.mkdirs()) {
            mutableState.value = UpdateState.Error("Unable to prepare update storage.")
            return@withContext
        }
        val temporary = File(updatesDirectory, "update-${update.latestVersionCode}.part.apk")
        val finalFile = File(updatesDirectory, "update-${update.latestVersionCode}.apk")
        temporary.delete()
        finalFile.delete()

        try {
            mutableState.value = UpdateState.Downloading(0, 0L, update.apkSize)
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(temporary).use { fileOutput ->
                val digestOutput = DigestOutputStream(fileOutput, digest)
                var lastProgressAt = 0L
                releaseClient.downloadReleaseAsset(
                    url = update.downloadUrl,
                    expectedSize = update.apkSize,
                    output = digestOutput,
                ) { downloaded ->
                    val now = System.currentTimeMillis()
                    if (now - lastProgressAt >= PROGRESS_INTERVAL_MS || downloaded == update.apkSize) {
                        val percent = ((downloaded * 100L) / update.apkSize).toInt().coerceIn(0, 100)
                        mutableState.value = UpdateState.Downloading(percent, downloaded, update.apkSize)
                        lastProgressAt = now
                    }
                }
                digestOutput.flush()
                fileOutput.fd.sync()
            }
            val calculatedSha256 = digest.digest().toHex()
            if (calculatedSha256 != update.sha256) {
                throw IOException("Downloaded APK SHA-256 does not match the signed manifest")
            }
            configured.packageValidator.validateArchive(temporary, update)
            moveAtomically(temporary, finalFile)
            mutableState.value = UpdateState.ReadyToInstall(finalFile, update)
        } catch (error: CancellationException) {
            temporary.delete()
            finalFile.delete()
            throw error
        } catch (error: Exception) {
            temporary.delete()
            finalFile.delete()
            releaseClient.recordAssetFailure(error)
            AppLogger.e(LOG_TAG, "Direct update download or validation failed", error)
            mutableState.value = UpdateState.Error(DOWNLOAD_ERROR_MESSAGE)
        } finally {
            temporary.delete()
        }
    }

    override fun dismiss() {
        (mutableState.value as? UpdateState.ReadyToInstall)?.apkFile?.delete()
        lastVerifiedUpdate = null
        mutableState.value = UpdateState.Idle
    }

    private fun launchPackageInstaller(apkFile: File) {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun cleanupStaleDownloads() {
        updatesDirectory.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".part.apk") || file.name.startsWith("update-"))) {
                file.delete()
            }
        }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private data class UpdateConfiguration(
        val verifier: UpdateManifestVerifier,
        val packageValidator: ApkPackageValidator,
    )

    companion object {
        private const val LOG_TAG = "DirectUpdate"
        private const val UPDATES_DIRECTORY_NAME = "updates"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val PROGRESS_INTERVAL_MS = 150L
        private const val CONFIGURATION_ERROR_MESSAGE = "Secure Direct updates are not configured for this build."
        private const val SIGNING_ERROR_MESSAGE = "This installation cannot use the configured update channel."
        private const val VERIFICATION_ERROR_MESSAGE = "Update verification failed. No package was installed."
        private const val DOWNLOAD_ERROR_MESSAGE = "Unable to download and verify the update."
    }
}

internal class ApkPackageValidator(
    private val context: Context,
    private val expectedSigningCertificateSha256: String,
) {
    fun validateInstalledApplication() {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, signingFlags())
        validateSigningCertificate(packageInfo)
    }

    fun validateArchive(apkFile: File, update: AppUpdateDto) {
        if (!apkFile.isFile || apkFile.length() != update.apkSize) {
            throw UpdateVerificationException("Downloaded APK size is invalid")
        }
        val packageInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, signingFlags())
            ?: throw UpdateVerificationException("Downloaded file is not a valid APK")
        if (packageInfo.packageName != context.packageName || packageInfo.packageName != update.packageName) {
            throw UpdateVerificationException("Downloaded APK package name does not match")
        }
        val archiveVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
        if (archiveVersionCode != update.latestVersionCode.toLong() ||
            archiveVersionCode <= BuildConfig.VERSION_CODE.toLong()
        ) {
            throw UpdateVerificationException("Downloaded APK version code is invalid")
        }
        if (packageInfo.versionName != update.latestVersionName) {
            throw UpdateVerificationException("Downloaded APK version name does not match")
        }
        validateSigningCertificate(packageInfo)
    }

    @Suppress("DEPRECATION")
    private fun validateSigningCertificate(packageInfo: PackageInfo) {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            packageInfo.signatures.orEmpty()
        }
        if (signatures.size != 1) {
            throw UpdateVerificationException("APK must have exactly one current signing certificate")
        }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(signatures.single().toByteArray())
            .toHex()
        if (fingerprint != expectedSigningCertificateSha256) {
            throw UpdateVerificationException("APK signing certificate does not match the pinned certificate")
        }
    }

    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
