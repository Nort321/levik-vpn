package com.leviknet.vpn.ui

import com.leviknet.vpn.ui.theme.compactAppScale
import org.junit.Assert.assertEquals
import org.junit.Test

class CompactAppScaleTest {
    @Test fun narrowPhonesMatchReferenceWidthWithBoundedReduction() {
        assertEquals(360f / 412f, compactAppScale(360f, false), 0.0001f)
        assertEquals(0.85f, compactAppScale(320f, false), 0.0001f)
        assertEquals(0.85f, compactAppScale(200f, false), 0.0001f)
    }

    @Test fun referencePhonesTabletsAndTelevisionsKeepTheirScale() {
        for (width in listOf(412f, 480f, 600f, 840f)) {
            assertEquals(1f, compactAppScale(width, false), 0f)
        }
        assertEquals(1f, compactAppScale(360f, true), 0f)
        assertEquals(1f, compactAppScale(Float.NaN, false), 0f)
        assertEquals(1f, compactAppScale(0f, false), 0f)
    }
}
