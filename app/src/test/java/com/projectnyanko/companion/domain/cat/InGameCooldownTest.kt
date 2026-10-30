package com.projectnyanko.companion.domain.cat

import com.projectnyanko.companion.domain.seed.TestCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the cooldown conversion against the bundled table and the numbers MyGamatoto shows.
 *
 * The table stores raw zero-upgrade frames; the game (and every stat site) displays the endgame
 * value, which is the raw count minus 266 frames, floored at 60 frames.
 */
class InGameCooldownTest {
    private val stats by lazy { TestCatalog.unitStats() }

    @Test
    fun rawSecondsAreAlwaysWholeFrames() {
        // The `x.07` decimals are 2/30 rounding artefacts, so every value must land on a frame.
        assertEquals(152L, InGameCooldown.rawFrames(5.07))
        assertEquals(242L, InGameCooldown.rawFrames(8.07))
        assertEquals(842L, InGameCooldown.rawFrames(28.07))
        assertEquals(2402L, InGameCooldown.rawFrames(80.07))
    }

    @Test
    fun cheapCatsHitTheHardTwoSecondFloor() {
        // Mohawk (152f) and Eraser (242f) are both below the reduction, so PONOS' 60-frame floor is
        // what they display — this is the value the user checks in-game.
        assertEquals("2.00s", InGameCooldown.format(5.07))
        assertEquals("2.00s", InGameCooldown.format(8.07))
        assertEquals(60L, InGameCooldown.inGameFrames(5.07))
    }

    @Test
    fun matchesMyGamatotoSpawnValues() {
        // Read off mygamatoto.com: Pizza 19.20s, Saber Alter CC 71.20s, Mega Cat 383.47s.
        assertEquals("19.20s", InGameCooldown.format(28.07))
        assertEquals(576L, InGameCooldown.inGameFrames(28.07))
        assertEquals("71.20s", InGameCooldown.format(80.07))
        assertEquals(2136L, InGameCooldown.inGameFrames(80.07))
        assertEquals("383.47s", InGameCooldown.format(392.33))
        assertEquals(11504L, InGameCooldown.inGameFrames(392.33))
    }

    @Test
    fun bundledUnitsConvertWithoutExceptions() {
        // Every real cat (cost > 0) must produce a value between the floor and its raw cooldown.
        var checked = 0
        for (unit in stats.values) {
            for (form in unit.forms) {
                if (form.cost <= 0 || form.rechargeSeconds <= 0.0) continue
                val frames = InGameCooldown.inGameFrames(form.rechargeSeconds)
                assertTrue("below floor: ${form.name}", frames >= InGameCooldown.MIN_FRAMES)
                assertTrue("grew: ${form.name}", frames <= InGameCooldown.rawFrames(form.rechargeSeconds))
                checked++
            }
        }
        assertTrue("expected the whole catalog, checked only $checked forms", checked > 500)
    }

    @Test
    fun zeroRechargeIsNotANumber() {
        assertEquals("—", InGameCooldown.format(0.0))
    }
}
