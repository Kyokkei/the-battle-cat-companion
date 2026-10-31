package com.projectnyanko.companion.domain.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Carrier → EX stage linking. The `$id|chance` rows name their target; the older numeric rows only
 * store a group index, so those fall back to the game's own naming (`Rays of Gold (Hard)` and
 * friends), which is how the player actually sees them in the stage list.
 */
class ExStageTargetTest {
    private val metadata = StageCatalogMetadata(
        stageCount = 0,
        translationRowCount = 0,
        enemySpriteCount = 0,
        missingEnemySpriteIds = emptySet(),
        source = "test",
        attribution = "test",
        license = "test",
        hashes = emptyMap(),
    )

    private fun record(
        id: Long,
        sourceId: String,
        name: String,
        energy: Long,
        exStages: List<ExStageRef> = emptyList(),
    ) = StageRecord(
        id = id,
        sourceId = sourceId,
        localizedNames = mapOf(StageLocale.EN to listOf(name)),
        baseHealth = null,
        energy = energy,
        length = null,
        difficulty = "",
        enemySpawns = emptyList(),
        exStages = exStages,
    )

    @Test
    fun anExplicitLinkWinsOverTheName() {
        val carrier = record(1, "carrier", "Rays of Gold", 200, listOf(ExStageRef("target", 15)))
        val target = record(2, "target", "Something Else Entirely", 0)
        val decoy = record(3, "decoy", "Rays of Gold (Hard)", 0)
        val catalog = StageCatalog(listOf(carrier, target, decoy), emptyList(), metadata)

        val found = catalog.exStageTargets(carrier, StageLocale.EN)
        assertEquals(listOf(target), found.map { it.first })
        assertEquals(listOf(15), found.map { it.second })
    }

    @Test
    fun aLegacyRowFindsItsExStagesByName() {
        // Real shape: `15,004,00,02$` carries no id, and the game ships the three EX stages as
        // zero-energy stages named after the carrier.
        val carrier = record(1, "uh84", "Rays of Gold", 200, listOf(ExStageRef(null, 15)))
        val hard = record(2, "a", "Rays of Gold (Hard)", 0)
        val expert = record(3, "b", "Rays of Gold (Expert)", 0)
        val insane = record(4, "c", "Rays of Gold (Insane)", 0)
        val notEx = record(5, "d", "Rays of Gold 2", 200)
        val catalog = StageCatalog(listOf(carrier, hard, expert, insane, notEx), emptyList(), metadata)

        val found = catalog.exStageTargets(carrier, StageLocale.EN)
        assertEquals(listOf(hard, expert, insane), found.map { it.first })
        assertEquals(listOf(15, 15, 15), found.map { it.second })
    }

    @Test
    fun aCarrierWithNoNameableTargetReturnsNothing() {
        val carrier = record(1, "qsia", "First Round (Deadly)", 200, listOf(ExStageRef(null, 100)))
        val catalog = StageCatalog(listOf(carrier), emptyList(), metadata)
        assertTrue(catalog.exStageTargets(carrier, StageLocale.EN).isEmpty())
    }

    @Test
    fun anExStageKnowsWhichCarrierSpawnsIt() {
        val carrier = record(1, "uh84", "Rays of Gold", 200, listOf(ExStageRef("a", 15)))
        val ex = record(2, "a", "Rays of Gold (Hard)", 0)
        val catalog = StageCatalog(listOf(carrier, ex), emptyList(), metadata)
        assertEquals(carrier, catalog.carrierOf(ex))
    }
}
