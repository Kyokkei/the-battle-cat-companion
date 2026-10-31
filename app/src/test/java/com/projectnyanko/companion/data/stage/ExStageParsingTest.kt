package com.projectnyanko.companion.data.stage

import com.projectnyanko.companion.domain.stage.ExStageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `ex_stage` column is what tells the player that clearing a stage can drop them into a hidden
 * "EX" stage, and at what odds — the game itself never prints the percentage.
 */
class ExStageParsingTest {
    @Test
    fun oneStageOneChance() {
        assertEquals(listOf(ExStageRef("2enlk", 3)), StageCatalogParser.parseExStages("$2enlk|3"))
    }

    @Test
    fun severalStagesEachWithItsOwnChance() {
        assertEquals(
            listOf(ExStageRef("2ergg", 50), ExStageRef("2eqoq", 50)),
            StageCatalogParser.parseExStages("$2ergg,2eqoq|50,50"),
        )
    }

    @Test
    fun aMissingChanceStillKeepsTheLink() {
        assertEquals(listOf(ExStageRef("2f0pt", null)), StageCatalogParser.parseExStages("$2f0pt"))
    }

    @Test
    fun theOldNumericFormKeepsItsChanceButNotItsName() {
        // 194 rows still use `chance,group,pad,count$`: the target indexes an EX table this build
        // does not ship, but the odds — the part the game never prints — survive.
        assertEquals(listOf(ExStageRef(null, 100)), StageCatalogParser.parseExStages("100,032,00,01$"))
        assertEquals(listOf(ExStageRef(null, 5)), StageCatalogParser.parseExStages("5,000,00,02$"))
        assertTrue(StageCatalogParser.parseExStages("").isEmpty())
        assertTrue(StageCatalogParser.parseExStages("0,000,00,00$").isEmpty())
    }
}
