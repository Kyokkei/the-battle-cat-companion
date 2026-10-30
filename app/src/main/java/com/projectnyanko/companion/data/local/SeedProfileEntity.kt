package com.projectnyanko.companion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "seed_profiles")
data class SeedProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val seed: Long,
    val region: String,
    val eventId: String,
    /**
     * The cat rolled just before this table. It is the only thing the tracker needs to know about
     * your history: row 1 rerolls when it would duplicate this cat. 0 means "nothing rolled yet".
     */
    val lastCatId: Int = 0,
    /** Display name for [lastCatId], kept because the banner may have changed since. */
    val lastCatName: String = "",
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)
