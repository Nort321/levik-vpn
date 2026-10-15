package com.leviknet.vpn.ui.diagnosis

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leviknet.vpn.R
import com.leviknet.vpn.core.platform.DiagnosisPolicy
import com.leviknet.vpn.core.platform.DiagnosisVerdict
import com.leviknet.vpn.core.platform.Verdict
import com.leviknet.vpn.core.platform.VerdictAction
import com.leviknet.vpn.ui.theme.LevikDimensions
import com.leviknet.vpn.vpn.localizedCountryName

/** "It's not the VPN": one sentence and one button above the diagnostics. */
@Composable
internal fun DiagnosisVerdictCard(verdict: DiagnosisVerdict, onAction: (VerdictAction) -> Unit) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = verdictText(verdict),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            val label = actionLabel(verdict.action) ?: return@Column
            Button(
                onClick = {
                    val settings = when (verdict.action) {
                        VerdictAction.NETWORK_SETTINGS -> Settings.ACTION_WIRELESS_SETTINGS
                        VerdictAction.WIFI_SETTINGS -> Settings.ACTION_WIFI_SETTINGS
                        else -> null
                    }
                    if (settings != null) {
                        try {
                            context.startActivity(Intent(settings).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: ActivityNotFoundException) {
                            // A device without this settings screen: the sentence still says what to do.
                        }
                    }
                    onAction(verdict.action)
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = LevikDimensions.ButtonHeight),
            ) {
                Text(stringResource(label), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun verdictText(verdict: DiagnosisVerdict): String {
    val service = verdict.service.orEmpty()
    val provider = verdict.provider
    val country = localizedCountryName(verdict.country)
    return when (verdict.verdict) {
        Verdict.NO_NETWORK -> stringResource(R.string.diagnosis_no_network)
        Verdict.CAPTIVE_PORTAL -> stringResource(R.string.diagnosis_captive_portal)
        Verdict.INTERNET_DOWN -> stringResource(R.string.diagnosis_internet_down)
        Verdict.WHITELISTS -> if (provider != null) {
            stringResource(R.string.diagnosis_whitelists_provider, provider)
        } else {
            stringResource(R.string.diagnosis_whitelists)
        }
        Verdict.SERVER_DOWN -> if (country != null) {
            stringResource(R.string.diagnosis_server_down_country, country)
        } else {
            stringResource(R.string.diagnosis_server_down)
        }
        Verdict.PROTOCOL -> {
            val protocol = DiagnosisPolicy.protocolName(verdict.protocol.orEmpty())
            if (provider != null) {
                stringResource(R.string.diagnosis_protocol_provider, provider, protocol)
            } else {
                stringResource(R.string.diagnosis_protocol, protocol)
            }
        }
        Verdict.VPN_UNREACHABLE -> stringResource(R.string.diagnosis_vpn_unreachable)
        Verdict.VPN_PATH_BROKEN -> stringResource(R.string.diagnosis_vpn_path_broken)
        Verdict.SERVICE_DOWN_EVERYWHERE -> stringResource(R.string.diagnosis_service_down, service)
        Verdict.SERVICE_RESTRICTED -> stringResource(R.string.diagnosis_service_restricted, service)
        Verdict.SERVICE_OPERATOR -> stringResource(R.string.diagnosis_service_operator, service, provider.orEmpty())
        Verdict.SERVICE_FAILS_DIRECT -> stringResource(R.string.diagnosis_service_direct, service)
        Verdict.SERVICE_VIA_VPN -> stringResource(R.string.diagnosis_service_via_vpn, service)
        Verdict.ALL_OK_VPN_OFF -> stringResource(R.string.diagnosis_all_ok_vpn_off)
        Verdict.ALL_OK -> stringResource(R.string.diagnosis_all_ok)
    }
}

/** Support has its own buttons below; "nothing to do" needs no button. */
private fun actionLabel(action: VerdictAction): Int? = when (action) {
    VerdictAction.RETRY -> R.string.diagnosis_action_retry
    VerdictAction.NETWORK_SETTINGS -> R.string.diagnosis_action_network
    VerdictAction.WIFI_SETTINGS -> R.string.diagnosis_action_wifi
    VerdictAction.CONNECT -> R.string.diagnosis_action_connect
    VerdictAction.BEST_SERVER -> R.string.diagnosis_action_best_server
    VerdictAction.MOBILE_SERVERS -> R.string.diagnosis_action_mobile_servers
    VerdictAction.SUPPORT, VerdictAction.NONE -> null
}
