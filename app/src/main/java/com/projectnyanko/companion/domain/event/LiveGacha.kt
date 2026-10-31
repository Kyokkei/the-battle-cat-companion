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

    /**
     * Live banners (soonest ending first), then what is coming.
     *
     * The Platinum and Legend capsules sit in the table with an end date of 2030 and repeat once per
     * historical entry, so counting them as live said "115 live" when four banners were actually
     * running. They are pulled out into [alwaysAvailable] and the rest is de-duplicated by name,
     * because the same banner is listed once per re-run.
     */
    fun ordered(all: List<LiveGacha>, today: String): List<LiveGacha> {
        val live = all.filter { it.status(today) == EventStatus.LIVE && !it.isAlwaysAvailable }
            .sortedBy { it.end }
        val upcoming = all.filter { it.status(today) == EventStatus.UPCOMING }.sortedBy { it.start }
        return (live + upcoming).distinctBy { it.name }
    }

    /** Permanent capsules: live, but not "events". One row per capsule, not one per re-run. */
    fun alwaysAvailable(all: List<LiveGacha>, today: String): List<LiveGacha> =
        all.filter { it.status(today) == EventStatus.LIVE && it.isAlwaysAvailable }.distinctBy { it.name }
}
