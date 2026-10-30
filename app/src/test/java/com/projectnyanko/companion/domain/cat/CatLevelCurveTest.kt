package com.projectnyanko.companion.domain.cat

import com.projectnyanko.companion.domain.seed.TestCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the growth curve against the bundled level-30 table and against known in-game values. */
class CatLevelCurveTest {
    private val stats by lazy { TestCatalog.unitStats() }

    @Test
    fun thiefCatMatchesItsRealLevelOneAndLevelFiftyValues() {
        // Thief Cat is level 30 in the bundle at 7854 HP / 918 attack; the game shows 1155 / 135 at
        // level 1 and 12474 / 1458 at level 50.
        assertEquals(1155, CatLevelCurve.project(7854, 30, 1))
        assertEquals(7854, CatLevelCurve.project(7854, 30, 30))
        assertEquals(12474, CatLevelCurve.project(7854, 30, 50))

        assertEquals(135, CatLevelCurve.project(918, 30, 1))
        assertEquals(918, CatLevelCurve.project(918, 30, 30))
        assertEquals(1458, CatLevelCurve.project(918, 30, 50))
    }

    @Test
    fun supernovaCosmoAtLevelFiftyMatchesThePredictedScreenshotValue() {
        // The number the user is checking in-game for Superstar Koneko (unit 783, evolved form).
        val form = requireNotNull(stats[783]).forms[1]
        assertEquals(71400, form.health)
        assertEquals(113400, CatLevelCurve.project(form.health, 30, 50))
        assertEquals(18495, CatLevelCurve.project(form.attack, 30, 50))
    }

    @Test
    fun growthHalvesAboveSixtyAndQuartersAboveEighty() {
        // S30 = 3400 -> S1 = 200.
        // L60 = 200 * 32 = 6400; L80 = 6400 + 20*0.25*200 = 7400;
        // L110 = 7400 + 30*0.125*200 = 8150.
        assertEquals(6400, CatLevelCurve.statAt(3400, 60))
        assertEquals(7400, CatLevelCurve.statAt(3400, 80))
        assertEquals(8150, CatLevelCurve.statAt(3400, 110))
    }

    @Test
    fun pizzaCatAtSixtySixUsesTheSecondTier() {
        // S30 = 33150 -> S1 = 1950. L60 = 62400, then +6 * 0.25 * 1950 = 2925.
        assertEquals(62400, CatLevelCurve.statAt(33150, 60))
        assertEquals(65325, CatLevelCurve.statAt(33150, 66))
    }

    @Test
    fun normalCatsKeepFullGrowthUntilNinety() {
        // Normal Cats are the legacy exception: no halving at 60, only after 90.
        // S30 = 3400 -> S1 = 200: L90 = 200 * 47 = 9400, L110 = 9400 + 20*0.25*200 = 10400.
        assertEquals(9400, CatLevelCurve.statAt(3400, 90, normalCat = true))
        assertEquals(10400, CatLevelCurve.statAt(3400, 110, normalCat = true))
        // The gacha path must still halve at 60.
        assertEquals(8150, CatLevelCurve.statAt(3400, 110, normalCat = false))
    }

    @Test
    fun rarityCapsMatchTheGamesHardLimits() {
        assertEquals(130, CatLevelCurve.capFor(com.projectnyanko.companion.domain.seed.CatRarity.UBER_RARE))
        assertEquals(130, CatLevelCurve.capFor(com.projectnyanko.companion.domain.seed.CatRarity.SUPER_RARE))
        assertEquals(130, CatLevelCurve.capFor(com.projectnyanko.companion.domain.seed.CatRarity.RARE))
        assertEquals(70, CatLevelCurve.capFor(com.projectnyanko.companion.domain.seed.CatRarity.LEGEND_RARE))
        assertEquals(110, CatLevelCurve.capFor(null))
    }

    @Test
    fun theCurveReproducesTheBundledTableForAlmostEveryForm() {
        var total = 0
        var hpOk = 0
        var atkOk = 0
        for (unit in stats.values) {
            for (form in unit.forms) {
                if (form.health <= 0) continue
                total++
                if (CatLevelCurve.project(form.health, unit.level, unit.level) == form.health) hpOk++
                if (CatLevelCurve.project(form.attack, unit.level, unit.level) == form.attack) atkOk++
            }
        }
        assertTrue("expected a large table, got $total", total > 1500)
        val hpRate = hpOk.toDouble() / total
        val atkRate = atkOk.toDouble() / total
        // 99.03% measured; the remainder are rows the source table itself has wrong.
        assertTrue("HP reproduction dropped to ${"%.2f".format(hpRate)}", hpRate >= 0.98)
        assertTrue("ATK reproduction dropped to ${"%.2f".format(atkRate)}", atkRate >= 0.98)
    }

    @Test
    fun levelsAreClampedToTheLegalRange() {
        assertEquals(CatLevelCurve.project(1155, 1, 1), CatLevelCurve.project(1155, 1, 0))
        assertEquals(CatLevelCurve.project(1155, 1, 200), CatLevelCurve.project(1155, 1, 999))
    }

    @Test
    fun aZeroStatStaysZero() {
        assertEquals(0, CatLevelCurve.project(0, 30, 50))
    }
}
