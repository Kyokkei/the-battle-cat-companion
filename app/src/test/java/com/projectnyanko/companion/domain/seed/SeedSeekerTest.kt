package com.projectnyanko.companion.domain.seed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SeedSeekerTest {
    private val eventId = "2026-10-23_946"
    private val knownSeed = 1_169_179_061L

    /** What the player would actually see, rerolls and track switches included. */
    private fun observed(pool: GachaPool, seed: Long, count: Int): List<ObservedRoll> {
        val graph = SeedTrackGraph(pool, seed, count + 8)
        return graph.route(count).map { cat ->
            ObservedRoll(
                rarity = cat.rarity,
                slot = requireNotNull(cat.slotIndex) { "rolled cat has no slot" },
                unitId = cat.id,
                name = cat.displayName,
            )
        }
    }

    @Test
    fun rejectsRollsOutsideTheBannerPool() {
        val pool = TestCatalog.pool(eventId)
        val bad = listOf(ObservedRoll(CatRarity.RARE, 9_999))
        val failure = runCatching { SeedSeeker(threadCount = 1).seek(pool, bad) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun rejectsEmptyInput() {
        val pool = TestCatalog.pool(eventId)
        assertTrue(runCatching { SeedSeeker(threadCount = 1).seek(pool, emptyList()) }.isFailure)
    }

    /**
     * The full 2^32 search. Ten observed rolls pin the seed uniquely, so this is the same work the
     * app does on the phone. It is gated because it is the slowest test in the suite; run it with
     * `NYANKO_FULL_SEEK=1 ./gradlew :app:testDebugUnitTest`.
     */
    @Test
    fun recoversTheUsersSeedFromTenRolls() {
        assumeTrue(System.getenv("NYANKO_FULL_SEEK") == "1")
        val pool = TestCatalog.pool(eventId)
        val rolls = observed(pool, knownSeed, 10)
        val outcome = SeedSeeker().seek(pool, rolls)

        println("seek: ${outcome.elapsedMillis}ms, scanned ${outcome.scanned}, method ${outcome.method}")
        assertEquals(knownSeed, outcome.startingSeed)
        assertEquals(1, outcome.hits.size)
        assertNotNull(outcome.currentSeed)
    }

    @Test
    fun findsTheSeedOfAnotherBannerAndSeed() {
        assumeTrue(System.getenv("NYANKO_FULL_SEEK") == "1")
        val pool = TestCatalog.pool("2026-10-30_926")
        val rolls = observed(pool, 14L, 10)
        val outcome = SeedSeeker().seek(pool, rolls)
        println("seek: ${outcome.elapsedMillis}ms, scanned ${outcome.scanned}")
        assertEquals(14L, outcome.startingSeed)
    }
}
