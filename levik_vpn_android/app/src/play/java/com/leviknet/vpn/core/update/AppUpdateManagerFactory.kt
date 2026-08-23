package com.leviknet.vpn.core.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Suppress("UNUSED_PARAMETER")
internal fun createAppUpdateManager(context: Context): AppUpdateManager = DisabledAppUpdateManager

private object DisabledAppUpdateManager : AppUpdateManager {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    override suspend fun checkForUpdates(silent: Boolean): AppUpdateDto? = null

    override suspend fun downloadAndInstall(update: AppUpdateDto) = Unit

    override fun dismiss() = Unit
}
