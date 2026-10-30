package com.projectnyanko.companion.data.stage

import android.content.Context
import com.projectnyanko.companion.domain.stage.EnemyStats
import org.json.JSONObject

/**
 * Intrinsic enemy stats, parsed on the first tap and kept for the session.
 *
 * The table is 114 KB of JSON for 792 enemies. Parsing it eagerly at startup would cost a few
 * milliseconds on every launch for data most sessions never open, so it is loaded the first time an
 * enemy card is asked for and cached after that.
 */
class EnemyStatsRepository(context: Context) {
    private val appContext = context.applicationContext

    @Volatile
    private var cached: Map<Int, EnemyStats>? = null

    fun find(enemyId: Int): EnemyStats? = all()[enemyId]

    fun all(): Map<Int, EnemyStats> = cached ?: synchronized(this) {
        cached ?: load().also { cached = it }
    }

    private fun load(): Map<Int, EnemyStats> = runCatching {
        val bytes = appContext.assets.open(ASSET).use { it.readBytes() }
        val enemies = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("enemies")
        buildMap {
            for (index in 0 until enemies.length()) {
                val entry = enemies.optJSONObject(index) ?: continue
                val id = entry.optInt("id", -1)
                if (id < 0) continue
                put(
                    id,
                    EnemyStats(
                        id = id,
                        name = entry.optString("name").ifBlank { "Enemy $id" },
                        traits = entry.optJSONArray("traits").toStringList(),
                        isArea = entry.optBoolean("isArea", false),
                        range = entry.optInt("range", 0),
                        speed = entry.optInt("speed", 0),
                        knockbacks = entry.optInt("kb", 0),
                        abilities = entry.optJSONArray("abilities").toStringList(),
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private companion object {
        const val ASSET = "neko/data/en/enemy_stats.json"
    }
}
