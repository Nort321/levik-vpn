package com.leviknet.vpn

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Opt-in local tests must not start the account container, workers, or VPN service. */
class HotSwitchTestRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, className: String, context: Context): Application =
        super.newApplication(loader, Application::class.java.name, context)
}
