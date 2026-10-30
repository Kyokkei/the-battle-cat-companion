package com.projectnyanko.companion.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "roll_history",
    foreignKeys = [
        ForeignKey(
            entity = SeedProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profileId", "sequence", "track"], unique = true)],
)
data class RollHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val profileId: Long,
    val sequence: Int,
    val track: Int,
    val catName: String,
    val rarity: String,
    val recordedAtEpochMillis: Long = System.currentTimeMillis(),
)
