package com.leviknet.vpn.guard

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.leviknet.vpn.LevikVpnApplication
import com.leviknet.vpn.vpn.VpnStateStore
import java.time.LocalDate

/** Local, read-only companion bridge. Only the installed Guard package UID is accepted. */
class GuardStatusProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_STATUS || !isGuardCaller()) return null
        val app = context?.applicationContext as? LevikVpnApplication ?: return null
        if (!GuardBridgeAccess.isEnabled(app)) return null
        val snapshot = VpnStateStore.state.value
        val today = LocalDate.now().toString()
        val usage = app.container.trafficHistoryStore.history.value.firstOrNull { it.date == today }
        return Bundle().apply {
            putInt("api_version", 1)
            putString("state", snapshot.state.name)
            putLong("session_rx_bytes", snapshot.downloadedBytes)
            putLong("session_tx_bytes", snapshot.uploadedBytes)
            putLong("today_rx_bytes", usage?.downloadedBytes ?: 0L)
            putLong("today_tx_bytes", usage?.uploadedBytes ?: 0L)
        }
    }

    private fun isGuardCaller(): Boolean {
        val callerUid = Binder.getCallingUid()
        val packages = context?.packageManager?.getPackagesForUid(callerUid).orEmpty()
        return packages.any { it == "com.leviknet.guard" }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val METHOD_STATUS = "status"
    }
}
