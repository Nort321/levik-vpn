package com.leviknet.vpn.ui.sharing

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import com.leviknet.vpn.R

/** Offline guides. Values shown beside the steps come from the live sharing session. */
enum class ProxyRecipient(
    @StringRes val title: Int,
    @ArrayRes val steps: Int,
    @StringRes val restore: Int,
) {
    ANDROID(R.string.sharing_android, R.array.sharing_steps_android, R.string.sharing_restore_android),
    SAMSUNG(R.string.sharing_samsung, R.array.sharing_steps_samsung, R.string.sharing_restore_android),
    XIAOMI(R.string.sharing_xiaomi, R.array.sharing_steps_xiaomi, R.string.sharing_restore_android),
    IOS(R.string.sharing_ios, R.array.sharing_steps_ios, R.string.sharing_restore_ios),
    WINDOWS(R.string.sharing_windows, R.array.sharing_steps_windows, R.string.sharing_restore_windows),
    MACOS(R.string.sharing_macos, R.array.sharing_steps_macos, R.string.sharing_restore_macos),
    LINUX(R.string.sharing_linux, R.array.sharing_steps_linux, R.string.sharing_restore_linux),
}
