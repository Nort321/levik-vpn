package com.leviknet.vpn.vpn

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal class AndroidRelayVkTurnProvider(
    private val context: Context?,
) : RelayVkTurnProvider {
    override fun obtain(hash: String): RelayTurnCredentials {
        val appContext = context ?: throw IllegalStateException("VK auth context unavailable")
        return RelayVkAuthCoordinator.obtain(appContext, hash)
    }
}

private data class PendingVkAuth(
    val hash: String,
    val latch: CountDownLatch = CountDownLatch(1),
    val completed: AtomicBoolean = AtomicBoolean(false),
    val credentials: AtomicReference<RelayTurnCredentials?> = AtomicReference(null),
    val failure: AtomicReference<Throwable?> = AtomicReference(null),
)

internal object RelayVkAuthCoordinator {
    private const val CHANNEL_ID = "relay_vk_auth"
    private const val NOTIFICATION_ID = 0x4c56
    private const val WAIT_SECONDS = 285L
    private val pending = AtomicReference<PendingVkAuth?>(null)

    fun obtain(context: Context, hash: String): RelayTurnCredentials {
        require(hash.matches(Regex("^[A-Za-z0-9_-]{16,256}$")))
        val request = PendingVkAuth(hash)
        check(pending.compareAndSet(null, request)) { "VK auth is already active" }
        try {
            showNotification(context)
            context.startActivity(
                Intent(context, RelayVkAuthActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
            if (!request.latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw IllegalStateException("VK auth timed out")
            }
            request.failure.get()?.let { throw it }
            return request.credentials.get()
                ?: throw IllegalStateException("VK auth returned no credentials")
        } finally {
            pending.compareAndSet(request, null)
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    fun currentHash(): String? = pending.get()?.hash

    fun complete(credentials: RelayTurnCredentials) {
        val request = pending.get() ?: return
        if (request.completed.compareAndSet(false, true)) {
            request.credentials.set(credentials)
            request.latch.countDown()
        }
    }

    fun cancel(message: String = "VK auth cancelled") {
        val request = pending.get() ?: return
        if (request.completed.compareAndSet(false, true)) {
            request.failure.set(IllegalStateException(message))
            request.latch.countDown()
        }
    }

    private fun showNotification(context: Context) {
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

internal class RelayVkAuthActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var completed = false
    private var joining = false
    private var joinAttempt = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack() && !joining) {
                    webView.goBack()
                } else {
                    RelayVkAuthCoordinator.cancel()
                    finish()
                }
            }
        })
        val hash = RelayVkAuthCoordinator.currentHash()
        if (hash == null) {
            finish()
            return
        }
        buildContent()
        configureWebView(hash)
        if (hasVkSession()) {
            loadJoin(hash)
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

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun configureWebView(hash: String) {
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
        webView.addJavascriptInterface(TurnBridge(), JS_BRIDGE)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !isAllowedNavigation(request.url)

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (joining) view.evaluateJavascript(INTERCEPTOR_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                if (!joining && hasVkSession()) {
                    loadJoin(hash)
                    return
                }
                if (joining) {
                    view.evaluateJavascript(INTERCEPTOR_JS, null)
                    view.evaluateJavascript(AUTO_JOIN_SETUP_JS, null)
                    scheduleAutoJoin()
                }
            }
        }
    }

    private fun loadJoin(hash: String) {
        joining = true
        joinAttempt = 0
        status.setText(R.string.relay_vk_joining)
        webView.loadUrl(JOIN_URLS.first().replace("{hash}", hash))
    }

    private fun scheduleAutoJoin() {
        handler.removeCallbacksAndMessages(null)
        handler.post(object : Runnable {
            override fun run() {
                if (completed || !joining) return
                webView.evaluateJavascript(AUTO_JOIN_TRY_JS, null)
                joinAttempt++
                if (joinAttempt < 90) handler.postDelayed(this, 500L)
            }
        })
    }

    private fun hasVkSession(): Boolean = VK_COOKIE_ORIGINS.any { origin ->
        CookieManager.getInstance().getCookie(origin)
            ?.split(';')
            ?.any { cookie -> cookie.trim().startsWith("remixsid=") && cookie.substringAfter('=').isNotBlank() } == true
    }

    private fun isAllowedNavigation(uri: Uri): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return ALLOWED_HOSTS.any { allowed -> host == allowed || host.endsWith(".$allowed") }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface(JS_BRIDGE)
            webView.stopLoading()
            webView.destroy()
        }
        if (isFinishing && !completed) RelayVkAuthCoordinator.cancel()
        super.onDestroy()
    }

    private inner class TurnBridge {
        @JavascriptInterface
        fun onTurnServer(raw: String) {
            val credentials = runCatching {
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
            }.getOrNull() ?: return
            runOnUiThread {
                if (completed) return@runOnUiThread
                completed = true
                RelayVkAuthCoordinator.complete(credentials)
                finish()
            }
        }
    }

    private companion object {
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
}
