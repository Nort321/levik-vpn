package com.leviknet.vpn.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import android.os.Process
import com.leviknet.vpn.BuildConfig
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

internal object YandexGuestRefreshProtocol {
    const val ACTION = "com.leviknet.vpn.YANDEX_GUEST_REFRESH"
    const val REQUEST = 1
    const val SUCCESS = 2
    const val INTERACTION_REQUIRED = 3
    const val CANCEL = 4
    const val MAX_BYTES = 16_384
    const val SERVICE_TIMEOUT_MS = 25_000L
    const val CLIENT_TIMEOUT_MS = 28_000L
}

private val guestRefreshInFlight = AtomicBoolean(false)

/** Anonymous refresh only. A challenge or unsupported editor requires the visible guest flow. */
internal suspend fun refreshYandexGuest(context: Context, documentUrl: String, network: Network): YandexProviderAuth? {
    if (Build.VERSION.SDK_INT < 28 ||
        network.networkHandle <= 0 ||
        runCatching { YandexContract.validateDocumentUrl(documentUrl) }.isFailure ||
        !guestRefreshInFlight.compareAndSet(false, true)
    ) return null
    return try {
        withTimeoutOrNull(YandexGuestRefreshProtocol.CLIENT_TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    val app = context.applicationContext
                    val component = ComponentName(app, YandexGuestRefreshService::class.java)
                    val requestId = SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1
                    val main = Handler(Looper.getMainLooper())
                    val completed = AtomicBoolean(false)
                    var bound = false
                    var remote: Messenger? = null
                    lateinit var connection: ServiceConnection
                    fun finish(auth: YandexProviderAuth?) {
                        if (!completed.compareAndSet(false, true)) return
                        if (auth == null) runCatching {
                            remote?.send(Message.obtain(null, YandexGuestRefreshProtocol.CANCEL, requestId, 0))
                        }
                        if (bound) { bound = false; runCatching { app.unbindService(connection) } }
                        remote = null
                        if (continuation.isActive) continuation.resume(auth)
                    }
                    val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                        if (message.sendingUid != Process.myUid() || message.arg1 != requestId ||
                            message.arg2 != 0 || message.obj != null || message.replyTo != null
                        ) {
                            finish(null)
                        } else {
                            val auth = runCatching {
                                when (message.what) {
                                    YandexGuestRefreshProtocol.SUCCESS -> {
                                        val data = message.data
                                        require(boundedYandexGuestBundle(data, setOf("providerAuth")))
                                        val encoded = data.getString("providerAuth") ?: error("Missing guest response")
                                        require(encoded.toByteArray().size <= YandexGuestRefreshProtocol.MAX_BYTES)
                                        Json.decodeFromString(YandexProviderAuth.serializer(), encoded)
                                            .also { YandexContract.validateProviderAuth(it) }
                                    }
                                    YandexGuestRefreshProtocol.INTERACTION_REQUIRED -> {
                                        require(boundedYandexGuestBundle(message.data, emptySet()))
                                        null
                                    }
                                    else -> error("Invalid guest response")
                                }
                            }.getOrNull()
                            finish(auth)
                        }
                        true
                    })
                    connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, service: IBinder) {
                            if (completed.get()) return
                            if (name != component) { finish(null); return }
                            remote = Messenger(service)
                            runCatching {
                                remote?.send(Message.obtain(null, YandexGuestRefreshProtocol.REQUEST, requestId, 0).apply {
                                    replyTo = reply
                                    data = Bundle().apply {
                                        putString("documentUrl", documentUrl)
                                        putLong("networkHandle", network.networkHandle)
                                    }
                                })
                            }.onFailure { finish(null) }
                        }
                        override fun onServiceDisconnected(name: ComponentName) { finish(null) }
                        override fun onBindingDied(name: ComponentName) { finish(null) }
                        override fun onNullBinding(name: ComponentName) {
                            if (BuildConfig.DEBUG) android.util.Log.d("YandexGuestRefresh", "service binding rejected")
                            finish(null)
                        }
                    }
                    continuation.invokeOnCancellation { main.post { finish(null) } }
                    try {
                        bound = app.bindService(Intent(YandexGuestRefreshProtocol.ACTION).setComponent(component), connection, Context.BIND_AUTO_CREATE)
                        if (!bound) finish(null)
                    } catch (_: Throwable) { finish(null) }
                }
            }
        }
    } finally {
        guestRefreshInFlight.set(false)
    }
}

internal fun boundedYandexGuestBundle(bundle: Bundle, keys: Set<String>, longKeys: Set<String> = emptySet()): Boolean = runCatching {
    require(bundle.keySet() == keys + longKeys)
    require(keys.all { bundle.get(it) is String })
    require(longKeys.all { bundle.get(it) is Long })
    val parcel = Parcel.obtain()
    try { parcel.writeBundle(bundle); parcel.dataSize() + 512 <= YandexGuestRefreshProtocol.MAX_BYTES }
    finally { parcel.recycle() }
}.getOrDefault(false)
