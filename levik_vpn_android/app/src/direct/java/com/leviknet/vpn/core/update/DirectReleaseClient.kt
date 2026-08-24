package com.leviknet.vpn.core.update

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.min
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class DirectReleaseAsset(
    val name: String,
    val size: Long,
    val url: String,
)

@Serializable
internal data class DirectReleaseFeed(
    val schemaVersion: Int,
    val channel: String,
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<DirectReleaseAsset>,
)

internal data class StableDirectRelease(
    val tagName: String,
    val manifestUrl: String,
    val signatureUrl: String,
    val assets: List<PublishedReleaseAsset>,
)

internal sealed interface ReleaseLookupResult {
    data class Found(val release: StableDirectRelease) : ReleaseLookupResult
    data object NoStableRelease : ReleaseLookupResult
    data object Skipped : ReleaseLookupResult
    data class Unavailable(val message: String, val cause: Throwable? = null) : ReleaseLookupResult
}

internal object DirectReleaseParser {
    fun parseLatestStableRelease(body: ByteArray, json: Json): StableDirectRelease? {
        val release = try {
            json.decodeFromString(DirectReleaseFeed.serializer(), body.decodeToString())
        } catch (error: Exception) {
            throw IOException("Invalid Direct release feed", error)
        }
        if (release.schemaVersion != SUPPORTED_SCHEMA_VERSION || release.channel != STABLE_CHANNEL) {
            throw IOException("Unsupported Direct release feed")
        }
        if (release.draft || release.prerelease) return null
        if (!STABLE_TAG_PATTERN.matches(release.tagName)) {
            throw IOException("Invalid stable release tag")
        }
        val manifest = release.assets.singleOrNull { asset ->
            asset.name == DirectReleaseClient.MANIFEST_ASSET_NAME
        } ?: throw IOException("Latest stable release does not contain update.json")
        val signature = release.assets.singleOrNull { asset ->
            asset.name == DirectReleaseClient.SIGNATURE_ASSET_NAME
        } ?: throw IOException("Latest stable release does not contain update.json.sig")

        UpdateManifestVerifier.requireDirectReleaseAssetUrl(
            manifest.url,
            DirectReleaseClient.MANIFEST_ASSET_NAME,
        )
        UpdateManifestVerifier.requireDirectReleaseAssetUrl(
            signature.url,
            DirectReleaseClient.SIGNATURE_ASSET_NAME,
        )
        if (manifest.size !in 1..UpdateManifestVerifier.MAX_MANIFEST_BYTES.toLong()) {
            throw IOException("Published update manifest size is invalid")
        }
        if (signature.size !in 1..UpdateManifestVerifier.MAX_SIGNATURE_FILE_BYTES.toLong()) {
            throw IOException("Published update signature size is invalid")
        }

        return StableDirectRelease(
            tagName = release.tagName,
            manifestUrl = manifest.url,
            signatureUrl = signature.url,
            assets = release.assets.map { asset ->
                PublishedReleaseAsset(
                    name = asset.name,
                    url = asset.url,
                    size = asset.size,
                )
            },
        )
    }

    private const val SUPPORTED_SCHEMA_VERSION = 1
    private const val STABLE_CHANNEL = "stable"
    private val STABLE_TAG_PATTERN = Regex("^v[0-9]+\\.[0-9]+\\.[0-9]+$")
}

internal class ReleaseHttpException(
    val statusCode: Int,
    val retryAfterSeconds: Long?,
    val rateLimitResetEpochSeconds: Long?,
) : IOException("Update service request failed with HTTP $statusCode")

internal object UpdateCheckSchedule {
    const val SUCCESS_INTERVAL_MS = 18L * 60 * 60 * 1000
    const val NOT_FOUND_BACKOFF_MS = 12L * 60 * 60 * 1000
    const val MIN_RATE_LIMIT_BACKOFF_MS = 15L * 60 * 1000
    const val MAX_BACKOFF_MS = 24L * 60 * 60 * 1000

    fun transientBackoffMs(consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 5)
        return min(30L * 60 * 1000 * (1L shl exponent), 12L * 60 * 60 * 1000)
    }

    fun rateLimitBackoffMs(
        nowMillis: Long,
        retryAfterSeconds: Long?,
        resetEpochSeconds: Long?,
    ): Long {
        val retryAfter = retryAfterSeconds?.times(1000)
        val untilReset = resetEpochSeconds?.times(1000)?.minus(nowMillis)
        return maxOf(retryAfter ?: 0L, untilReset ?: 0L, MIN_RATE_LIMIT_BACKOFF_MS)
            .coerceAtMost(MAX_BACKOFF_MS)
    }
}

internal class DirectReleaseClient(
    context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val metadataDirectory = File(context.filesDir, METADATA_DIRECTORY_NAME)
    private val cachedReleasesFile = File(metadataDirectory, RELEASE_CACHE_FILE_NAME)

    fun lookupLatestStableRelease(silent: Boolean): ReleaseLookupResult {
        val now = nowMillis()
        val retryAt = preferences.getLong(KEY_RETRY_AT, 0L)
        if (retryAt > now) {
            return if (silent) {
                ReleaseLookupResult.Skipped
            } else {
                ReleaseLookupResult.Unavailable("Update service is temporarily unavailable. Try again later.")
            }
        }
        val nextCheckAt = preferences.getLong(KEY_NEXT_CHECK_AT, 0L)
        if (silent && nextCheckAt > now) {
            return ReleaseLookupResult.Skipped
        }

        return try {
            val headers = buildMap {
                put("Accept", JSON_ACCEPT)
                preferences.getString(KEY_ETAG, null)
                    ?.takeIf { etag -> etag.length <= MAX_ETAG_LENGTH }
                    ?.let { etag -> put("If-None-Match", etag) }
            }
            val response = request(
                url = RELEASE_FEED_URL,
                headers = headers,
                maxBytes = MAX_RELEASE_RESPONSE_BYTES,
                allowedHosts = setOf(RELEASE_HOST),
                allowRedirects = false,
            )
            val body = when (response.statusCode) {
                HttpURLConnection.HTTP_OK -> {
                    val bytes = requireNotNull(response.body)
                    writeCacheAtomically(bytes)
                    response.etag
                        ?.takeIf { etag -> etag.length <= MAX_ETAG_LENGTH }
                        ?.let { etag -> preferences.edit().putString(KEY_ETAG, etag).apply() }
                    bytes
                }
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    readCache() ?: run {
                        preferences.edit().remove(KEY_ETAG).apply()
                        throw IOException("Update feed returned 304 without cached metadata")
                    }
                }
                else -> throw ReleaseHttpException(
                    statusCode = response.statusCode,
                    retryAfterSeconds = response.retryAfterSeconds,
                    rateLimitResetEpochSeconds = response.rateLimitResetEpochSeconds,
                )
            }

            val release = DirectReleaseParser.parseLatestStableRelease(body, json)
            recordSuccess(now)
            release?.let(ReleaseLookupResult::Found) ?: ReleaseLookupResult.NoStableRelease
        } catch (error: ReleaseHttpException) {
            recordHttpFailure(error, now)
            ReleaseLookupResult.Unavailable(httpFailureMessage(error.statusCode), error)
        } catch (error: Exception) {
            recordTransientFailure(now)
            ReleaseLookupResult.Unavailable("Unable to check for updates. Check your connection and try again.", error)
        }
    }

    fun fetchReleaseAsset(url: String, maxBytes: Int): ByteArray {
        UpdateManifestVerifier.requireDirectReleaseAssetUrl(
            value = url,
            expectedSuffix = if (url.endsWith(SIGNATURE_ASSET_NAME)) SIGNATURE_ASSET_NAME else MANIFEST_ASSET_NAME,
        )
        val response = request(
            url = url,
            headers = mapOf("Accept" to "application/octet-stream"),
            maxBytes = maxBytes,
            allowedHosts = setOf(RELEASE_HOST),
            allowRedirects = false,
        )
        if (response.statusCode !in 200..299) {
            throw ReleaseHttpException(
                statusCode = response.statusCode,
                retryAfterSeconds = response.retryAfterSeconds,
                rateLimitResetEpochSeconds = response.rateLimitResetEpochSeconds,
            )
        }
        return requireNotNull(response.body)
    }

    fun downloadReleaseAsset(
        url: String,
        expectedSize: Long,
        output: OutputStream,
        onProgress: (downloadedBytes: Long) -> Unit,
    ) {
        UpdateManifestVerifier.requireDirectReleaseAssetUrl(url, expectedSuffix = ".apk")
        var current = URI(url)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            validateHttpsOrigin(current, setOf(RELEASE_HOST))
            val connection = (URL(current.toASCIIString()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = APK_READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
            }
            try {
                val status = connection.responseCode
                if (status in REDIRECT_STATUS_CODES) {
                    if (redirectCount == MAX_REDIRECTS) throw IOException("Too many APK download redirects")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("APK redirect is missing Location")
                    current = current.resolve(location)
                    return@repeat
                }
                if (status !in 200..299) {
                    throw ReleaseHttpException(
                        statusCode = status,
                        retryAfterSeconds = connection.getHeaderField("Retry-After")?.toLongOrNull(),
                        rateLimitResetEpochSeconds = connection
                            .getHeaderField("X-RateLimit-Reset")
                            ?.toLongOrNull(),
                    )
                }
                val contentLength = connection.contentLengthLong
                if (contentLength >= 0L && contentLength != expectedSize) {
                    throw IOException("APK Content-Length does not match the signed manifest")
                }
                var downloaded = 0L
                val buffer = ByteArray(32 * 1024)
                connection.inputStream.use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > expectedSize) {
                            throw IOException("APK exceeds the signed size")
                        }
                        output.write(buffer, 0, count)
                        onProgress(downloaded)
                    }
                }
                if (downloaded != expectedSize) {
                    throw IOException("APK size does not match the signed manifest")
                }
                return
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many APK download redirects")
    }

    fun recordAssetFailure(error: Exception) {
        val now = nowMillis()
        if (error is ReleaseHttpException) {
            recordHttpFailure(error, now)
        } else {
            recordTransientFailure(now)
        }
    }

    private fun request(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
        allowedHosts: Set<String>,
        allowRedirects: Boolean,
    ): HttpResponse {
        var current = URI(url)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            validateHttpsOrigin(current, allowedHosts)
            val connection = (URL(current.toASCIIString()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            try {
                val status = connection.responseCode
                if (status in REDIRECT_STATUS_CODES) {
                    if (!allowRedirects || redirectCount == MAX_REDIRECTS) {
                        throw IOException("Unexpected or excessive HTTPS redirect")
                    }
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("HTTPS redirect is missing Location")
                    current = current.resolve(location)
                    return@repeat
                }
                val contentLength = connection.contentLengthLong
                if (contentLength > maxBytes) {
                    throw IOException("HTTP response exceeds the allowed size")
                }
                val body = if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    null
                } else if (status in 200..299) {
                    connection.inputStream.use { input -> readBounded(input, maxBytes) }
                } else {
                    null
                }
                return HttpResponse(
                    statusCode = status,
                    body = body,
                    etag = connection.getHeaderField("ETag"),
                    retryAfterSeconds = connection.getHeaderField("Retry-After")?.toLongOrNull(),
                    rateLimitResetEpochSeconds = connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull(),
                )
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many HTTPS redirects")
    }

    private fun validateHttpsOrigin(uri: URI, allowedHosts: Set<String>) {
        val valid = uri.scheme == "https" &&
            uri.host in allowedHosts &&
            uri.port in setOf(-1, 443) &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null
        if (!valid) throw IOException("HTTPS origin is not allowed")
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(min(maxBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("HTTP response exceeds the allowed size")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun readCache(): ByteArray? = try {
        if (!cachedReleasesFile.isFile || cachedReleasesFile.length() > MAX_RELEASE_RESPONSE_BYTES) null
        else cachedReleasesFile.readBytes()
    } catch (_: IOException) {
        null
    }

    private fun writeCacheAtomically(bytes: ByteArray) {
        if (!metadataDirectory.exists() && !metadataDirectory.mkdirs()) {
            throw IOException("Unable to create update metadata directory")
        }
        val temporary = File(metadataDirectory, "$RELEASE_CACHE_FILE_NAME.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    cachedReleasesFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    cachedReleasesFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private fun recordSuccess(now: Long) {
        preferences.edit()
            .putLong(KEY_NEXT_CHECK_AT, now + UpdateCheckSchedule.SUCCESS_INTERVAL_MS)
            .putLong(KEY_RETRY_AT, 0L)
            .putInt(KEY_FAILURE_COUNT, 0)
            .apply()
    }

    private fun recordTransientFailure(now: Long) {
        val failures = (preferences.getInt(KEY_FAILURE_COUNT, 0) + 1).coerceAtMost(6)
        preferences.edit()
            .putInt(KEY_FAILURE_COUNT, failures)
            .putLong(KEY_RETRY_AT, now + UpdateCheckSchedule.transientBackoffMs(failures))
            .apply()
    }

    private fun recordHttpFailure(error: ReleaseHttpException, now: Long) {
        val backoff = when (error.statusCode) {
            HttpURLConnection.HTTP_FORBIDDEN, 429 -> UpdateCheckSchedule.rateLimitBackoffMs(
                nowMillis = now,
                retryAfterSeconds = error.retryAfterSeconds,
                resetEpochSeconds = error.rateLimitResetEpochSeconds,
            )
            HttpURLConnection.HTTP_NOT_FOUND -> UpdateCheckSchedule.NOT_FOUND_BACKOFF_MS
            else -> UpdateCheckSchedule.transientBackoffMs(
                preferences.getInt(KEY_FAILURE_COUNT, 0) + 1,
            )
        }
        val failures = (preferences.getInt(KEY_FAILURE_COUNT, 0) + 1).coerceAtMost(6)
        preferences.edit()
            .putInt(KEY_FAILURE_COUNT, failures)
            .putLong(KEY_RETRY_AT, now + backoff)
            .apply()
    }

    private fun httpFailureMessage(status: Int): String = when (status) {
        HttpURLConnection.HTTP_FORBIDDEN, 429 -> "Update checks are temporarily rate limited."
        HttpURLConnection.HTTP_NOT_FOUND -> "No published update channel is available."
        else -> "Update service is temporarily unavailable."
    }

    private data class HttpResponse(
        val statusCode: Int,
        val body: ByteArray?,
        val etag: String?,
        val retryAfterSeconds: Long?,
        val rateLimitResetEpochSeconds: Long?,
    )

    companion object {
        const val RELEASE_FEED_URL =
            "https://leviknet.com/downloads/android/stable/latest.json"
        const val MANIFEST_ASSET_NAME = "update.json"
        const val SIGNATURE_ASSET_NAME = "update.json.sig"

        private const val RELEASE_HOST = "leviknet.com"
        private const val JSON_ACCEPT = "application/json"
        private const val USER_AGENT = "LevikVPN-Android-Direct"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val APK_READ_TIMEOUT_MS = 60_000
        private const val MAX_REDIRECTS = 4
        private const val MAX_RELEASE_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_ETAG_LENGTH = 512
        private const val PREFERENCES_NAME = "direct_update_checks"
        private const val METADATA_DIRECTORY_NAME = "update-metadata"
        private const val RELEASE_CACHE_FILE_NAME = "direct-release-feed.json"
        private const val KEY_ETAG = "github_etag"
        private const val KEY_NEXT_CHECK_AT = "next_check_at"
        private const val KEY_RETRY_AT = "retry_at"
        private const val KEY_FAILURE_COUNT = "failure_count"
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}
