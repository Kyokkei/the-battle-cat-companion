package com.projectnyanko.companion.domain.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The schedule file is small but hand-maintained, so the parse has to be forgiving and exact. */
class EventScheduleTest {
    private val json = """
        {
          "fetched": "2026-10-31",
          "gacha": [
            ["Halloween Party! Special★Event Capsules", "2026-10-26", "2026-11-16"],
            ["THE DYNAMITES", "2026-10-30", "2026-11-02"],
            ["Download Celebration!", "2026-10-05", "2026-10-29"]
          ],
          "special": [
            ["XP Festival", "2026-11-01", "2026-11-05"]
          ],
          "recurring": [
            ["Sweet Irony", "week", "1:00:00", "1:00:00"],
            ["The Crazed Cat", "month", "3:00:00", "1:00:00"]
          ]
        }
    """.trimIndent()

    private val schedule = EventScheduleParser.parse(json)
    private val today = "2026-10-31"

    @Test
    fun theFileParses() {
        assertEquals(3, schedule.gacha.size)
        assertEquals(1, schedule.special.size)
        assertEquals(2, schedule.recurring.size)
        assertEquals("2026-10-31", schedule.fetched)
    }

    @Test
    fun liveComesBeforeUpcomingAndEndedIsDropped() {
        val ordered = schedule.ordered(EventKind.GACHA, today)
        // THE DYNAMITES ends 11-02, Halloween ends 11-16, and Download Celebration already ended.
        assertEquals(
            listOf("THE DYNAMITES", "Halloween Party! Special★Event Capsules"),
            ordered.map { it.name },
        )
    }

    @Test
    fun theCountdownIsInWholeDays() {
        val halloween = schedule.gacha.first { it.name.startsWith("Halloween") }
        assertEquals(16L, halloween.daysLeft(today))
        assertEquals(EventStatus.LIVE, halloween.status(today))

        val xp = schedule.special.single()
        assertEquals(EventStatus.UPCOMING, xp.status(today))
        assertEquals(1L, xp.daysUntilStart(today))
    }

    @Test
    fun anEndedEventIsRecognised() {
        val download = schedule.gacha.first { it.name.startsWith("Download") }
        assertEquals(EventStatus.ENDED, download.status(today))
        assertTrue(schedule.ordered(EventKind.GACHA, today).none { it.name == download.name })
    }

    @Test
    fun recurringEntriesKeepTheirCadence() {
        assertEquals("weekly", schedule.recurring[0].cadenceLabel)
        assertEquals("monthly", schedule.recurring[1].cadenceLabel)
        assertEquals("1:00:00", schedule.recurring[0].window)
    }

    @Test
    fun aBrokenRowIsSkippedInsteadOfCrashing() {
        val broken = EventScheduleParser.parse(
            """{"gacha":[["No dates"],["","2026-10-01","2026-10-02"],["Fine","2026-10-01","2026-10-02"]]}""",
        )
        assertEquals(listOf("Fine"), broken.gacha.map { it.name })
    }
}
