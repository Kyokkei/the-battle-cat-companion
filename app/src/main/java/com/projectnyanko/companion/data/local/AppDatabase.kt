package com.projectnyanko.companion.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SeedProfileEntity::class, RollHistoryEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun seedTrackerDao(): SeedTrackerDao
}
