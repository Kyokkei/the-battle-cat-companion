package com.projectnyanko.companion.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add future schema migrations here; keep migrations explicit and data-preserving. */
object RoomMigrations {
    private val migration1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE seed_profiles ADD COLUMN currentTrack INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** Reroll and guaranteed markers: without them the next roll after a duplicate is wrong. */
    private val migration2To3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE seed_profiles ADD COLUMN currentRerolled INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE seed_profiles ADD COLUMN currentGuaranteed INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * The tracker no longer stores an absolute position: like the web tracker it keeps only the
     * last cat rolled, and always numbers the table from 1. The old position columns cannot be
     * translated into that, so they are dropped and every profile restarts at "nothing rolled".
     */
    private val migration3To4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `seed_profiles_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`seed` INTEGER NOT NULL, " +
                    "`region` TEXT NOT NULL, " +
                    "`eventId` TEXT NOT NULL, " +
                    "`lastCatId` INTEGER NOT NULL, " +
                    "`lastCatName` TEXT NOT NULL, " +
                    "`createdAtEpochMillis` INTEGER NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO `seed_profiles_new` " +
                    "(id, name, seed, region, eventId, lastCatId, lastCatName, createdAtEpochMillis) " +
                    "SELECT id, name, seed, region, eventId, 0, '', createdAtEpochMillis " +
                    "FROM `seed_profiles`",
            )
            db.execSQL("DROP TABLE `seed_profiles`")
            db.execSQL("ALTER TABLE `seed_profiles_new` RENAME TO `seed_profiles`")
        }
    }

    val all: Array<Migration> = arrayOf(migration1To2, migration2To3, migration3To4)
}
