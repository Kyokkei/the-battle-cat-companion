package com.projectnyanko.companion.domain.seed

import com.projectnyanko.companion.data.catalog.CatalogLookup
import com.projectnyanko.companion.data.catalog.GachaCatalogParser
import java.io.File

/**
 * Loads the real bundled EN catalog for JVM tests.
 *
 * Gradle runs unit tests with the module directory as the working directory, so the shipped assets
 * can be read directly instead of copying a second copy of the data into test resources.
 */
object TestCatalog {
    private val directory = File("src/main/assets/neko/data/en")

    private val parsed: GachaCatalogParser.ParsedRegionCatalog by lazy {
        check(directory.isDirectory) {
            "Bundled catalog not found at ${directory.absolutePath}; run from the app module."
        }
        GachaCatalogParser.parse(
            region = GameRegion.EN,
            eventBytes = File(directory, "gacha_events.json").readBytes(),
            poolBytes = File(directory, "gacha_pools.json").readBytes(),
            unitBytes = File(directory, "units.json").readBytes(),
        )
    }

    fun pool(eventId: String): GachaPool = when (val lookup = parsed.resolve(eventId)) {
        is CatalogLookup.Available -> lookup.pool
        is CatalogLookup.Unavailable -> error("$eventId unavailable: ${lookup.reason}")
    }

    fun eventIds(): List<String> = parsed.events.map { it.eventId }

    /** The bundled per-unit stat table, joined the same way the app joins it. */
    fun unitStats(): Map<Int, com.projectnyanko.companion.data.catalog.UnitStats> {
        check(directory.isDirectory) {
            "Bundled catalog not found at ${directory.absolutePath}; run from the app module."
        }
        return com.projectnyanko.companion.data.catalog.UnitStatsParser.parse(
            org.json.JSONArray(File(directory, "units.json").readText()),
            org.json.JSONObject(File(directory, "stats.json").readText()),
        )
    }
}
