package com.projectnyanko.companion.domain.stage

import org.junit.Assert.assertEquals
import org.junit.Test

class StageCandidateGroupsTest {
    @Test
    fun stagesThatShareANameCollapseToOneGroup() {
        val southAfrica = (1L..11L).map { stage(it, "South Africa") }

        val groups = StageCandidateGroups.group(southAfrica, StageLocale.EN)

        assertEquals(1, groups.size)
        assertEquals("South Africa", groups.single().name)
        assertEquals(11, groups.single().stages.size)
    }

    @Test
    fun groupsKeepTheOrderTheCandidatesArrivedIn() {
        val candidates = listOf(
            stage(1, "Madagascar"),
            stage(2, "South Africa"),
            stage(3, "Madagascar"),
            stage(4, "Kenya"),
        )

        val groups = StageCandidateGroups.group(candidates, StageLocale.EN)

        assertEquals(listOf("Madagascar", "South Africa", "Kenya"), groups.map { it.name })
        assertEquals(listOf(2, 1, 1), groups.map { it.stages.size })
        assertEquals(listOf(1L, 3L), groups.first().stages.map { it.id })
    }

    @Test
    fun aStageWithNoNameInTheSelectedLocaleFallsBackToJapanese() {
        val japaneseOnly = StageRecord(
            id = 7,
            sourceId = "7",
            localizedNames = mapOf(StageLocale.JP to listOf("南アフリカ")),
            baseHealth = null,
            energy = null,
            length = null,
            difficulty = "",
            enemySpawns = emptyList(),
        )

        assertEquals("南アフリカ", StageCandidateGroups.displayName(japaneseOnly, StageLocale.EN))
        assertEquals("Unknown stage", StageCandidateGroups.displayName(stage(8, ""), StageLocale.EN))
    }

    @Test
    fun theSameBattleSoldForCataminIsListedOnce() {
        val spawn = listOf(
            EnemySpawn(
                enemyId = 0,
                count = null,
                initialSpawn = 0L,
                interval = 90L,
                maximum = 600L,
                probability = 100L,
                bossFlag = 0L,
                magnification = null,
                rawFields = listOf("0", "", "0", "90", "600", "100", "0", "b4"),
            ),
        )
        // Sweet XP (Expert) is listed for 250 energy and again for 5 catamin; only the energy differs.
        val energyCopy = stage(21, "Sweet XP (Expert)").copy(baseHealth = 240_000, length = 4_000, enemySpawns = spawn)
        val cataminCopy = stage(22, "Sweet XP (Expert)").copy(baseHealth = 240_000, length = 4_000, enemySpawns = spawn)
        val otherChapter = stage(23, "Sweet XP (Expert)").copy(baseHealth = 280_000, length = 4_000, enemySpawns = spawn)

        val groups = StageCandidateGroups.group(listOf(energyCopy, cataminCopy, otherChapter), StageLocale.EN)

        assertEquals(1, groups.size)
        assertEquals(listOf(21L, 23L), groups.single().stages.map { it.id })
    }

    private fun stage(id: Long, name: String) = StageRecord(
        id = id,
        sourceId = id.toString(36),
        localizedNames = if (name.isEmpty()) emptyMap() else mapOf(StageLocale.EN to listOf(name)),
        baseHealth = null,
        energy = null,
        length = null,
        difficulty = "",
        enemySpawns = emptyList(),
    )
}
