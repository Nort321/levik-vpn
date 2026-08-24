package com.leviknet.vpn

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.leviknet.vpn.core.auth.ExternalUriPolicy
import com.leviknet.vpn.core.auth.DeepLinkRouter
import com.leviknet.vpn.ui.AppEffect
import com.leviknet.vpn.ui.AppViewModel
import com.leviknet.vpn.ui.LevikVpnApp
import com.leviknet.vpn.ui.theme.LevikTheme
import kotlinx.coroutines.launch

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// This app uses ComponentActivity directly and has no FragmentActivity or Fragment dependency.
@SuppressLint("InvalidFragmentVersionForActivityResult")
class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as LevikVpnApplication).container
    private val viewModel: AppViewModel by viewModels {
        AppViewModel.factory(container)
    }
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onLocationPermissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LevikTheme(
                themeMode = state.themeMode,
            ) {
                LevikVpnApp(viewModel)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect(::handleEffect)
            }
        }
        intent?.data?.let { uri -> viewModel.handleDeepLink(uri) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { uri -> viewModel.handleDeepLink(uri) }
    }

    private fun handleEffect(effect: AppEffect) {
        when (effect) {
            is AppEffect.OpenAuthorization -> openAuthorization(effect.uri)
            is AppEffect.OpenExternal -> openAllowedUri(effect.uri)
            is AppEffect.ShareText -> {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, effect.text)
                    type = "text/plain"
                }
                val shareIntent = Intent.createChooser(sendIntent, effect.title)
                startActivity(shareIntent)
            }
            AppEffect.RequestBatteryOptimization -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = "package:$packageName".toUri()
                    }
                    runCatching { startActivity(intent) }.onFailure {
                        runCatching {
                            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                }
            }
            AppEffect.RequestVpnPermission -> {
                val permissionIntent = container.vpnController.permissionIntent()
                if (permissionIntent == null) {
                    viewModel.onVpnPermissionResult(granted = true)
                } else {
                    vpnPermissionLauncher.launch(permissionIntent)
                }
            }
            AppEffect.RequestNotificationPermission -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.onNotificationPermissionResult(granted = true)
                }
            }
            AppEffect.RequestLocationPermission -> {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    viewModel.onLocationPermissionResult(granted = true)
                } else {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        }
    }

    private fun openAuthorization(rawUri: String) {
        if (DeepLinkRouter.activationCode(rawUri) == null) return
        val uri = runCatching { rawUri.toUri() }.getOrNull() ?: return
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()
        val customTabsLaunched = runCatching { customTabsIntent.launchUrl(this, uri) }.isSuccess
        if (!customTabsLaunched) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
    }

    private fun openAllowedUri(rawUri: String) {
        val uri = runCatching { rawUri.toUri() }.getOrNull() ?: return
        val host = uri.host.orEmpty()
        val allowed = when (uri.scheme?.lowercase()) {
            "https" -> ExternalUriPolicy.isAllowedHttpsHost(host)
            "tg" -> true
            else -> false
        }
        if (!allowed) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            if (uri.scheme == "tg") {
                val domain = uri.getQueryParameter("domain") ?: return
                val start = uri.getQueryParameter("start")
                val fallback = Uri.Builder()
                    .scheme("https")
                    .authority("t.me")
                    .appendPath(domain)
                    .apply {
                        if (!start.isNullOrBlank()) appendQueryParameter("start", start)
                    }
                    .build()
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, fallback)) }
            }
        }
    }
}
