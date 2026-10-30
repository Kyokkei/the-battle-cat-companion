package com.projectnyanko.companion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SeedTrackerDao {
    @Query("SELECT * FROM seed_profiles ORDER BY createdAtEpochMillis DESC")
    fun observeProfiles(): Flow<List<SeedProfileEntity>>

    @Query("SELECT * FROM seed_profiles WHERE id = :profileId LIMIT 1")
    suspend fun getProfile(profileId: Long): SeedProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: SeedProfileEntity): Long

    @Query("UPDATE seed_profiles SET name = :name, seed = :seed, region = :region, eventId = :eventId, lastCatId = :lastCatId, lastCatName = :lastCatName WHERE id = :profileId")
    suspend fun updateProfile(
        profileId: Long,
        name: String,
        seed: Long,
        region: String,
        eventId: String,
        lastCatId: Int,
        lastCatName: String,
    )

    @Query("UPDATE seed_profiles SET seed = :seed, lastCatId = :lastCatId, lastCatName = :lastCatName WHERE id = :profileId")
    suspend fun updateLastRoll(
        profileId: Long,
        seed: Long,
        lastCatId: Int,
        lastCatName: String,
    )

    @Query("DELETE FROM seed_profiles WHERE id = :profileId")
    suspend fun deleteProfile(profileId: Long)

    @Query("SELECT * FROM roll_history WHERE profileId = :profileId ORDER BY sequence, track")
    fun observeHistory(profileId: Long): Flow<List<RollHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHistory(entry: RollHistoryEntity): Long

    @Query("UPDATE roll_history SET sequence = :sequence, track = :track, catName = :catName, rarity = :rarity WHERE id = :entryId")
    suspend fun updateHistory(entryId: Long, sequence: Int, track: Int, catName: String, rarity: String): Int

    @Query("DELETE FROM roll_history WHERE profileId = :profileId AND sequence = :sequence AND track = :track")
    suspend fun deleteHistoryEntry(profileId: Long, sequence: Int, track: Int)

    @Query("DELETE FROM roll_history WHERE profileId = :profileId")
    suspend fun clearHistory(profileId: Long)
}
