package com.projectnyanko.companion.data.catalog

import com.projectnyanko.companion.domain.seed.CatRarity
import org.json.JSONArray
import org.json.JSONObject

/** One evolution stage of a unit, as shown on the web tracker's cat page. */
data class UnitFormStats(
    val name: String,
    val health: Int,
    val attack: Int,
    val dps: Int,
    val attackCycleSeconds: Double,
    val range: Int,
    val speed: Int,
    val knockbacks: Int,
    val cost: Int,
    val rechargeSeconds: Double,
    val areaAttack: Boolean,
    val targets: List<String>,
    val effects: List<String>,
    val immune: List<String>,
) {
    /** The web tracker reports speed as range-units per second, 15 per internal speed point. */
    val speedPerSecond: Int get() = speed * 15
}

data class UnitStats(
    val id: Int,
    val rarity: CatRarity?,
    val level: Int,
    val forms: List<UnitFormStats>,
)

/**
 * Joins the bundled `units.json` (names, rarity) with `stats.json` (numbers at a fixed level).
 *
 * Pure so the JVM tests can check it against the shipped assets without an emulator.
 */
object UnitStatsParser {
    fun parse(unitsJson: JSONArray, statsJson: JSONObject): Map<Int, UnitStats> {
        val namesById = HashMap<Int, Pair<CatRarity?, List<String>>>(unitsJson.length())
        for (index in 0 until unitsJson.length()) {
            val item = unitsJson.optJSONObject(index) ?: continue
            val id = item.optInt("id", Int.MIN_VALUE)
            if (id == Int.MIN_VALUE) continue
            namesById[id] = parseRarity(item.optString("rarity")) to toStringList(item.optJSONArray("forms"))
        }
        val level = statsJson.optInt("level", 30)
        val statsUnits = statsJson.optJSONArray("units") ?: return emptyMap()

        val result = HashMap<Int, UnitStats>(statsUnits.length())
        for (index in 0 until statsUnits.length()) {
            val entry = statsUnits.optJSONObject(index) ?: continue
            val id = entry.optInt("id", Int.MIN_VALUE)
            if (id == Int.MIN_VALUE) continue
            val formsJson = entry.optJSONArray("forms") ?: continue
            val names = namesById[id]?.second.orEmpty()
            val forms = ArrayList<UnitFormStats>(formsJson.length())
            for (formIndex in 0 until formsJson.length()) {
                val form = formsJson.optJSONObject(formIndex) ?: continue
                forms += UnitFormStats(
                    name = names.getOrNull(formIndex) ?: "Form ${formIndex + 1}",
                    health = form.optInt("hp"),
                    attack = form.optInt("atk"),
                    dps = form.optInt("dps"),
                    attackCycleSeconds = form.optDouble("freq", 0.0),
                    range = form.optInt("range"),
                    speed = form.optInt("speed"),
                    knockbacks = form.optInt("kb"),
                    cost = form.optInt("cost"),
                    rechargeSeconds = form.optDouble("recharge", 0.0),
                    areaAttack = form.optBoolean("area"),
                    targets = toStringList(form.optJSONArray("targets")),
                    effects = toStringList(form.optJSONArray("effects")),
                    immune = toStringList(form.optJSONArray("immune")),
                )
            }
            if (forms.isEmpty()) continue
            result[id] = UnitStats(
                id = id,
                rarity = namesById[id]?.first,
                level = level,
                forms = forms,
            )
        }
        return result
    }

    private fun toStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val values = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val value = array.optString(index)
            if (value.isNotBlank()) values += value
        }
        return values
    }

    private fun parseRarity(value: String): CatRarity? = when (value.trim().lowercase()) {
        "rare" -> CatRarity.RARE
        "super rare" -> CatRarity.SUPER_RARE
        "uber super rare", "uber rare" -> CatRarity.UBER_RARE
        "legend rare" -> CatRarity.LEGEND_RARE
        else -> null
    }
}
