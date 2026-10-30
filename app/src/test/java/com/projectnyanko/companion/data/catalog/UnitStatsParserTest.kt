package com.projectnyanko.companion.data.catalog

import com.projectnyanko.companion.domain.seed.CatRarity
import com.projectnyanko.companion.domain.seed.TestCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the joined stat table against the shipped assets. */
class UnitStatsParserTest {
    private val stats by lazy { TestCatalog.unitStats() }

    @Test
    fun thiefCatHasThreeFormsWithJoinedNames() {
        val unit = stats[49]
        assertNotNull(unit)
        assertEquals(CatRarity.RARE, unit!!.rarity)
        assertEquals(30, unit.level)
        assertEquals(
            listOf("Thief Cat", "Phantom Thief Cat", "Goemon Cat"),
            unit.forms.map { it.name },
        )
    }

    @Test
    fun thiefCatNumbersMatchTheBundledTable() {
        val first = requireNotNull(stats[49]).forms.first()
        assertEquals(7854, first.health)
        assertEquals(918, first.attack)
        assertEquals(918, first.dps)
        assertEquals(170, first.range)
        assertEquals(3, first.knockbacks)
        assertEquals(495, first.cost)
        assertEquals(1.0, first.attackCycleSeconds, 0.001)
        assertEquals(195, first.speedPerSecond)
        assertTrue(first.effects.contains("Extra money"))
    }

    @Test
    fun everyUnitWithStatsHasFormsAndGachaRaritiesResolve() {
        assertTrue("expected a full table", stats.size > 500)
        // Normal and Special units exist in the table but are not gacha rarities, so their
        // rarity is deliberately null; every gacha unit must still resolve.
        val gacha = stats.values.count { it.rarity != null }
        assertTrue("expected most units to carry a gacha rarity", gacha > 500)
        assertTrue(
            stats.values.all { unit -> unit.forms.isNotEmpty() && unit.forms.all { it.name.isNotBlank() } },
        )
    }
}
