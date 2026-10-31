package com.projectnyanko.companion.domain.event

import org.json.JSONArray
import org.json.JSONObject
import java.util.GregorianCalendar

enum class EventKind { GACHA, SPECIAL }

enum class EventStatus { LIVE, UPCOMING, ENDED }

/**
 * One dated event: the name the game shows and the days it runs between.
 *
 * Dates stay as `yyyy-MM-dd` strings because that format sorts and compares correctly as text, and
 * `java.time` is not available below API 26 without desugaring.
 */
data class ScheduledEvent(
    val name: String,
    val start: String,
    val end: String,
    val kind: EventKind,
) {
    fun status(today: String): EventStatus = when {
        today < start -> EventStatus.UPCOMING
        today > end -> EventStatus.ENDED
        else -> EventStatus.LIVE
    }

    /** Whole days until [end]; 0 means it ends today. Null when the date is unreadable. */
    fun daysLeft(today: String): Long? = daysBetween(today, end)

    /** Whole days until [start]; 0 means it starts today. */
    fun daysUntilStart(today: String): Long? = daysBetween(today, start)
}

/** A stage that comes back on a schedule, e.g. `Sweet Irony` weekly for one hour. */
data class RecurringEvent(
    val name: String,
    val cadence: String,
    val window: String,
) {
    /** `weekly` / `monthly`, for display. */
    val cadenceLabel: String
        get() = when (cadence.lowercase()) {
            "week" -> "weekly"
            "month" -> "monthly"
            "day" -> "daily"
            else -> cadence
        }
}

data class EventSchedule(
    val gacha: List<ScheduledEvent>,
    val special: List<ScheduledEvent>,
    val recurring: List<RecurringEvent>,
    val fetched: String,
) {
    fun events(kind: EventKind): List<ScheduledEvent> =
        if (kind == EventKind.GACHA) gacha else special

    /** Live first, soonest ending first; then what is coming, soonest first. */
    fun ordered(kind: EventKind, today: String): List<ScheduledEvent> {
        val all = events(kind)
        val live = all.filter { it.status(today) == EventStatus.LIVE }.sortedBy { it.end }
        val upcoming = all.filter { it.status(today) == EventStatus.UPCOMING }.sortedBy { it.start }
        return live + upcoming
    }
}

object EventScheduleParser {
    fun parse(json: String): EventSchedule {
        val root = JSONObject(json)
        return EventSchedule(
            gacha = dated(root.optJSONArray("gacha"), EventKind.GACHA),
            special = dated(root.optJSONArray("special"), EventKind.SPECIAL),
            recurring = recurring(root.optJSONArray("recurring")),
            fetched = root.optString("fetched"),
        )
    }

    private fun dated(array: JSONArray?, kind: EventKind): List<ScheduledEvent> {
        if (array == null) return emptyList()
        val out = ArrayList<ScheduledEvent>(array.length())
        for (index in 0 until array.length()) {
            val row = array.optJSONArray(index) ?: continue
            val name = row.optString(0).trim()
            val start = row.optString(1).trim()
            val end = row.optString(2).trim()
            if (name.isEmpty() || !looksLikeDate(start) || !looksLikeDate(end)) continue
            out += ScheduledEvent(name, start, end, kind)
        }
        return out
    }

    private fun recurring(array: JSONArray?): List<RecurringEvent> {
        if (array == null) return emptyList()
        val out = ArrayList<RecurringEvent>(array.length())
        for (index in 0 until array.length()) {
            val row = array.optJSONArray(index) ?: continue
            val name = row.optString(0).trim()
            if (name.isEmpty()) continue
            out += RecurringEvent(
                name = name,
                cadence = row.optString(1).trim(),
                window = row.optString(2).trim(),
            )
        }
        return out
    }

    private fun looksLikeDate(value: String): Boolean =
        value.length == 10 && value[4] == '-' && value[7] == '-'
}

/** Whole days from [from] to [to], rounded so a DST hour cannot shift the answer. */
internal fun daysBetween(from: String, to: String): Long? {
    val startMs = dayMillis(from) ?: return null
    val endMs = dayMillis(to) ?: return null
    return Math.round((endMs - startMs) / 86_400_000.0)
}

private fun dayMillis(date: String): Long? {
    val parts = date.split('-')
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31) return null
    return GregorianCalendar(year, month - 1, day).timeInMillis
}
