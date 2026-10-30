package com.projectnyanko.companion.domain.seed

import com.projectnyanko.companion.data.catalog.CatalogLookup
import com.projectnyanko.companion.data.catalog.GachaCatalogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class NekoCatalogParityTest {
    @Test
    fun englishEvent946PoolMatchesGodfatNextElevenFrom199A() {
        val region = parseRegion("en")
        val available = region.resolve(GodfatReferenceFixture.eventId) as? CatalogLookup.Available
            ?: error("EN event 946 must resolve from the supplied snapshot")

        assertEquals(6_970, available.pool.rareWeight)
        assertEquals(2_500, available.pool.superRareWeight)
        assertEquals(500, available.pool.uberRareWeight)
        assertEquals(0, available.pool.guaranteedRolls)
        assertEquals(66, available.pool.slotsByRarity.values.sumOf { it.size })

        val result = SeedRollEngine(available.pool, GodfatReferenceFixture.seed)
            .predict(GodfatReferenceFixture.lastPosition, 0, 11)
        assertEquals(GodfatReferenceFixture.after199A.map { it.position }, result.route.map { it.displayPosition })
        assertEquals(GodfatReferenceFixture.after199A.map { it.name }, result.route.map { it.displayName })
    }

    @Test
    fun englishEvent946AlternateLaneMatchesGodfatNextElevenFrom199B() {
        val region = parseRegion("en")
        val available = region.resolve(GodfatReferenceFixture.eventId) as CatalogLookup.Available
        val result = SeedRollEngine(available.pool, GodfatReferenceFixture.seed)
            .predict(GodfatReferenceFixture.lastPosition, 1, 11)

        assertEquals(GodfatReferenceFixture.after199B.map { it.position }, result.route.map { it.displayPosition })
        assertEquals(GodfatReferenceFixture.after199B.map { it.name }, result.route.map { it.displayName })
    }

    @Test
    fun japaneseCatalogHasIndependentPoolsAndNames() {
        val region = parseRegion("jp")
        assertTrue(region.events.isNotEmpty())
        assertTrue(region.events.none { it.eventId == GodfatReferenceFixture.eventId })
        val event = region.events.first()
        val lookup = region.resolve(event.eventId)
        assertTrue("JP pool should resolve from JP files: $lookup", lookup is CatalogLookup.Available)
        assertEquals(GameRegion.JP, (lookup as CatalogLookup.Available).pool.region)
    }

    private fun parseRegion(language: String): GachaCatalogParser.ParsedRegionCatalog {
        val cwd = Path.of(System.getProperty("user.dir"))
        val roots = listOf(cwd.resolve("neko/data"), cwd.resolve("../neko/data").normalize())
        val dataRoot = roots.firstOrNull { Files.isDirectory(it.resolve(language)) }
            ?: error("Could not locate the imported neko/data directory from $cwd")
        val regionDir = dataRoot.resolve(language)
        return GachaCatalogParser.parse(
            region = if (language == "en") GameRegion.EN else GameRegion.JP,
            eventBytes = Files.readAllBytes(regionDir.resolve("gacha_events.json")),
            poolBytes = Files.readAllBytes(regionDir.resolve("gacha_pools.json")),
            unitBytes = Files.readAllBytes(regionDir.resolve("units.json")),
        )
    }
}
