package com.projectnyanko.companion.domain.event

import org.json.JSONArray

/**
 * A gacha banner, straight from the dated event table the app bundles.
 *
 * Unlike the wiki schedule this one needs no network at all: it lists every banner with its start and
 * end, so "what is live today" is a date comparison against the device clock. That is why the gacha
 * side stays current while the special-event side is a snapshot.
 */
data class LiveGacha(
    val name: String,
    val start: String,
    val end: String,
    val guaranteed: Boolean,
) {
    fun status(today: String): EventStatus = when {
        today < start -> EventStatus.UPCOMING
        today > end -> EventStatus.ENDED
        else -> EventStatus.LIVE
    }

    fun daysLeft(today: String): Long? = daysBetween(today, end)

    /** The Platinum Capsule and friends run until 2030; nobody needs a 1,300-day countdown. */
    val isAlwaysAvailable: Boolean get() = end >= "2029-01-01"
}

object LiveGachaParser {
    fun parse(json: String): List<LiveGacha> {
        val root = JSONArray(json)
        val out = ArrayList<LiveGacha>(root.length())
        for (index in 0 until root.length()) {
            val row = root.optJSONObject(index) ?: continue
            val name = row.optString("name").trim()
            val start = row.optString("start").trim()
            val end = row.optString("end").trim()
            if (name.isEmpty() || start.length != 10 || end.length != 10) continue
            out += LiveGacha(name, start, end, row.optBoolean("guaranteed", false))
        }
        return out
    }

    /** Live first (soonest ending first), then what is coming. Ended banners are dropped. */
    fun ordered(all: List<LiveGacha>, today: String): List<LiveGacha> {
        val live = all.filter { it.status(today) == EventStatus.LIVE }
            .sortedWith(compareBy({ it.isAlwaysAvailable }, { it.end }))
        val upcoming = all.filter { it.status(today) == EventStatus.UPCOMING }.sortedBy { it.start }
        return live + upcoming
    }
}
