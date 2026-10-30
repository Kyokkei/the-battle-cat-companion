package com.projectnyanko.companion.domain.seed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnyanko.companion.data.catalog.CatalogLookup
import com.projectnyanko.companion.data.catalog.GachaCatalogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the exhaustive 2^32 search on the real device against the real bundled catalog.
 *
 * This is the number that decides whether the phone actually beats waiting in the web queue, so it
 * asserts the answer *and* prints the wall time.
 */
@RunWith(AndroidJUnit4::class)
class SeedSeekerDeviceTest {
    @Test
    fun fullScanFindsTheSeedOnDevice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = GachaCatalogRepository(context)
        val pool = when (val lookup = catalog.resolve(GameRegion.EN, "2026-10-23_946")) {
            is CatalogLookup.Available -> lookup.pool
            is CatalogLookup.Unavailable -> error(lookup.reason)
        }
        val graph = SeedTrackGraph(pool, 1_169_179_061L, 20)
        val rolls = graph.route(TrackMarker(0, 0), 10).map { cat ->
            ObservedRoll(cat.rarity, requireNotNull(cat.slotIndex), cat.id, cat.displayName)
        }
        assertEquals(10, rolls.size)

        val outcome = SeedSeeker().seek(pool, rolls)
        println(
            "DEVICE_SEEK elapsedMs=${outcome.elapsedMillis} scanned=${outcome.scanned} " +
                "method=${outcome.method} runType=${outcome.runType} " +
                "cores=${Runtime.getRuntime().availableProcessors()} seed=${outcome.startingSeed}",
        )
        assertEquals(1_169_179_061L, outcome.startingSeed)
        assertTrue("a full scan should visit hundreds of millions of candidates", outcome.scanned > 1_000_000)

        // Second pass in the same process: same work, already JIT-compiled.
        val warm = SeedSeeker().seek(pool, rolls)
        println("DEVICE_SEEK_WARM elapsedMs=${warm.elapsedMillis} scanned=${warm.scanned}")
        assertEquals(1_169_179_061L, warm.startingSeed)

        // Fewer threads avoids being held back by the little cores finishing last.
        val quarter = SeedSeeker(threadCount = 4).seek(pool, rolls)
        println("DEVICE_SEEK_4T elapsedMs=${quarter.elapsedMillis} scanned=${quarter.scanned}")
        assertEquals(1_169_179_061L, quarter.startingSeed)
    }
}
