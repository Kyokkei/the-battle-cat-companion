package com.projectnyanko.companion.data.local

import kotlinx.coroutines.flow.Flow

class SeedTrackerRepository(private val dao: SeedTrackerDao) {
    fun observeProfiles(): Flow<List<SeedProfileEntity>> = dao.observeProfiles()

    fun observeHistory(profileId: Long): Flow<List<RollHistoryEntity>> = dao.observeHistory(profileId)

    suspend fun saveProfile(profile: SeedProfileEntity): Long = dao.insertProfile(profile)

    suspend fun updateProfile(profile: SeedProfileEntity) {
        dao.updateProfile(
            profileId = profile.id,
            name = profile.name,
            seed = profile.seed,
            region = profile.region,
            eventId = profile.eventId,
            lastCatId = profile.lastCatId,
            lastCatName = profile.lastCatName,
        )
    }

    /**
     * Records that [catId] is now the last cat rolled, together with the seed that continues the
     * table right after it. This is the only mutation the tracker performs while you play.
     */
    suspend fun updateLastRoll(profileId: Long, seed: Long, catId: Int, catName: String) {
        dao.updateLastRoll(profileId = profileId, seed = seed, lastCatId = catId, lastCatName = catName)
    }

    suspend fun recordOutcome(entry: RollHistoryEntity) {
        dao.insertHistory(entry)
    }

    suspend fun correctOutcome(entry: RollHistoryEntity) {
        dao.updateHistory(
            entryId = entry.id,
            sequence = entry.sequence,
            track = entry.track,
            catName = entry.catName,
            rarity = entry.rarity,
        )
    }

    suspend fun deleteOutcome(profileId: Long, sequence: Int, track: Int) {
        dao.deleteHistoryEntry(profileId, sequence, track)
    }

    suspend fun deleteProfile(profileId: Long) {
        dao.clearHistory(profileId)
        dao.deleteProfile(profileId)
    }
}
