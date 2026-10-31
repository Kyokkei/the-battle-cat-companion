package com.projectnyanko.companion.domain.event

import org.json.JSONObject
import java.util.Calendar

/**
 * How often a stage comes back.
 *
 * The wiki writes all three in the same slot but the numbers mean different things, which is easy to
 * get wrong: `["week","2:11:00",…]` is day-of-week 2, while `["month","3:00:00",…]` is the **3rd of
 * the month** — the same "3" but a completely different day.
 */
enum class ClockCadence { WEEKLY, MONTHLY, YEARLY }

/**
 * One appearance window of a stage that comes back on a schedule.
 *
 * Times are the **player's** local time: Battle Cats schedules against the device clock, not a
 * server, so a window is computed here rather than looked up.
 */
data class ClockWindow(
    val cadence: ClockCadence,
    /** Weekday 1..7 (1 = Sunday) for weekly, day-of-month for monthly and yearly. */
    val day: Int,
    /** Month 1..12, only for yearly entries. */
    val month: Int?,
    val hour: Int,
    val minute: Int,
    val durationMinutes: Int,
)

data class StageClock(
    val name: String,
    val windows: List<ClockWindow>,
)

/** A concrete appearance: which stage, and when it opens and closes. */
data class ClockSlot(
    val name: String,
    val start: Calendar,
    val end: Calendar,
) {
    /** Whole minutes until it closes, rounded up: at 19:01 a window ending 19:02 has 1 left, not 0. */
    fun minutesLeft(now: Calendar): Long =
        ((end.timeInMillis - now.timeInMillis) + 59_999L) / 60_000L

    fun minutesUntil(now: Calendar): Long =
        ((start.timeInMillis - now.timeInMillis) + 59_999L) / 60_000L
}

/**
 * Everything the schedule knows, answered against the device clock.
 *
 * The game has no server-side notion of "now": a stage appears when the local clock reaches its
 * window, so this is computed here rather than read from a table of dates.
 */
class StageClocks(private val clocks: List<StageClock>) {
    /** Windows that are open right now, soonest-ending first. */
    fun live(now: Calendar): List<ClockSlot> = clocks.flatMap { clock ->
        clock.windows.mapNotNull { window ->
            slotAround(clock.name, window, now)
        }
    }.sortedBy { it.end.timeInMillis }

    /** Windows that open later today, earliest first. */
    fun laterToday(now: Calendar): List<ClockSlot> = clocks.flatMap { clock ->
        clock.windows.mapNotNull { window ->
            startOn(clock.name, window, now, dayOffset = 0)
                ?.takeIf { it.start.timeInMillis > now.timeInMillis }
        }
    }.sortedBy { it.start.timeInMillis }

    /** The next appearance of every stage that is not already covered by [live] or [laterToday]. */
    fun restOfWeek(now: Calendar): List<ClockSlot> {
        val tomorrow = startOfTomorrow(now).timeInMillis
        return clocks.mapNotNull { clock ->
            clock.windows.mapNotNull { window -> nextAfter(clock.name, window, now) }
                .minByOrNull { it.start.timeInMillis }
        }.filter { it.start.timeInMillis >= tomorrow }
            .sortedBy { it.start.timeInMillis }
    }

    private fun isInside(slot: ClockSlot, now: Calendar): Boolean {
        val t = now.timeInMillis
        return t >= slot.start.timeInMillis && t < slot.end.timeInMillis
    }

    /** The window that contains [now], including one that started yesterday and runs past midnight. */
    private fun slotAround(name: String, window: ClockWindow, now: Calendar): ClockSlot? =
        startOn(name, window, now, dayOffset = 0)?.takeIf { isInside(it, now) }
            ?: startOn(name, window, now, dayOffset = -1)?.takeIf { isInside(it, now) }

    /** This window on the given day, or null when that day does not match its cadence. */
    private fun startOn(name: String, window: ClockWindow, now: Calendar, dayOffset: Int): ClockSlot? {
        val day = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
        val matches = when (window.cadence) {
            ClockCadence.WEEKLY -> day.get(Calendar.DAY_OF_WEEK) == window.day
            ClockCadence.MONTHLY -> day.get(Calendar.DAY_OF_MONTH) == window.day
            ClockCadence.YEARLY -> day.get(Calendar.DAY_OF_MONTH) == window.day &&
                day.get(Calendar.MONTH) + 1 == (window.month ?: -1)
        }
        if (!matches) return null
        val start = (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, window.hour)
            set(Calendar.MINUTE, window.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MINUTE, window.durationMinutes) }
        return ClockSlot(name, start, end)
    }

    /** The first occurrence strictly after [now], searching the next eight days. */
    private fun nextAfter(name: String, window: ClockWindow, now: Calendar): ClockSlot? {
        for (offset in 0..7) {
            val slot = startOn(name, window, now, dayOffset = offset) ?: continue
            if (slot.start.timeInMillis > now.timeInMillis) return slot
        }
        return null
    }

    private fun startOfTomorrow(now: Calendar): Calendar = (now.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    companion object {
        fun parse(json: String): StageClocks {
            val root = JSONObject(json).optJSONObject("clocks") ?: return StageClocks(emptyList())
            val out = ArrayList<StageClock>(root.length())
            for (name in root.keys()) {
                val rows = root.optJSONArray(name) ?: continue
                val windows = ArrayList<ClockWindow>(rows.length())
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONArray(index) ?: continue
                    window(row.optString(0), row.optString(1), row.optString(2))?.let { windows += it }
                }
                if (windows.isNotEmpty()) out += StageClock(name, windows)
            }
            return StageClocks(out.sortedBy { it.name })
        }

        /**
         * `week` `2:11:00` → Monday 11:00. `month` `3:00:00` → the 3rd at 00:00.
         * `year` `1:16:11:00` → January 16th at 11:00. The third field is always `H:MM:SS`.
         */
        internal fun window(cadence: String, start: String, duration: String): ClockWindow? {
            val cadenceKind = when (cadence.lowercase()) {
                "week" -> ClockCadence.WEEKLY
                "month" -> ClockCadence.MONTHLY
                "year" -> ClockCadence.YEARLY
                else -> return null
            }
            val parts = start.split(':').map { it.toIntOrNull() ?: return null }
            val durationParts = duration.split(':').map { it.toIntOrNull() ?: return null }
            if (durationParts.size != 3) return null

            val month: Int?
            val day: Int
            val hour: Int
            val minute: Int
            when (parts.size) {
                3 -> {
                    month = null
                    day = parts[0]
                    hour = parts[1]
                    minute = parts[2]
                }
                4 -> {
                    month = parts[0]
                    day = parts[1]
                    hour = parts[2]
                    minute = parts[3]
                }
                else -> return null
            }

            val total = durationParts[0] * 60 + durationParts[1]
            if (total <= 0) return null
            if (hour !in 0..23 || minute !in 0..59) return null
            when (cadenceKind) {
                ClockCadence.WEEKLY -> if (day !in 1..7) return null
                ClockCadence.MONTHLY -> if (day !in 1..31) return null
                ClockCadence.YEARLY -> if (day !in 1..31 || month == null || month !in 1..12) return null
            }
            return ClockWindow(cadenceKind, day, month, hour, minute, total)
        }
    }
}

/** `Monday`, for the "next up" line. */
fun Calendar.weekdayName(): String = when (get(Calendar.DAY_OF_WEEK)) {
    Calendar.SUNDAY -> "Sunday"
    Calendar.MONDAY -> "Monday"
    Calendar.TUESDAY -> "Tuesday"
    Calendar.WEDNESDAY -> "Wednesday"
    Calendar.THURSDAY -> "Thursday"
    Calendar.FRIDAY -> "Friday"
    else -> "Saturday"
}

/** `22:00`, in the player's local time. */
fun Calendar.clockTime(): String =
    String.format(java.util.Locale.ROOT, "%02d:%02d", get(Calendar.HOUR_OF_DAY), get(Calendar.MINUTE))

/** `under a minute` / `42 minutes` / `1 hour 5 minutes` — plain words, no shorthand to decode. */
fun minutesLabel(minutes: Long): String {
    val safe = minutes.coerceAtLeast(0)
    return when {
        safe < 1 -> "under a minute"
        safe == 1L -> "1 minute"
        safe < 60 -> "$safe minutes"
        safe % 60 == 0L -> "${safe / 60} hour" + if (safe == 60L) "" else "s"
        else -> "${safe / 60} hour" + (if (safe / 60 == 1L) " " else "s ") + "${safe % 60} minutes"
    }
}
