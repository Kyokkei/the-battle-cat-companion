package com.projectnyanko.companion.domain.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * Battle Cats schedules against the device clock, so "what is on right now" has to be computed from
 * local time. The windows come from the wiki's EventClock table: `["week", "2:11:00", "0:01:00"]` is
 * day 2 (Monday) at 11:00 for one minute.
 */
class StageClockTest {
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
        GregorianCalendar(year, month - 1, day, hour, minute).apply { set(Calendar.SECOND, 0) }

    private val json = """
        {"clocks": {
          "XP Stage": [["week","2:11:00","0:01:00"],["week","2:22:00","0:01:00"]],
          "Sweet Irony": [["week","1:00:00","1:00:00"]],
          "Growing Epic": [["week","7:11:00","0:03:00"],["week","1:18:00","0:03:00"]],
          "The Crazed Cat": [["month","3:00:00","1:00:00"]],
          "January Gauntlet": [["year","1:16:11:00","15:00:00"]]
        }}
    """.trimIndent()

    private val clocks = StageClocks.parse(json)

    @Test
    fun windowsParseIntoWeekdayHourAndDuration() {
        val xp = StageClockTestAccess.window("week", "2:11:00", "0:01:00")
        assertEquals(2, xp?.day)
        assertEquals(11, xp?.hour)
        assertEquals(0, xp?.minute)
        assertEquals(1, xp?.durationMinutes)
    }

    @Test
    fun aStageIsLiveInsideItsWindowOnly() {
        // 2026-11-02 is a Monday.
        val inside = at(2026, 11, 2, 11, 0)
        val live = clocks.live(inside).map { it.name }
        assertTrue("XP Stage should be on at 11:00 Monday, got $live", "XP Stage" in live)

        val after = at(2026, 11, 2, 11, 2)
        assertTrue(clocks.live(after).none { it.name == "XP Stage" })
    }

    @Test
    fun theCountdownRunsToTheEndOfTheWindow() {
        val inside = at(2026, 11, 2, 11, 0)
        val xp = clocks.live(inside).first { it.name == "XP Stage" }
        assertEquals(1L, xp.minutesLeft(inside))
    }

    @Test
    fun aSundayMidnightStageIsLiveOnSunday() {
        // Day 1 = Sunday, and 2026-11-01 is a Sunday.
        val sunday = at(2026, 11, 1, 0, 30)
        val live = clocks.live(sunday).map { it.name }
        assertTrue("Sweet Irony should be on, got $live", "Sweet Irony" in live)
    }

    @Test
    fun laterTodayListsOnlyWindowsThatHaveNotOpenedYet() {
        val morning = at(2026, 11, 2, 9, 0)
        val later = clocks.laterToday(morning).map { it.name }
        assertTrue("XP Stage opens at 11:00", "XP Stage" in later)

        val evening = at(2026, 11, 2, 23, 0)
        assertTrue(clocks.laterToday(evening).none { it.name == "XP Stage" })
    }

    @Test
    fun theRestOfTheWeekStartsTomorrow() {
        val monday = at(2026, 11, 2, 9, 0)
        val rest = clocks.restOfWeek(monday)
        assertTrue(rest.isNotEmpty())
        rest.forEach { slot ->
            assertTrue(
                "${slot.name} should start after today",
                slot.start.get(Calendar.DAY_OF_YEAR) != monday.get(Calendar.DAY_OF_YEAR),
            )
        }
    }

    @Test
    fun aMonthlyStageUsesTheDayOfTheMonthNotTheWeekday() {
        // `["month","3:00:00",…]` is the 3rd of the month. Read as a weekday it would be a Tuesday,
        // which is exactly the bug this guards: the same "3" means two different days.
        val third = at(2026, 11, 3, 0, 30)
        assertTrue("Crazed Cat should be on the 3rd, got ${clocks.live(third).map { it.name }}",
            clocks.live(third).any { it.name == "The Crazed Cat" })

        val tuesday = at(2026, 11, 10, 0, 30)
        assertTrue(clocks.live(tuesday).none { it.name == "The Crazed Cat" })
    }

    @Test
    fun aYearlyStageCarriesItsMonth() {
        val january = at(2027, 1, 16, 12, 0)
        assertTrue(clocks.live(january).any { it.name == "January Gauntlet" })

        val february = at(2027, 2, 16, 12, 0)
        assertTrue(clocks.live(february).none { it.name == "January Gauntlet" })
    }

    @Test
    fun aBrokenRowIsSkipped() {
        val broken = StageClocks.parse("""{"clocks":{"Bad":[["week","nope","0:01:00"],["week","2:11:00","0:00:00"]]}}""")
        assertNull(StageClockTestAccess.window("week", "nope", "0:01:00"))
        assertEquals(0, broken.live(at(2026, 11, 2, 11, 0)).size)
    }
}

/** Reaches the internal parser without widening its visibility in production code. */
private object StageClockTestAccess {
    fun window(cadence: String, start: String, duration: String) = StageClocks.window(cadence, start, duration)
}
