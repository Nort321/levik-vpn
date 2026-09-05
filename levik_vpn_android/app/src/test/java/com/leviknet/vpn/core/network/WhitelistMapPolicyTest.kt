package com.leviknet.vpn.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitelistMapPolicyTest {
    @Test fun unknownIsNeverReportedAsOpenInternet() {
        assertNull(whitelistMapSignal(WhitelistMode.UNKNOWN))
        assertEquals("active", whitelistMapSignal(WhitelistMode.ACTIVE))
        assertEquals("inactive", whitelistMapSignal(WhitelistMode.INACTIVE))
    }

    @Test fun regionIsInvalidatedOnNetworkChangeExpiryAndClockReset() {
        assertTrue(whitelistRegionTokenIsUsable(true, 100, 1000, 200))
        assertFalse(whitelistRegionTokenIsUsable(false, 100, 1000, 200))
        assertFalse(whitelistRegionTokenIsUsable(true, 100, 1000, 1000))
        assertFalse(whitelistRegionTokenIsUsable(true, 100, 1000, 99))
        assertFalse(whitelistRegionTokenIsUsable(true, 100, Long.MAX_VALUE, 100 + WHITELIST_MAP_MAX_TOKEN_AGE_MS))
    }
}
