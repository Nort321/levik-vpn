package com.leviknet.vpn.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitelistMapPolicyTest {
    @Test fun onlyPhysicalCellularInternetCanContribute() {
        // Exercise every combination, including VPN capabilities that inherit cellular.
        for (mask in 0 until 64) {
            val flags = (0..5).map { mask and (1 shl it) != 0 }
            assertEquals(
                "capability mask $mask",
                mask == 7,
                whitelistMapNetworkIsEligible(flags[0], flags[1], flags[2], flags[3], flags[4], flags[5]),
            )
        }
    }

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
