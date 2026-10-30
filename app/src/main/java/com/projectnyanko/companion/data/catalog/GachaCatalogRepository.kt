package com.projectnyanko.companion.data.catalog

import android.content.Context
import com.projectnyanko.companion.domain.seed.GameRegion

/** Loads the supplied NekoOptimizer EN/JP JSON snapshots from app assets. */
class GachaCatalogRepository(context: Context) {
    private val appContext = context.applicationContext
    private val regionCache = mutableMapOf<GameRegion, GachaCatalogParser.ParsedRegionCatalog>()

    @Synchronized
    fun resolve(region: GameRegion, eventId: String): CatalogLookup = try {
        regionCache.getOrPut(region) { readRegion(region) }.resolve(eventId)
    } catch (error: Exception) {
        CatalogLookup.Unavailable("Could not read ${region.label} catalog: ${error.message ?: "invalid asset"}")
    }

    @Synchronized
    fun events(region: GameRegion): List<GachaEventSummary> = try {
        regionCache.getOrPut(region) { readRegion(region) }.events
    } catch (_: Exception) {
        emptyList()
    }

    private fun readRegion(region: GameRegion): GachaCatalogParser.ParsedRegionCatalog {
        val directory = "neko/data/${region.name.lowercase()}"
        val eventBytes = appContext.assets.open("$directory/gacha_events.json").use { it.readBytes() }
        val poolBytes = appContext.assets.open("$directory/gacha_pools.json").use { it.readBytes() }
        val unitBytes = appContext.assets.open("$directory/units.json").use { it.readBytes() }
        return GachaCatalogParser.parse(region, eventBytes, poolBytes, unitBytes)
    }
}
