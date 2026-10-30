package com.projectnyanko.companion.domain.stage

/** Every local record that shares one displayed stage name. */
data class StageNameGroup(
    val name: String,
    val stages: List<StageRecord>,
)

/**
 * Collapses a candidate list to one entry per stage name.
 *
 * The same name resolves to many records in the local catalog — 11 stages are called `South Africa`
 * — because Empire of Cats, Into the Future, the Aku Realms, and the zombie variants reuse labels.
 * Listing every record as its own row pushed the other readable cards off the result card, so the
 * list is grouped by name and the chapter choice happens one level down.
 */
object StageCandidateGroups {
    fun group(stages: List<StageRecord>, locale: StageLocale): List<StageNameGroup> {
        val byName = LinkedHashMap<String, MutableList<StageRecord>>()
        stages.forEach { stage ->
            val bucket = byName.getOrPut(displayName(stage, locale), ::mutableListOf)
            if (bucket.none { it.isSameBattleAs(stage) }) bucket.add(stage)
        }
        return byName.map { (name, entries) -> StageNameGroup(name, entries) }
    }

    fun displayName(stage: StageRecord, locale: StageLocale): String =
        stage.names(locale).firstOrNull()
            ?: stage.names(StageLocale.JP).firstOrNull()
            ?: "Unknown stage"

    /**
     * Catamin stages are the same battle sold for catamin instead of energy: the local catalog lists
     * `Sweet XP (Expert)` three times with an identical base HP, length and enemy lineup, differing
     * only in the energy column (250 energy vs 5 catamin). Offering all three made the player choose
     * between identical stages, so only the first is kept. Real duplicates — Aku Realms reusing the
     * Empire of Cats names — have different stats and are still listed separately.
     */
    private fun StageRecord.isSameBattleAs(other: StageRecord): Boolean {
        if (baseHealth == null || enemySpawns.isEmpty()) return false
        return baseHealth == other.baseHealth &&
            length == other.length &&
            enemySpawns == other.enemySpawns
    }
}
