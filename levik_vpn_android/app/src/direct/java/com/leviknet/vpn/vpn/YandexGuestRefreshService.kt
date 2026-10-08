package com.leviknet.vpn.vpn

import android.annotation.SuppressLint
import android.app.Service
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.leviknet.vpn.BuildConfig
import java.io.ByteArrayInputStream
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Bound, private guest process. One fresh anonymous request; no background activity or cookie export. */
internal class YandexGuestRefreshService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message -> handle(message); true })
    private var active: GuestRequest? = null
    private var webView: WebView? = null
    private var coordinatorOwner: Any? = null
    private var bindingAttempted = false

    override fun onBind(intent: Intent?): IBinder? {
        val processAllowed = Build.VERSION.SDK_INT >= 28 && Application.getProcessName() == "$packageName:yandex_guest"
        val actionAllowed = intent?.action == YandexGuestRefreshProtocol.ACTION
        val packageAllowed = intent?.component?.packageName == packageName
        val componentAllowed = intent?.component?.className == YandexGuestRefreshService::class.java.name
        diagnostic("binding process=$processAllowed action=$actionAllowed package=$packageAllowed component=$componentAllowed")
        return if (processAllowed && actionAllowed && packageAllowed && componentAllowed) messenger.binder else null
    }

    private fun handle(message: Message) {
        diagnostic("request received")
        if (message.sendingUid != Process.myUid() || message.arg1 <= 0 || message.arg2 != 0 || message.obj != null) return
        if (message.what == YandexGuestRefreshProtocol.CANCEL) {
            if (message.arg1 == active?.id && boundedYandexGuestBundle(message.data, emptySet())) finish(null)
            return
        }
        if (message.what != YandexGuestRefreshProtocol.REQUEST) return
        val reply = message.replyTo ?: return
        val document = runCatching {
            require(boundedYandexGuestBundle(message.data, setOf("documentUrl"), setOf("networkHandle")))
            require(message.data.getLong("networkHandle") > 0)
            YandexContract.validateDocumentUrl(message.data.getString("documentUrl").orEmpty())
        }.getOrNull()
        if (active != null || document == null) { reply(reply, message.arg1, null); return }
        val request = GuestRequest(message.arg1, reply, SystemClock.elapsedRealtime() + YandexGuestRefreshProtocol.SERVICE_TIMEOUT_MS)
        if (!YandexGuestProcessCoordinator.tryAcquire(request)) { reply(reply, message.arg1, null); return }
        coordinatorOwner = request
        active = request
        request.death = IBinder.DeathRecipient { main.post { if (active === request) finish(null) } }
        try { reply.binder.linkToDeath(request.death, 0) } catch (_: Throwable) { finish(null); return }
        main.postDelayed({ if (active === request) { diagnostic("request timed out"); finish(null) } }, YandexGuestRefreshProtocol.SERVICE_TIMEOUT_MS)
        val bound = runCatching {
            val manager = getSystemService(ConnectivityManager::class.java)
            val handle = message.data.getLong("networkHandle")
            val selected = manager.allNetworks.singleOrNull { it.networkHandle == handle } ?: return@runCatching false
            val capabilities = manager.getNetworkCapabilities(selected) ?: return@runCatching false
            require(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            bindingAttempted = true
            manager.bindProcessToNetwork(selected)
        }.getOrDefault(false)
        diagnostic("underlying network bound=$bound")
        if (!bound) { finish(null); return }
        startGuest(document, request)
    }

    @SuppressLint("SetJavaScriptEnabled") // Fixed DOM extraction only, isolated anonymous provider process.
    private fun startGuest(document: String, request: GuestRequest) {
        try {
            WebView.setWebContentsDebuggingEnabled(false)
            val view = WebView(this)
            webView = view
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                saveFormData = false
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            view.setDownloadListener { _, _, _, _, _ -> }
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean = true
                override fun onPermissionRequest(request: PermissionRequest) {
                    // Optional provider probes must not terminate anonymous admission.
                    // Match the visible guest flow: deny every permission and keep loading.
                    request.deny()
                    diagnostic("optional permission denied; guest loading continues")
                }
                override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    result.cancel(); diagnostic("script alert denied"); finish(null); return true
                }
                override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    result.cancel(); diagnostic("script confirmation denied"); finish(null); return true
                }
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val allowed = YandexGuestRefreshPolicy.navigation(request.url.toString(), request.isForMainFrame)
                    if (request.isForMainFrame) diagnostic("navigation allowed=$allowed")
                    if (!allowed && request.isForMainFrame) main.post { finish(null) }
                    return !allowed
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (YandexGuestRefreshPolicy.resource(request.url.toString())) null else WebResourceResponse(
                        "text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)),
                    )
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    val allowed = url != null && YandexGuestRefreshPolicy.navigation(url, true)
                    diagnostic("page started allowed=$allowed editor=${url?.let(YandexGuestRefreshPolicy::configOrigin) == true}")
                    if (!allowed) finish(null)
                }
                override fun onPageFinished(view: WebView, url: String?) { diagnostic("page finished"); capture(request) }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel(); diagnostic("TLS error denied"); finish(null)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) { diagnostic("load error code=${error.errorCode}"); finish(null) }
                }
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(view, false)
                removeAllCookies {
                    if (!current(request)) return@removeAllCookies
                    WebStorage.getInstance().deleteAllData()
                    view.clearCache(true)
                    view.loadUrl(document)
                    poll(request)
                }
            }
        } catch (_: Throwable) { diagnostic("guest startup failed"); finish(null) }
    }

    private fun poll(request: GuestRequest) {
        if (!current(request)) return
        capture(request)
        main.postDelayed({ poll(request) }, 1000)
    }

    private fun capture(request: GuestRequest) {
        if (!current(request) || request.capturing) return
        val view = webView ?: return
        val url = view.url ?: return
        if (!YandexGuestRefreshPolicy.configOrigin(url)) return
        request.capturing = true
        view.evaluateJavascript(
            "(function(){if(location.origin!=='https://docs.yandex.ru')return null;" +
                "var e=document.querySelector('script#client-config');" +
                "return e&&e.textContent.length<=131072?e.textContent:null;})()",
        ) { result ->
            request.capturing = false
            if (!current(request) || view.url != url) return@evaluateJavascript
            val auth = runCatching {
                require(result.length <= 262_144)
                val encoded = (Json.parseToJsonElement(result) as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: error("Missing guest configuration")
                parseYandexGuestConfig(encoded)
            }.getOrNull()
            if (auth != null) finish(auth)
        }
    }

    private fun current(request: GuestRequest): Boolean = active === request && !request.completed &&
        SystemClock.elapsedRealtime() < request.deadline

    private fun finish(auth: YandexProviderAuth?) {
        val request = active ?: return
        if (request.completed) return
        diagnostic("capture completed success=${auth != null}")
        request.completed = true
        main.removeCallbacksAndMessages(null)
        // Leave the WebView callback before destroying its native renderer.
        main.post {
            destroyWebView()
            // Keep the slot occupied until the shared guest cookie store is cleared.
            main.postDelayed({ complete(request, null, cookiesCleared = false) }, 2000)
            try { CookieManager.getInstance().removeAllCookies { complete(request, auth) } }
            catch (_: Throwable) { complete(request, null, cookiesCleared = false) }
            runCatching { WebStorage.getInstance().deleteAllData() }
        }
    }

    private fun complete(request: GuestRequest, auth: YandexProviderAuth?, cookiesCleared: Boolean = true) {
        if (active !== request) return
        main.removeCallbacksAndMessages(null)
        active = null
        runCatching { request.reply.binder.unlinkToDeath(request.death, 0) }
        val reset = resetNetworkBinding(releaseOwner = cookiesCleared)
        diagnostic("cleanup networkReset=$reset cookiesCleared=$cookiesCleared")
        reply(request.reply, request.id, if (reset) auth else null)
    }

    private fun diagnostic(phase: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("YandexGuestRefresh", phase)
    }

    private fun reply(target: Messenger, id: Int, auth: YandexProviderAuth?) {
        val encoded = auth?.let { runCatching {
            YandexContract.validateProviderAuth(it)
            Json.encodeToString(YandexProviderAuth.serializer(), it)
                .also { value -> require(value.toByteArray().size + 512 <= YandexGuestRefreshProtocol.MAX_BYTES) }
        }.getOrNull() }
        val result = Bundle().apply { if (encoded != null) putString("providerAuth", encoded) }
        val success = encoded != null && boundedYandexGuestBundle(result, setOf("providerAuth"))
        runCatching { target.send(Message.obtain(null,
            if (success) YandexGuestRefreshProtocol.SUCCESS else YandexGuestRefreshProtocol.INTERACTION_REQUIRED, id, 0,
        ).apply { data = if (success) result else Bundle() }) }
    }

    private fun destroyWebView() {
        val view = webView
        webView = null
        diagnostic("renderer cleanup started")
        runCatching { view?.stopLoading() }
        diagnostic("renderer loading stopped")
        runCatching { view?.clearHistory(); view?.clearCache(true) }
        diagnostic("renderer cache cleared")
        runCatching { view?.destroy() }
        diagnostic("renderer destroyed")
    }

    private fun resetNetworkBinding(releaseOwner: Boolean = true): Boolean {
        val owner = coordinatorOwner ?: return true
        val reset = !bindingAttempted || runCatching {
            getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null)
        }.getOrDefault(false)
        if (reset) {
            bindingAttempted = false
            if (releaseOwner) {
                coordinatorOwner = null
                YandexGuestProcessCoordinator.release(owner)
            }
        }
        return reset
    }

    override fun onUnbind(intent: Intent?): Boolean { finish(null); return false }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        active?.let { runCatching { it.reply.binder.unlinkToDeath(it.death, 0) } }
        active = null
        destroyWebView()
        if (coordinatorOwner != null) {
            // Do not erase a later activity's guest store after normal completion released our slot.
            resetNetworkBinding(releaseOwner = false)
            runCatching {
                CookieManager.getInstance().removeAllCookies { resetNetworkBinding() }
                WebStorage.getInstance().deleteAllData()
            }
        }
        super.onDestroy()
    }

    private class GuestRequest(val id: Int, val reply: Messenger, val deadline: Long) {
        lateinit var death: IBinder.DeathRecipient
        var completed = false
        var capturing = false
    }
}

/** Shared with the visible guest activity; release only after cookies and process binding are cleared. */
internal object YandexGuestProcessCoordinator {
    private var owner: Any? = null
    @Synchronized fun tryAcquire(candidate: Any): Boolean {
        if (owner != null) return false
        owner = candidate
        return true
    }
    @Synchronized fun release(candidate: Any) { if (owner === candidate) owner = null }
}

/** No account/login or interactive challenge. Provider fast admission has the job's fixed deadline. */
internal object YandexGuestRefreshPolicy {
    private val onlyOfficeHost = Regex("(?:[a-z0-9_-]{1,63}\\.)?onlyoffice\\.disk\\.yandex\\.net")
    private fun uri(value: String): URI? = runCatching {
        require(value.length in 1..4096)
        URI(value).also {
            require(it.scheme == "https" && it.rawFragment == null && it.rawAuthority != null)
            require(!it.rawPath.orEmpty().let { path ->
                !isYandexFastGuestChallengePath(path) &&
                    (path.startsWith("/showcaptcha") || path.startsWith("/checkcaptcha") || path.startsWith("/captcha"))
            })
        }
    }.getOrNull()
    fun resource(value: String): Boolean {
        val endpoint = uri(value) ?: return false
        val host = endpoint.rawAuthority ?: return false
        return host in setOf("disk.yandex.ru", "docs.yandex.ru", "yastatic.net") || host.matches(onlyOfficeHost)
    }
    fun navigation(value: String, mainFrame: Boolean): Boolean {
        if (!resource(value)) return false
        if (!mainFrame) return true
        val endpoint = uri(value) ?: return false
        return when (endpoint.rawAuthority) {
            "disk.yandex.ru" -> runCatching { YandexContract.validateDocumentUrl(value) }.isSuccess
            "docs.yandex.ru" -> isYandexGuestEditorPath(endpoint.rawPath.orEmpty()) ||
                isYandexFastGuestChallengePath(endpoint.rawPath.orEmpty())
            else -> false
        }
    }
    fun configOrigin(value: String): Boolean {
        val endpoint = uri(value) ?: return false
        return navigation(value, true) && endpoint.rawAuthority == "docs.yandex.ru" &&
            isYandexGuestEditorPath(endpoint.rawPath.orEmpty())
    }
}
