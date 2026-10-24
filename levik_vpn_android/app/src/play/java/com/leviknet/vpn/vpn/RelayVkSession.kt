package com.leviknet.vpn.vpn

/** Google Play builds have no WDTT relay, so no VK login either. */
internal fun hasStoredVkSession(): Boolean = false
