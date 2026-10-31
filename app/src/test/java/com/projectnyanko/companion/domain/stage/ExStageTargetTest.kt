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

        val found = catalog.exStageTargets(carrier)
        assertEquals(listOf(target), found.map { it.first })
        assertEquals(listOf(15), found.map { it.second })
    }

    @Test
    fun aLegacyRowWithNoTargetResolvesToNothing() {
        // `15,004,00,02$` carries no id. Guessing by name produced `Rays of Gold (Hard)`, a stage
        // PONOS deleted years ago, so a row like this now reports nothing and the link lives in
        // stage.tsv instead (Rays of Gold → Catfruit Jubilee, 5%).
        val carrier = record(1, "uh84", "Rays of Gold", 200, listOf(ExStageRef(null, 15)))
        val removed = record(2, "a", "Rays of Gold (Hard)", 0)
        val catalog = StageCatalog(listOf(carrier, removed), emptyList(), metadata)
        assertTrue(catalog.exStageTargets(carrier).isEmpty())
    }

    @Test
    fun aCarrierWithNoNameableTargetReturnsNothing() {
        val carrier = record(1, "qsia", "First Round (Deadly)", 200, listOf(ExStageRef(null, 100)))
        val catalog = StageCatalog(listOf(carrier), emptyList(), metadata)
        assertTrue(catalog.exStageTargets(carrier).isEmpty())
    }

    @Test
    fun theChainWalksTheRoundsInOrder() {
        // The Proving Grounds shape: every round offers a Dawn and a Dusk branch, and both continue
        // into the same next round. Breadth first, so the card reads round by round.
        val r1 = record(1, "r1", "First Round (Deadly)", 200, listOf(ExStageRef("r2a", 100), ExStageRef("r2b", 100)))
        val r2a = record(2, "r2a", "2nd Round: Dawn (Deadly)", 0, listOf(ExStageRef("r3", 100)))
        val r2b = record(3, "r2b", "2nd Round: Dusk (Deadly)", 0, listOf(ExStageRef("r3", 100)))
        val r3 = record(4, "r3", "3rd Round (Deadly)", 0)
        val catalog = StageCatalog(listOf(r1, r2a, r2b, r3), emptyList(), metadata)

        val chain = catalog.exStageChain(r1)
        assertEquals(listOf(r2a, r2b, r3), chain.map { it.stage })
        assertEquals(listOf(1, 1, 2), chain.map { it.depth })
    }

    @Test
    fun aCycleDoesNotLoopForever() {
        val a = record(1, "a", "A", 200, listOf(ExStageRef("b", 100)))
        val b = record(2, "b", "B", 0, listOf(ExStageRef("a", 100)))
        val catalog = StageCatalog(listOf(a, b), emptyList(), metadata)
        assertEquals(listOf(b), catalog.exStageChain(a).map { it.stage })
    }

    @Test
    fun anExStageKnowsWhichCarrierSpawnsIt() {
        val carrier = record(1, "uh84", "Rays of Gold", 200, listOf(ExStageRef("a", 15)))
        val ex = record(2, "a", "Rays of Gold (Hard)", 0)
        val catalog = StageCatalog(listOf(carrier, ex), emptyList(), metadata)
        assertEquals(carrier, catalog.carrierOf(ex))
    }
}
