package com.projectnyanko.companion.data.catalog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnyanko.companion.domain.seed.GameRegion
import com.projectnyanko.companion.domain.seed.GodfatReferenceFixture
import com.projectnyanko.companion.domain.seed.SeedRollEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GachaCatalogParityTest {
    @Test
    fun enPool946MatchesTheCapturedGodfatLaneAfter199A() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = GachaCatalogRepository(context)
        val result = catalog.resolve(GameRegion.EN, GodfatReferenceFixture.eventId)

        assertTrue("EN event pool should resolve: $result", result is CatalogLookup.Available)
        val available = result as CatalogLookup.Available
        assertEquals(6_970, available.pool.rareWeight)
        assertEquals(2_500, available.pool.superRareWeight)
        assertEquals(500, available.pool.uberRareWeight)
        assertEquals(0, available.pool.guaranteedRolls)
        assertTrue(available.pool.slotsByRarity.values.sumOf { it.size } > 0)

        val prediction = SeedRollEngine(available.pool, GodfatReferenceFixture.seed)
            .predict(GodfatReferenceFixture.lastPosition, lastTrack = 0, count = 11)
        val expectedNames = GodfatReferenceFixture.after199A.map { it.name }
        val expectedPositions = GodfatReferenceFixture.after199A.map { it.position }
        assertEquals(expectedPositions, prediction.route.map { it.displayPosition })
        assertEquals(expectedNames, prediction.route.map { it.displayName })
    }

    @Test
    fun jpCatalogLoadsWithoutBorrowingEnglishEvents() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = GachaCatalogRepository(context)
        val jpEvents = catalog.events(GameRegion.JP)
        assertTrue("JP event catalog should load", jpEvents.isNotEmpty())
        assertTrue(jpEvents.none { it.eventId == "2026-10-23_946" })
        val available = catalog.resolve(GameRegion.JP, jpEvents.first().eventId)
        assertTrue("JP event should resolve from the JP pool data: $available", available is CatalogLookup.Available)
        assertEquals(GameRegion.JP, (available as CatalogLookup.Available).pool.region)
    }
}
