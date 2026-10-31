package com.projectnyanko.companion.data.catalog

import android.content.Context
import com.projectnyanko.companion.domain.seed.GameRegion

/** Loads the NekoOptimizer EN/JP catalogs, preferring a downloaded copy over the bundled one. */
class GachaCatalogRepository(context: Context) {
    private val appContext = context.applicationContext
    private val regionCache = mutableMapOf<GameRegion, GachaCatalogParser.ParsedRegionCatalog>()
    private val updater = CatalogUpdater(appContext)

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

    /** Drops the parsed catalogs so the next read picks up a freshly downloaded copy. */
    @Synchronized
    fun invalidate() {
        regionCache.clear()
    }

    private fun readRegion(region: GameRegion): GachaCatalogParser.ParsedRegionCatalog {
        val eventBytes = read(region, "gacha_events.json")
        val poolBytes = read(region, "gacha_pools.json")
        val unitBytes = read(region, "units.json")
        return GachaCatalogParser.parse(region, eventBytes, poolBytes, unitBytes)
    }

    /**
     * Downloaded copy first, bundled asset second.
     *
     * The asset is the floor: it always exists, so the app works offline and on first launch. A
     * cached download only ever sits on top of it, and [CatalogUpdater] refuses to write one that
     * would be worse than the file it replaces.
     */
    private fun read(region: GameRegion, name: String): ByteArray {
        val cached = updater.cachedFile(region, name)
        if (cached.isFile && cached.length() > 0L) {
            runCatching { return cached.readBytes() }
                .onFailure { android.util.Log.w("GachaCatalog", "cached $name unreadable, using the bundled copy") }
        }
        return appContext.assets.open("neko/data/${region.name.lowercase()}/$name").use { it.readBytes() }
    }
}
