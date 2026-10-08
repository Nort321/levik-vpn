package com.leviknet.vpn.vpn

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.leviknet.vpn.R
import com.leviknet.vpn.BuildConfig
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Private guest process and separate WebView storage; no JavaScript interface or cookie export. */
internal class YandexGuestActivity : Activity() {
    private var webView: WebView? = null
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var completed = false
    private var documentUrl = ""
    private var ownsGuestProcess = false
    private var networkBound = false
    private val poll = object : Runnable {
        override fun run() {
            capture()
            if (!completed) handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled") // Required by the provider editor in an isolated guest process.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        documentUrl = runCatching {
            YandexContract.validateDocumentUrl(intent.getStringExtra("documentUrl").orEmpty())
        }.getOrElse { finish(); return }
        if (!YandexGuestProcessCoordinator.tryAcquire(this)) { finish(); return }
        ownsGuestProcess = true
        val manager = getSystemService(ConnectivityManager::class.java)
        val handle = intent.getLongExtra("networkHandle", 0)
        val selected = manager.allNetworks.singleOrNull { it.networkHandle == handle }
        val caps = selected?.let(manager::getNetworkCapabilities)
        if (selected == null || caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) != true ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ) { finish(); return }
        networkBound = manager.bindProcessToNetwork(selected)
        if (!networkBound) { finish(); return }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { setText(R.string.yandex_guest_loading); setPadding(24, 16, 24, 16) }
        layout.addView(status)
        layout.addView(Button(this).apply { setText(R.string.cancel); setOnClickListener { finish() } })
        val view = WebView(this)
        WebView.setWebContentsDebuggingEnabled(false)
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
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                result?.cancel(); return true
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val allowed = allowedNavigation(request.url, request.isForMainFrame)
                if (request.isForMainFrame) diagnostic("navigation allowed=$allowed origin=${origin(request.url)} route=${route(request.url)}")
                return !allowed
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (allowedResource(request.url)) null else WebResourceResponse(
                    "text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)),
                )

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                status.setText(R.string.yandex_guest_loading)
                diagnostic("page-start origin=${origin(Uri.parse(url.orEmpty()))}")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                diagnostic("page-finish origin=${origin(Uri.parse(url.orEmpty()))}")
                capture()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    status.setText(R.string.yandex_guest_failed)
                    diagnostic("load-error code=${error.errorCode} origin=${origin(request.url)}")
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) {
                    status.setText(R.string.yandex_guest_failed)
                    diagnostic("http-error status=${errorResponse.statusCode} origin=${origin(request.url)}")
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                status.setText(R.string.yandex_guest_failed)
            }
        }
        layout.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, false)
            removeAllCookies {
                if (!isFinishing) {
                    WebStorage.getInstance().deleteAllData()
                    view.loadUrl(documentUrl)
                    handler.postDelayed(poll, 1000)
                }
            }
        }
    }

    private fun origin(uri: Uri): String = when (uri.host) {
        "disk.yandex.ru" -> "disk"
        "docs.yandex.ru" -> "docs"
        "yandex.ru", "smartcaptcha.yandexcloud.net" -> "challenge"
        else -> "other"
    }

    private fun diagnostic(phase: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("YandexGuest", phase)
    }

    private fun route(uri: Uri): String = when {
        uri.path.orEmpty().contains("captchafast") -> "fast-challenge"
        uri.path.orEmpty().contains("captcha") -> "challenge"
        uri.path == "/docs" -> "editor-root"
        uri.path.orEmpty().startsWith("/docs/") -> "editor"
        uri.path.orEmpty().startsWith("/edit/d/") -> "editor-direct"
        uri.path.orEmpty().startsWith("/i/") -> "public"
        else -> "other"
    }

    private fun capture() {
        if (completed || isFinishing) return
        val view = webView ?: return
        val uri = runCatching { Uri.parse(view.url) }.getOrNull() ?: return
        if (uri.scheme != "https" || uri.host != "docs.yandex.ru" ||
            uri.userInfo != null || uri.port !in setOf(-1, 443)
        ) return
        view.evaluateJavascript(
            "(function(){if(location.origin!=='https://docs.yandex.ru')return null;" +
                "var e=document.querySelector('script#client-config');" +
                "return e&&e.textContent.length<=131072?e.textContent:null;})()",
        ) { result ->
            if (completed || isFinishing || view.url != uri.toString()) return@evaluateJavascript
            val auth = runCatching {
                require(result.length <= 262_144)
                val config = (Json.parseToJsonElement(result) as? JsonPrimitive)?.content
                    ?: error("Missing configuration")
                parseYandexGuestConfig(config)
            }.getOrNull()
            if (auth != null) {
                completed = true
                setResult(RESULT_OK, Intent().putExtra("documentUrl", documentUrl)
                    .putExtra("providerAuth", Json.encodeToString(YandexProviderAuth.serializer(), auth)))
                finish()
            } else {
                status.setText(R.string.yandex_guest_instructions)
            }
        }
    }

    private fun allowedNavigation(uri: Uri, mainFrame: Boolean): Boolean {
        if (!allowedResource(uri)) return false
        if (!mainFrame) return true
        return when (uri.host) {
            "disk.yandex.ru" -> uri.path?.startsWith("/i/") == true
            "docs.yandex.ru" -> isYandexGuestEditorPath(uri.path.orEmpty()) ||
                isYandexFastGuestChallengePath(uri.path.orEmpty()) ||
                uri.path in setOf("/showcaptcha", "/checkcaptcha")
            "yandex.ru" -> uri.path?.let {
                it.startsWith("/showcaptcha") || it.startsWith("/checkcaptcha") || it.startsWith("/captcha")
            } == true
            else -> false
        }
    }

    private fun allowedResource(uri: Uri): Boolean {
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in setOf(-1, 443)) return false
        val host = uri.host ?: return false
        // Provider assets and CAPTCHA only; account login, local files and custom schemes stay blocked.
        if (host == "passport.yandex.ru" || host.startsWith("passport.")) return false
        return host == "yandex.ru" || host.endsWith(".yandex.ru") ||
            host == "yandex.net" || host.endsWith(".yandex.net") ||
            host == "yastatic.net" || host.endsWith(".yastatic.net") ||
            host == "smartcaptcha.yandexcloud.net"
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.let { view ->
            view.stopLoading()
            view.clearHistory()
            view.clearCache(true)
            view.destroy()
        }
        webView = null
        if (ownsGuestProcess) {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies {
                val reset = !networkBound || managerResetNetwork()
                if (reset) {
                    networkBound = false
                    ownsGuestProcess = false
                    YandexGuestProcessCoordinator.release(this)
                }
            }
        }
        super.onDestroy()
    }

    private fun managerResetNetwork(): Boolean = runCatching {
        getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null)
    }.getOrDefault(false)
}
