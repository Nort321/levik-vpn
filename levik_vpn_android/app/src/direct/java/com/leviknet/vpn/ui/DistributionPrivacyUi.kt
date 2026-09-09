package com.leviknet.vpn.ui

import androidx.compose.runtime.Composable

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun DistributionDataDisclosureDialog(
    disclosure: OptionalDataDisclosure?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) = Unit

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun DistributionInstalledAppsDisclosure(
    visible: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) = Unit
