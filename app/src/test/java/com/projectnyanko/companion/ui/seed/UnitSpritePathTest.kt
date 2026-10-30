package com.projectnyanko.companion.ui.seed

import org.junit.Assert.assertEquals
import org.junit.Test

/** The asset naming has to match the dump in `assets/units` exactly. */
class UnitSpritePathTest {
    @Test
    fun padsTheUnitIdToThreeDigits() {
        assertEquals("units/uni000_f00.png", spritePath(0, "f"))
        assertEquals("units/uni049_c00.png", spritePath(49, "c"))
        assertEquals("units/uni917_s00.png", spritePath(917, "s"))
    }

    @Test
    fun keepsLongerIdsUnpadded() {
        assertEquals("units/uni10371_f00.png", spritePath(10371, "f"))
    }
}
