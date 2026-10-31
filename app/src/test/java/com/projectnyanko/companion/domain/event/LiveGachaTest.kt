package com.projectnyanko.companion.domain.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gacha banners are the half of the Events tab that is genuinely live: the table ships with dates, so
 * "what is on right now" is a comparison against the device clock, not a snapshot.
 */
class LiveGachaTest {
    private val json = """
        [
          {"name":"Squire Luno","start":"2026-10-30","end":"2026-11-02","guaranteed":false},
          {"name":"Halloween Units","start":"2026-10-26","end":"2026-11-16","guaranteed":true},
          {"name":"Download Celebration","start":"2026-10-05","end":"2026-10-29","guaranteed":false},
          {"name":"Platinum Capsule","start":"2018-07-13","end":"2030-08-03","guaranteed":false},
          {"name":"UBERFEST","start":"2026-11-05","end":"2026-11-09","guaranteed":false}
        ]
    """.trimIndent()

    private val all = LiveGachaParser.parse(json)
    private val today = "2026-10-31"

    @Test
    fun rowsParse() {
        assertEquals(5, all.size)
        assertTrue(all.first { it.name == "Halloween Units" }.guaranteed)
    }

    @Test
    fun liveComesFirstThenUpcomingAndEndedIsDropped() {
        val ordered = LiveGachaParser.ordered(all, today).map { it.name }
        // The Platinum capsule is live but permanent, so it is not an "event" and is listed apart.
        assertEquals(listOf("Squire Luno", "Halloween Units", "UBERFEST"), ordered)
    }

    @Test
    fun permanentCapsulesArePulledOutAndDeduplicated() {
        // Real data: 111 rows were live, 107 of them the same two capsules re-listed per re-run,
        // which made the tile read "115 live" when four banners were actually running.
        val repeated = all + all.filter { it.name == "Platinum Capsule" } + all.filter { it.name == "Platinum Capsule" }
        val permanent = LiveGachaParser.alwaysAvailable(repeated, today)
        assertEquals(listOf("Platinum Capsule"), permanent.map { it.name })
        assertEquals(3, LiveGachaParser.ordered(repeated, today).size)
    }

    @Test
    fun aBannerListedTwiceAppearsOnce() {
        val duplicated = all + all.filter { it.name == "Squire Luno" }
        assertEquals(1, LiveGachaParser.ordered(duplicated, today).count { it.name == "Squire Luno" })
    }

    @Test
    fun anOpenEndedBannerSortsLastAndSaysSo() {
        val platinum = all.first { it.name == "Platinum Capsule" }
        assertTrue(platinum.isAlwaysAvailable)
        assertTrue(platinum.status(today) == EventStatus.LIVE)
        assertFalse(all.first { it.name == "Squire Luno" }.isAlwaysAvailable)
    }

    @Test
    fun theCountdownIsInWholeDays() {
        assertEquals(2L, all.first { it.name == "Squire Luno" }.daysLeft(today))
        assertEquals(EventStatus.ENDED, all.first { it.name == "Download Celebration" }.status(today))
        assertEquals(EventStatus.UPCOMING, all.first { it.name == "UBERFEST" }.status(today))
    }
}
