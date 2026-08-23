package com.leviknet.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class DistributionPolicyTest {
    @Test
    fun `compile time distribution policy stays mutually consistent`() {
        assertEquals(!BuildConfig.IS_PLAY_DISTRIBUTION, BuildConfig.SELF_UPDATE_ENABLED)
        assertEquals(!BuildConfig.IS_PLAY_DISTRIBUTION, BuildConfig.EXTERNAL_PURCHASES_ENABLED)
        assertEquals(BuildConfig.IS_PLAY_DISTRIBUTION, BuildConfig.PLAY_INTEGRITY_ENABLED)
    }
}
