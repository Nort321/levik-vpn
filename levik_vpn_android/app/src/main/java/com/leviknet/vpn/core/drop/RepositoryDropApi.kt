package com.leviknet.vpn.core.drop

import com.leviknet.vpn.core.network.ApiException
import com.leviknet.vpn.data.AppRepository
import kotlinx.serialization.json.JsonObject

/** LevikDrop's website requests, signed like every other app request. */
class RepositoryDropApi(private val repository: AppRepository) : DropApi {
    override suspend fun presence(key: String) = repository.dropPresence(key)

    override suspend fun clearPresence() = repository.clearDropPresence()

    override suspend fun devices(): JsonObject = repository.dropDevices()

    override suspend fun transfers(): JsonObject = repository.dropTransfers()

    override suspend fun create(receiverDeviceId: String, fileCount: Int, totalBytes: Long): JsonObject =
        repository.createDropTransfer(receiverDeviceId, fileCount, totalBytes)

    override suspend fun update(id: String, action: String, reason: String?): JsonObject =
        repository.updateDropTransfer(id, action, reason)

    companion object {
        /** The website's error code ("quota_exceeded", "http_404"…), "unauthorized", or null for a network failure. */
        fun errorCode(error: Throwable): String? = when (error) {
            is ApiException.Rejected -> error.code
            is ApiException.Unauthorized -> "unauthorized"
            else -> null
        }
    }
}
