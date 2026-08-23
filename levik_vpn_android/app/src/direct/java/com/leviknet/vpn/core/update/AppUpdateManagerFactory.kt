package com.leviknet.vpn.core.update

import android.content.Context
import com.leviknet.vpn.BuildConfig

internal fun createAppUpdateManager(context: Context): AppUpdateManager = DirectAppUpdateManager(
    context = context,
    manifestPublicKeyBase64 = BuildConfig.DIRECT_UPDATE_MANIFEST_PUBLIC_KEY,
    signingCertificateSha256 = BuildConfig.DIRECT_UPDATE_SIGNING_CERTIFICATE_SHA256,
)
