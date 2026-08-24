package com.leviknet.vpn.core.update

import java.net.URI
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class DirectUpdateManifest(
    val schemaVersion: Int,
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha256: String,
    val signingCertificateSha256: String,
    val titleRu: String? = null,
    val titleEn: String? = null,
    val changelogRu: String? = null,
    val changelogEn: String? = null,
    val forceUpdate: Boolean = false,
)

internal data class PublishedReleaseAsset(
    val name: String,
    val url: String,
    val size: Long,
)

internal class UpdateVerificationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

internal class UpdateNotNewerException : Exception("Signed update is not newer")

internal class UpdateManifestVerifier(
    publicKeyBase64: String,
    expectedSigningCertificateSha256: String,
    private val expectedPackageName: String,
    private val currentVersionCode: Int,
    private val json: Json = STRICT_JSON,
) {
    private val publicKey = parsePublicKey(publicKeyBase64)
    private val expectedSigningCertificateSha256 = normalizeSha256(
        value = expectedSigningCertificateSha256,
        fieldName = "configured signing certificate",
    )

    fun verify(
        manifestBytes: ByteArray,
        signatureBytes: ByteArray,
        releaseAssets: List<PublishedReleaseAsset>,
    ): AppUpdateDto {
        requireVerifiedSignature(manifestBytes, signatureBytes)
        val manifest = try {
            json.decodeFromString<DirectUpdateManifest>(manifestBytes.decodeToString())
        } catch (error: Exception) {
            throw UpdateVerificationException("Invalid update manifest JSON", error)
        }

        if (manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw UpdateVerificationException("Unsupported update manifest schema")
        }
        if (manifest.packageName != expectedPackageName) {
            throw UpdateVerificationException("Update package does not match this application")
        }
        if (manifest.versionCode <= currentVersionCode) {
            throw UpdateNotNewerException()
        }
        if (manifest.versionCode <= 0) {
            throw UpdateVerificationException("Invalid update version code")
        }
        validateVersionName(manifest.versionName)
        if (manifest.apkSize !in MIN_APK_BYTES..MAX_APK_BYTES) {
            throw UpdateVerificationException("Update APK size is outside the allowed range")
        }

        val apkSha256 = normalizeSha256(manifest.apkSha256, "APK")
        val manifestSigningCertificate = normalizeSha256(
            manifest.signingCertificateSha256,
            "manifest signing certificate",
        )
        if (manifestSigningCertificate != expectedSigningCertificateSha256) {
            throw UpdateVerificationException("Update signing certificate does not match the pinned certificate")
        }

        requireDirectReleaseAssetUrl(manifest.apkUrl, expectedSuffix = ".apk")
        val publishedApk = releaseAssets.singleOrNull { asset -> asset.url == manifest.apkUrl }
            ?: throw UpdateVerificationException("Signed APK is not an asset of the selected stable release")
        if (publishedApk.size != manifest.apkSize || !publishedApk.name.endsWith(".apk")) {
            throw UpdateVerificationException("Published APK metadata does not match the signed manifest")
        }

        return AppUpdateDto(
            packageName = manifest.packageName,
            latestVersionCode = manifest.versionCode,
            latestVersionName = manifest.versionName,
            downloadUrl = manifest.apkUrl,
            apkSize = manifest.apkSize,
            sha256 = apkSha256,
            signingCertificateSha256 = manifestSigningCertificate,
            titleRu = validateOptionalText(manifest.titleRu, MAX_TITLE_LENGTH, "Russian title"),
            titleEn = validateOptionalText(manifest.titleEn, MAX_TITLE_LENGTH, "English title"),
            changelogRu = validateOptionalText(manifest.changelogRu, MAX_CHANGELOG_LENGTH, "Russian changelog"),
            changelogEn = validateOptionalText(manifest.changelogEn, MAX_CHANGELOG_LENGTH, "English changelog"),
            forceUpdate = manifest.forceUpdate,
        )
    }

    private fun requireVerifiedSignature(manifestBytes: ByteArray, signatureBytes: ByteArray) {
        if (manifestBytes.isEmpty() || manifestBytes.size > MAX_MANIFEST_BYTES) {
            throw UpdateVerificationException("Update manifest size is invalid")
        }
        if (signatureBytes.isEmpty() || signatureBytes.size > MAX_SIGNATURE_FILE_BYTES) {
            throw UpdateVerificationException("Update signature size is invalid")
        }
        val signature = try {
            Base64.getDecoder().decode(signatureBytes.decodeToString().trim())
        } catch (error: IllegalArgumentException) {
            throw UpdateVerificationException("Update signature is not valid Base64", error)
        }
        val verified = try {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(manifestBytes)
                verify(signature)
            }
        } catch (error: Exception) {
            throw UpdateVerificationException("Unable to verify update signature", error)
        }
        if (!verified) {
            throw UpdateVerificationException("Update manifest signature verification failed")
        }
    }

    private fun validateVersionName(value: String) {
        if (value.isBlank() || value.length > MAX_VERSION_NAME_LENGTH || value != value.trim()) {
            throw UpdateVerificationException("Invalid update version name")
        }
        if (value.any(Char::isISOControl)) {
            throw UpdateVerificationException("Invalid update version name")
        }
    }

    private fun validateOptionalText(value: String?, maxLength: Int, fieldName: String): String? {
        if (value == null) return null
        if (value.length > maxLength || value.any { character -> character == '\u0000' }) {
            throw UpdateVerificationException("$fieldName is invalid")
        }
        return value
    }

    companion object {
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val MIN_APK_BYTES = 1L * 1024 * 1024
        const val MAX_APK_BYTES = 512L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 64 * 1024
        const val MAX_SIGNATURE_FILE_BYTES = 1024

        private const val MAX_VERSION_NAME_LENGTH = 64
        private const val MAX_TITLE_LENGTH = 200
        private const val MAX_CHANGELOG_LENGTH = 8_000
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        private val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            explicitNulls = false
        }

        internal fun normalizeSha256(value: String, fieldName: String): String {
            val normalized = value.trim().replace(":", "").lowercase()
            if (!SHA256_PATTERN.matches(normalized)) {
                throw UpdateVerificationException("$fieldName SHA-256 is invalid")
            }
            return normalized
        }

        internal fun requireDirectReleaseAssetUrl(
            value: String,
            expectedSuffix: String,
        ): URI {
            val uri = try {
                URI(value)
            } catch (error: Exception) {
                throw UpdateVerificationException("Invalid Direct release asset URL", error)
            }
            val rawPath = uri.rawPath.orEmpty()
            val releasePathSegments = rawPath
                .removePrefix(DIRECT_RELEASE_PATH_PREFIX)
                .split('/')
            val valid = uri.scheme == "https" &&
                uri.host == DIRECT_RELEASE_HOST &&
                uri.port in setOf(-1, 443) &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                rawPath.startsWith(DIRECT_RELEASE_PATH_PREFIX) &&
                releasePathSegments.size == 2 &&
                releasePathSegments.all { segment ->
                    SAFE_RELEASE_PATH_SEGMENT.matches(segment) && segment !in DOT_SEGMENTS
                } &&
                rawPath.endsWith(expectedSuffix, ignoreCase = false)
            if (!valid) {
                throw UpdateVerificationException("Update asset URL is outside the trusted Direct release origin")
            }
            return uri
        }

        private fun parsePublicKey(value: String): ECPublicKey {
            if (value.isBlank()) {
                throw UpdateVerificationException("Update manifest public key is not configured")
            }
            val encoded = try {
                Base64.getDecoder().decode(value.trim())
            } catch (error: IllegalArgumentException) {
                throw UpdateVerificationException("Update manifest public key is not valid Base64", error)
            }
            val key = try {
                KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
            } catch (error: Exception) {
                throw UpdateVerificationException("Update manifest public key is invalid", error)
            }
            if (key !is ECPublicKey || !key.hasP256Parameters()) {
                throw UpdateVerificationException("Update manifest key must be an ECDSA P-256 public key")
            }
            return key
        }

        private fun ECPublicKey.hasP256Parameters(): Boolean {
            val expected = AlgorithmParameters.getInstance("EC").run {
                init(ECGenParameterSpec("secp256r1"))
                getParameterSpec(ECParameterSpec::class.java)
            }
            return params.curve == expected.curve &&
                params.generator == expected.generator &&
                params.order == expected.order &&
                params.cofactor == expected.cofactor
        }

        private const val DIRECT_RELEASE_HOST = "leviknet.com"
        private const val DIRECT_RELEASE_PATH_PREFIX = "/downloads/android/stable/"
        private val SAFE_RELEASE_PATH_SEGMENT = Regex("^[A-Za-z0-9._+-]+$")
        private val DOT_SEGMENTS = setOf(".", "..")
    }
}
