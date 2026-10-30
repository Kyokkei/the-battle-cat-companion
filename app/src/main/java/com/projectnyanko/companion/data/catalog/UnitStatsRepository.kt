package com.projectnyanko.companion.data.catalog

import android.content.Context
import com.projectnyanko.companion.domain.seed.GameRegion
import org.json.JSONArray
import org.json.JSONObject

/** Loads the bundled per-unit stats tables once per region. */
class UnitStatsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val cache = mutableMapOf<GameRegion, Map<Int, UnitStats>>()

    @Synchronized
    fun stats(region: GameRegion, unitId: Int): UnitStats? = load(region)[unitId]

    /** The whole table, for callers that need to search it (the cat scanner builds a name index). */
    @Synchronized
    fun all(region: GameRegion): Map<Int, UnitStats> = load(region)

    @Synchronized
    fun level(region: GameRegion): Int = levelByRegion[region] ?: 30

    private val levelByRegion = mutableMapOf<GameRegion, Int>()

    private fun load(region: GameRegion): Map<Int, UnitStats> = cache.getOrPut(region) {
        val directory = "neko/data/${region.name.lowercase()}"
        val units = JSONArray(read("$directory/units.json"))
        val stats = JSONObject(read("$directory/stats.json"))
        levelByRegion[region] = stats.optInt("level", 30)
        UnitStatsParser.parse(units, stats)
    }

    private fun read(path: String): String =
        appContext.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
}
