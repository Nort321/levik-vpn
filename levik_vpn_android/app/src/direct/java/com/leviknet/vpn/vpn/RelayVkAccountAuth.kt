package com.leviknet.vpn.vpn

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.leviknet.vpn.R
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

internal class AndroidRelayVkTurnProvider(
    private val context: Context?,
) : RelayVkTurnProvider {
    override fun obtain(hash: String, network: Network, deadlineElapsedMs: Long): RelayTurnCredentials {
        val appContext = context ?: throw IllegalStateException("VK auth context unavailable")
        return RelayVkAuthCoordinator.obtain(appContext, hash, network, deadlineElapsedMs)
    }
}

private data class CachedVkAuth(
    val hash: String,
    val networkHandle: Long,
    val credentials: RelayTurnCredentials,
    val expiresAtElapsedMs: Long,
)

internal object RelayVkAuthCoordinator {
    private const val CHANNEL_ID = "relay_vk_auth"
    private const val NOTIFICATION_ID = 0x4c56
    const val REQUEST_ID_EXTRA = "com.leviknet.vpn.RELAY_VK_AUTH_REQUEST"
    private const val CACHE_LIFETIME_MS = 2 * 60_000L
    private val pending = AtomicReference<RelayVkAuthAttempt?>(null)
    private val headless = AtomicReference<RelayVkAuthAttempt?>(null)
    private val cached = AtomicReference<CachedVkAuth?>(null)

    fun obtain(context: Context, hash: String, network: Network, deadlineElapsedMs: Long): RelayTurnCredentials {
        require(hash.matches(Regex("^[A-Za-z0-9_-]{16,256}$")))
        cached.get()?.takeIf {
            it.hash == hash && it.networkHandle == network.networkHandle &&
                SystemClock.elapsedRealtime() < it.expiresAtElapsedMs
        }?.let { return it.credentials }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: throw RelayVkAuthException("relay_vk_auth_network_unavailable")
        val request = RelayVkAuthAttempt(hash)
        check(pending.compareAndSet(null, request)) { "VK auth is already active" }
        val startedAt = SystemClock.elapsedRealtime()
        var previousNetwork: Network? = null
        var networkBound = false
        try {
            previousNetwork = connectivity.boundNetworkForProcess
            networkBound = connectivity.bindProcessToNetwork(network)
            if (!networkBound) {
                throw RelayVkAuthException("relay_vk_auth_network_unavailable")
            }
            // Silent recovery and interactive login share the native request's single budget.
            if (hasStoredVkSession()) {
                obtainHeadless(context, hash, minOf(
                    RelayVkAuthTiming.HEADLESS_TIMEOUT_MS,
                    RelayVkAuthTiming.remaining(startedAt, SystemClock.elapsedRealtime(), deadlineElapsedMs),
                ))?.let { credentials ->
                    if (request.isCompleted) throw RelayVkAuthException("relay_vk_auth_cancelled")
                    cached.set(CachedVkAuth(hash, network.networkHandle, credentials,
                        SystemClock.elapsedRealtime() + CACHE_LIFETIME_MS))
                    return credentials
                }
            }
            if (request.isCompleted) throw RelayVkAuthException("relay_vk_auth_cancelled")
            if (RelayVkAuthTiming.remaining(startedAt, SystemClock.elapsedRealtime(), deadlineElapsedMs) == 0L) {
                throw RelayVkAuthException("relay_vk_auth_timeout")
            }
            showNotification(context, request.id)
            context.startActivity(
                Intent(context, RelayVkAuthActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(REQUEST_ID_EXTRA, request.id)
                },
            )
            return when (val result = request.await(
                RelayVkAuthTiming.remaining(startedAt, SystemClock.elapsedRealtime(), deadlineElapsedMs),
            )) {
                is RelayVkAuthResult.Success -> result.credentials.also { credentials ->
                    cached.set(CachedVkAuth(hash, network.networkHandle, credentials,
                        SystemClock.elapsedRealtime() + CACHE_LIFETIME_MS))
                }
                is RelayVkAuthResult.Failure -> throw result.error
                null -> throw RelayVkAuthException("relay_vk_auth_timeout")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("VK authentication interrupted")
        } finally {
            request.cancel(RelayVkAuthException("relay_vk_auth_cancelled"))
            try {
                if (networkBound && !connectivity.bindProcessToNetwork(previousNetwork)) {
                    connectivity.bindProcessToNetwork(null)
                }
                context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            } finally {
                // Keep ownership until process-wide network and notification cleanup is
                // complete, so an old request cannot reset a new request's binding.
                pending.compareAndSet(request, null)
            }
        }
    }

    fun currentAttempt(requestId: String?): RelayVkAuthAttempt? =
        pending.get()?.takeIf { it.id == requestId && !it.isCompleted }

    fun cancel() {
        val failure = RelayVkAuthException("relay_vk_auth_cancelled")
        headless.get()?.cancel(failure)
        pending.get()?.cancel(failure)
    }

    /** Runs on the caller's background thread; the WebView lives on the main thread. */
    private fun obtainHeadless(context: Context, hash: String, timeoutMs: Long): RelayTurnCredentials? {
        val attempt = RelayVkAuthAttempt(hash)
        headless.set(attempt)
        val main = Handler(Looper.getMainLooper())
        val page = AtomicReference<HeadlessVkCallPage?>(null)
        try {
            main.post {
                if (attempt.isCompleted) return@post
                page.set(
                    HeadlessVkCallPage(context.applicationContext, hash) { result ->
                        if (result == null) attempt.cancel(RelayVkAuthException("relay_vk_auth_failed"))
                        else attempt.complete(result)
                    }.also { it.start() },
                )
            }
            return (attempt.await(timeoutMs) as? RelayVkAuthResult.Success)
                ?.credentials
        } finally {
            attempt.cancel(RelayVkAuthException("relay_vk_auth_cancelled"))
            headless.compareAndSet(attempt, null)
            main.post { page.getAndSet(null)?.destroy() }
        }
    }

    private fun showNotification(context: Context, requestId: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.relay_vk_auth_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val intent = Intent(context, RelayVkAuthActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(REQUEST_ID_EXTRA, requestId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(context.getString(R.string.relay_vk_auth_title))
                .setContentText(context.getString(R.string.relay_vk_auth_notification))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }
}

/**
 * The VK call page without a window. It reuses the activity's cookies, so it
 * works only with a stored login; a login page ends it with no credentials.
 */
private class HeadlessVkCallPage(
    private val context: Context,
    private val hash: String,
    private val onResult: (RelayTurnCredentials?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val completionHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var finished = false

    fun start() {
        val view = WebView(context)
        webView = view
        configureVkCallWebView(view, onTurnServer = { credentials -> completionHandler.post { finish(credentials) } })
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isVkLoginPage(request.url.toString())) {
                    finish(null)
                    return true
                }
                return !isAllowedVkNavigation(request.url)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                view.evaluateJavascript(VkCallPage.INTERCEPTOR_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (isVkLoginPage(url)) {
                    finish(null)
                    return
                }
                view.evaluateJavascript(VkCallPage.INTERCEPTOR_JS, null)
                view.evaluateJavascript(VkCallPage.AUTO_JOIN_SETUP_JS, null)
                scheduleAutoJoin(view)
            }
        }
        // Laid out at a phone size: some pages skip work in a zero-sized viewport.
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(HEADLESS_WIDTH, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(HEADLESS_HEIGHT, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, HEADLESS_WIDTH, HEADLESS_HEIGHT)
        view.onResume()
        view.resumeTimers()
        view.loadUrl(VkCallPage.JOIN_URLS.first().replace("{hash}", hash))
    }

    private fun scheduleAutoJoin(view: WebView) {
        handler.removeCallbacksAndMessages(null)
        var attempt = 0
        handler.post(object : Runnable {
            override fun run() {
                if (finished) return
                view.evaluateJavascript(VkCallPage.AUTO_JOIN_TRY_JS, null)
                if (++attempt < 90) handler.postDelayed(this, 500L)
            }
        })
    }

    private fun finish(credentials: RelayTurnCredentials?) {
        if (finished) return
        finished = true
        onResult(credentials)
    }

    fun destroy() {
        finished = true
        handler.removeCallbacksAndMessages(null)
        completionHandler.removeCallbacksAndMessages(null)
        webView?.apply {
            removeJavascriptInterface(VkCallPage.JS_BRIDGE)
            stopLoading()
            destroy()
        }
        webView = null
    }

    private companion object {
        const val HEADLESS_WIDTH = 1080
        const val HEADLESS_HEIGHT = 1920
    }
}

internal fun hasStoredVkSession(): Boolean = VkCallPage.VK_COOKIE_ORIGINS.any { origin ->
    CookieManager.getInstance().getCookie(origin)
        ?.split(';')
        ?.any { cookie -> cookie.trim().startsWith("remixsid=") && cookie.substringAfter('=').isNotBlank() } == true
}

private fun isAllowedVkNavigation(uri: Uri): Boolean {
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    return VkCallPage.ALLOWED_HOSTS.any { allowed -> host == allowed || host.endsWith(".$allowed") }
}

/** A VK page that asks for a login: a stored session has expired. */
internal fun isVkLoginPage(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    val path = uri.path.orEmpty().lowercase()
    return host.startsWith("id.vk.") || host.startsWith("login.vk.") ||
        path == "/login" || path.startsWith("/login/") || path.startsWith("/join/login")
}

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
private fun configureVkCallWebView(webView: WebView, onTurnServer: (RelayTurnCredentials) -> Unit) {
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
        userAgentString = userAgentString.replace("; wv", "")
    }
    webView.addJavascriptInterface(
        object {
            @JavascriptInterface
            fun onTurnServer(raw: String) {
                parseTurnServer(raw)?.let(onTurnServer)
            }
        },
        VkCallPage.JS_BRIDGE,
    )
}

private fun parseTurnServer(raw: String): RelayTurnCredentials? = runCatching {
    require(raw.length <= 32_768)
    val json = JSONObject(raw)
    val username = json.getString("username")
    val password = json.getString("credential")
    val values = json.getJSONArray("urls")
    val urls = buildList {
        for (index in 0 until values.length()) {
            val value = values.getString(index).trim()
            require(value.startsWith("turn:") || value.startsWith("turns:"))
            add(value)
        }
    }
    RelayTurnCredentials(username, password, urls)
}.getOrNull()

internal class RelayVkAuthActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var completed = false
    private var joining = false
    private var joinAttempt = 0
    private var attempt: RelayVkAuthAttempt? = null
    private var completionObserver: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack() && !joining) {
                    webView.goBack()
                } else {
                    attempt?.cancel(RelayVkAuthException("relay_vk_auth_cancelled"))
                    finish()
                }
            }
        })
        startAttempt(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val requestId = intent.getStringExtra(RelayVkAuthCoordinator.REQUEST_ID_EXTRA)
        if (attempt?.id == requestId || RelayVkAuthCoordinator.currentAttempt(requestId) == null) return
        releaseWebView()
        attempt?.cancel(RelayVkAuthException("relay_vk_auth_cancelled"))
        setIntent(intent)
        startAttempt(intent)
    }

    private fun startAttempt(intent: Intent) {
        val current = RelayVkAuthCoordinator.currentAttempt(
            intent.getStringExtra(RelayVkAuthCoordinator.REQUEST_ID_EXTRA),
        ) ?: run { finish(); return }
        attempt = current
        completed = false
        joining = false
        joinAttempt = 0
        buildContent()
        configureWebView(current)
        val observer: () -> Unit = {
            // Auto-join clears its own Handler queue; completion must survive that cleanup.
            runOnUiThread { if (attempt === current) finish() }
            Unit
        }
        completionObserver = observer
        current.observeCompletion(observer)
        if (hasStoredVkSession()) {
            loadJoin(current.hash)
        } else {
            status.setText(R.string.relay_vk_login_prompt)
            webView.loadUrl("https://m.vk.com/")
        }
    }

    private fun buildContent() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        status = TextView(this).apply {
            setTextColor(Color.BLACK)
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 24, 32, 24)
        }
        val progress = ProgressBar(this).apply { isIndeterminate = true }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(progress, LinearLayout.LayoutParams(72, 72))
            addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        webView = WebView(this)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun configureWebView(current: RelayVkAuthAttempt) {
        configureVkCallWebView(webView, onTurnServer = { credentials ->
            runOnUiThread {
                if (completed || attempt !== current || current.isCompleted) return@runOnUiThread
                completed = current.complete(credentials)
                if (completed) finish()
            }
        })
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !isAllowedVkNavigation(request.url)

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (attempt !== current || current.isCompleted) return
                if (joining) view.evaluateJavascript(VkCallPage.INTERCEPTOR_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (attempt !== current || current.isCompleted) return
                CookieManager.getInstance().flush()
                if (isVkLoginPage(url)) {
                    // A stale cookie can redirect a call back to login. Let the user sign in
                    // instead of spending the whole timeout auto-clicking the login page.
                    joining = false
                    handler.removeCallbacksAndMessages(null)
                    status.setText(R.string.relay_vk_login_prompt)
                    return
                }
                if (!joining && hasStoredVkSession()) {
                    loadJoin(current.hash)
                    return
                }
                if (joining) {
                    view.evaluateJavascript(VkCallPage.INTERCEPTOR_JS, null)
                    view.evaluateJavascript(VkCallPage.AUTO_JOIN_SETUP_JS, null)
                    scheduleAutoJoin()
                }
            }
        }
    }

    private fun loadJoin(hash: String) {
        joining = true
        joinAttempt = 0
        status.setText(R.string.relay_vk_joining)
        webView.loadUrl(VkCallPage.JOIN_URLS.first().replace("{hash}", hash))
    }

    private fun scheduleAutoJoin() {
        handler.removeCallbacksAndMessages(null)
        handler.post(object : Runnable {
            override fun run() {
                if (completed || !joining) return
                webView.evaluateJavascript(VkCallPage.AUTO_JOIN_TRY_JS, null)
                joinAttempt++
                if (joinAttempt < 90) handler.postDelayed(this, 500L)
            }
        })
    }

    private fun releaseWebView() {
        completionObserver?.let { observer -> attempt?.removeObserver(observer) }
        completionObserver = null
        handler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface(VkCallPage.JS_BRIDGE)
            webView.stopLoading()
            webView.destroy()
        }
    }

    override fun onDestroy() {
        releaseWebView()
        if (isFinishing && !completed) {
            attempt?.cancel(RelayVkAuthException("relay_vk_auth_cancelled"))
        }
        super.onDestroy()
    }

}

/** The VK call join page and the scripts that pick TURN credentials out of it. */
private object VkCallPage {
    const val JS_BRIDGE = "LevikVkAuth"
    val VK_COOKIE_ORIGINS = listOf("https://vk.com", "https://m.vk.com", "https://vk.ru", "https://m.vk.ru")
    val ALLOWED_HOSTS = setOf("vk.com", "vk.ru", "vk.me", "vkuseraudio.net", "okcdn.ru")
    val JOIN_URLS = listOf("https://m.vk.com/call/join/{hash}")
    const val INTERCEPTOR_JS = """
        (function() {
          if (window.__levikTurnHook) return;
          window.__levikTurnHook = true;
          function emit(data) {
            var ts = data && (data.turn_server || (data.response && data.response.turn_server));
            if (ts && ts.username && ts.credential && ts.urls) {
              window.LevikVkAuth.onTurnServer(JSON.stringify(ts));
            }
          }
          var originalFetch = window.fetch;
          if (originalFetch) window.fetch = async function() {
            var response = await originalFetch.apply(this, arguments);
            try { emit(JSON.parse(await response.clone().text())); } catch (_) {}
            return response;
          };
          var originalOpen = XMLHttpRequest.prototype.open;
          var originalSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function() { return originalOpen.apply(this, arguments); };
          XMLHttpRequest.prototype.send = function() {
            this.addEventListener('load', function() {
              try { emit(JSON.parse(this.responseText)); } catch (_) {}
            });
            return originalSend.apply(this, arguments);
          };
        })();
    """
    const val AUTO_JOIN_SETUP_JS = """
        (function() {
          window.__levikAutoJoin = function() {
            var nodes = document.querySelectorAll('button,a,[role="button"],input[type="button"],input[type="submit"]');
            for (var i = 0; i < nodes.length; i++) {
              var text = (nodes[i].innerText || nodes[i].textContent || nodes[i].value || '').trim().toLowerCase();
              if (text === 'присоединиться' || text === 'join' || text === 'продолжить в браузере' ||
                  text.indexOf('присоединиться к звонку') !== -1 || text.indexOf('continue in browser') !== -1) {
                try { nodes[i].click(); } catch (_) {}
                return true;
              }
            }
            return false;
          };
        })();
    """
    const val AUTO_JOIN_TRY_JS = "(function(){return window.__levikAutoJoin ? window.__levikAutoJoin() : false;})();"
}
