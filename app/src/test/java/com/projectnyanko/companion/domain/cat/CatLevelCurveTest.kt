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
    fun halfUnitBasesArePreserved() {
        // Base HP is sometimes x.5, which a plain value/6.8 loses.
        val half = CatLevelCurve.baseHalfUnits(17, 30)
        assertEquals(5L, half) // 2.5 HP, stored as 5 half-units
        assertEquals(17, CatLevelCurve.valueAt(half, 30))
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
        assertEquals(CatLevelCurve.project(1155, 1, 60), CatLevelCurve.project(1155, 1, 999))
    }

    @Test
    fun aZeroStatStaysZero() {
        assertEquals(0, CatLevelCurve.project(0, 30, 50))
    }
}
