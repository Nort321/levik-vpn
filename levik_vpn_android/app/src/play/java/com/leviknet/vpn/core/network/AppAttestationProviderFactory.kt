package com.leviknet.vpn.core.network

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

internal fun createAppAttestationProvider(
    context: Context,
    cloudProjectNumber: Long?,
): AppAttestationProvider = PlayIntegrityAttestationProvider(
    context = context,
    cloudProjectNumber = cloudProjectNumber,
)

private class PlayIntegrityAttestationProvider(
    context: Context,
    private val cloudProjectNumber: Long?,
) : AppAttestationProvider {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private val prepareMutex = Mutex()

    @Volatile
    private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    override suspend fun token(requestHash: String): AttestationResult {
        require(requestHash.length in 1..MAX_REQUEST_HASH_LENGTH) {
            "Invalid Play Integrity request hash"
        }
        if (cloudProjectNumber == null) {
            return AttestationResult.Unavailable()
        }

        val firstAttempt = try {
            return AttestationResult.Available(requestToken(requestHash))
        } catch (error: TimeoutCancellationException) {
            error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error
        }

        provider = null
        return try {
            AttestationResult.Available(requestToken(requestHash))
        } catch (error: TimeoutCancellationException) {
            error.addSuppressed(firstAttempt)
            AttestationResult.Unavailable(error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.addSuppressed(firstAttempt)
            AttestationResult.Unavailable(error)
        }
    }

    private suspend fun requestToken(requestHash: String): String =
        withTimeout(ATTESTATION_ATTEMPT_TIMEOUT_MS) {
            provider().request(
                StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                    .setRequestHash(requestHash)
                    .build(),
            ).await().token()
        }

    private suspend fun provider(): StandardIntegrityManager.StandardIntegrityTokenProvider {
        provider?.let { return it }
        return prepareMutex.withLock {
            provider?.let { return@withLock it }
            val projectNumber = requireNotNull(cloudProjectNumber)
            manager.prepareIntegrityToken(
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(projectNumber)
                    .build(),
            ).await().also { prepared ->
                provider = prepared
            }
        }
    }

    companion object {
        private const val MAX_REQUEST_HASH_LENGTH = 500
        private const val ATTESTATION_ATTEMPT_TIMEOUT_MS = 20_000L
    }
}
