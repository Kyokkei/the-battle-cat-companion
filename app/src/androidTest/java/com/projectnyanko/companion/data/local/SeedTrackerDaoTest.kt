package com.projectnyanko.companion.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeedTrackerDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: SeedTrackerDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.seedTrackerDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun profileAndObservedRollsPersistAndCanBeCorrected() = runBlocking {
        val profileId = dao.insertProfile(
            SeedProfileEntity(
                name = "Local test",
                seed = 1_169_179_061L,
                region = "EN",
                eventId = "2026-10-23_946",
                currentPosition = 199,
                currentTrack = 0,
            ),
        )
        val savedProfile = dao.getProfile(profileId)
        assertNotNull(savedProfile)
        assertEquals(1_169_179_061L, savedProfile?.seed)
        assertEquals(0, savedProfile?.currentTrack)

        val entryId = dao.insertHistory(
            RollHistoryEntity(
                profileId = profileId,
                sequence = 200,
                track = 0,
                catName = "Wushu Cat",
                rarity = "Rare",
            ),
        )
        dao.updateHistory(
            entryId = entryId,
            sequence = 200,
            track = 0,
            catName = "Corrected name",
            rarity = "Rare",
        )

        val rolls = dao.observeHistory(profileId).first()
        assertEquals(1, rolls.size)
        assertEquals("Corrected name", rolls.single().catName)
    }
}
