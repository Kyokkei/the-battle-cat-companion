package com.projectnyanko.companion.domain.seed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Golden parity with the live tracker at https://bc.godfat.org.
 *
 * The expected names were read out of the rendered table for event `2026-10-23_946`
 * (Hanasaka Cat / Mythical fantasy Cat heroes) on 2026-10-30, for two seeds: the user's own
 * 1169179061 and seed 14, which was picked because it produces duplicate-rare rerolls in the first
 * seven rows.
 */
class GodfatTableParityTest {
    private data class Row(
        val sequence: Int,
        val a: String,
        val aReroll: String?,
        val b: String,
        val bReroll: String?,
    )

    private val seed1169179061 = listOf(
        Row(1, "Thief Cat", null, "Tin Cat", null),
        Row(2, "Witch Cat", null, "Psychocat", null),
        Row(3, "Viking Cat", null, "Neneko", null),
        Row(4, "Bishop Cat", null, "Juliet Cat", null),
        Row(5, "Rover Cat", null, "Witch Cat", null),
        Row(6, "Kotatsu Cat", null, "Viking Cat", null),
        Row(7, "Swordsman Cat", null, "Onmyoji Cat", null),
        Row(8, "Driller Cat", null, "Onmyoji Cat", "Mer-Cat"),
        Row(9, "Wushu Cat", null, "Weightlifter Cat", null),
        Row(10, "Wushu Cat", "Bishop Cat", "Sarukani", null),
        Row(11, "Psychocat", null, "Viking Cat", null),
        Row(12, "Rich Cat III", null, "Backhoe Cat", null),
        Row(13, "Witch Cat", null, "Archer Cat", null),
        Row(14, "Welterweight Cat", null, "Witch Cat", null),
        Row(15, "Cat Toaster", null, "Pogo Cat", null),
        Row(16, "Pogo Cat", null, "Tin Cat", null),
        Row(17, "Onmyoji Cat", null, "Hip Hop Cat", null),
        Row(18, "Bishop Cat", null, "Sushi Cat", null),
        Row(19, "Metal Cat", null, "Cat Gunslinger", null),
        Row(20, "Swordsman Cat", null, "Swordsman Cat", null),
        Row(21, "Pirate Cat", null, "Thief Cat", null),
        Row(22, "Neneko", null, "Psychocat", null),
        Row(23, "Bishop Cat", null, "Witch Cat", null),
        Row(24, "Wheel Cat", null, "Cat Gunslinger", null),
        Row(25, "Pirate Cat", null, "Jurassic Cat", null),
        Row(26, "Mer-Cat", null, "Mer-Cat", null),
        Row(27, "Stilts Cat", null, "Gardener Cat", null),
        Row(28, "Swordsman Cat", null, "Wheel Cat", null),
        Row(29, "Archer Cat", null, "Gardener Cat", null),
        Row(30, "Onmyoji Cat", null, "Cutter Cat", null),
    )

    private val seed14 = listOf(
        Row(1, "Delinquent Cat", null, "Nerd Cat", null),
        Row(2, "Wheel Cat", null, "Matador Cat", null),
        Row(3, "Gardener Cat", null, "Shitakiri Sparrow", null),
        Row(4, "Fortune Teller Cat", null, "Archer Cat", null),
        Row(5, "Fortune Teller Cat", "Mer-Cat", "Rich Cat III", null),
        Row(6, "Gardener Cat", null, "Rocker Cat", null),
        Row(7, "Gardener Cat", "Swordsman Cat", "Backhoe Cat", null),
        Row(8, "Bath Cat", null, "Onmyoji Cat", null),
        Row(9, "Metal Cat", null, "Pirate Cat", null),
        Row(10, "Bishop Cat", null, "Wushu Cat", null),
        Row(11, "Wheel Cat", null, "Salon Cat", null),
        Row(12, "Swordsman Cat", null, "Pogo Cat", null),
    )

    @Test
    fun reproducesTheUsersOwnBannerTable() {
        assertTable("2026-10-23_946", 1_169_179_061L, seed1169179061)
    }

    @Test
    fun reproducesDuplicateRareRerolls() {
        assertTable("2026-10-23_946", 14L, seed14)
    }

    @Test
    fun nextAfterARerollSwitchesTrack() {
        // 5A is a duplicate of 4A, so rolling from 4A yields 5AR (Mer-Cat) and the following roll
        // lands on 6B (Rocker Cat) — the half-roll track switch.
        val graph = SeedTrackGraph(TestCatalog.pool("2026-10-23_946"), 14L, 12)
        val reroll = graph.cat(5, 0)?.rerolled
        assertEquals("Mer-Cat", reroll?.displayName)
        assertEquals("5AR", reroll?.displayPosition)
        assertEquals(TrackMarker(6, 1), graph.nextMarkerAfter(TrackMarker(5, 0, rerolled = true)))
        assertEquals("Rocker Cat", graph.cell(TrackMarker(6, 1))?.displayName)
    }

    @Test
    fun rerolledNodeJumpsTwoRowsOnTrackB() {
        // Live check: https://bc.godfat.org/?seed=1169179061&event=2026-10-23_946&pick=8BR marks
        // 10A as the next position, because track B already sits half a roll ahead.
        val graph = SeedTrackGraph(TestCatalog.pool("2026-10-23_946"), 1_169_179_061L, 20)
        assertEquals("Mer-Cat", graph.cat(8, 1)?.rerolled?.displayName)
        assertEquals(TrackMarker(10, 0), graph.nextMarkerAfter(TrackMarker(8, 1, rerolled = true)))
        assertEquals("Wushu Cat", graph.cell(TrackMarker(10, 0))?.displayName)
    }

    /**
     * Guaranteed column, read from
     * https://bc.godfat.org/?seed=1169179061&event=2026-10-18_1021&count=20&force_guaranteed=11
     */
    @Test
    fun guaranteedColumnMatchesTheLiveTable() {
        val expected = listOf(
            "Terun" to "Windy",
            "Twinstars" to "Windy",
            "Windy" to "Coppermine",
            "Myrcia" to "Kai",
            "Windy" to "Coppermine",
            "Kalisa" to "Thundia",
            "Kuu" to "Kuu",
            "Windy" to "Windy",
            "Lilin" to "Lilin",
            "Kai" to null,
        )
        val pool = TestCatalog.pool("2026-10-18_1021")
        assertEquals(11, pool.guaranteedRolls)
        val graph = SeedTrackGraph(pool, 1_169_179_061L, 32)
        expected.forEachIndexed { index, (a, b) ->
            val pair = graph.row(index + 1)
            assertEquals("${index + 1}AG", a, pair?.trackA?.guaranteedResult?.displayName)
            if (b != null) {
                assertEquals("${index + 1}BG", b, pair?.trackB?.guaranteedResult?.displayName)
            }
        }
        assertEquals("Pegasa", graph.cat(8, 1)?.rerolled?.guaranteedResult?.displayName)
    }

    private fun assertTable(eventId: String, seed: Long, expected: List<Row>) {
        val graph = SeedTrackGraph(TestCatalog.pool(eventId), seed, expected.size + 8)
        expected.forEach { row ->
            val pair = graph.row(row.sequence)
            assertEquals("row ${row.sequence} missing", true, pair != null)
            assertEquals("${row.sequence}A", row.a, pair!!.trackA.displayName)
            assertEquals("${row.sequence}B", row.b, pair.trackB.displayName)
            if (row.aReroll == null) {
                assertNull("${row.sequence}A should not reroll", pair.trackA.rerolled)
            } else {
                assertEquals("${row.sequence}AR", row.aReroll, pair.trackA.rerolled?.displayName)
            }
            if (row.bReroll == null) {
                assertNull("${row.sequence}B should not reroll", pair.trackB.rerolled)
            } else {
                assertEquals("${row.sequence}BR", row.bReroll, pair.trackB.rerolled?.displayName)
            }
        }
    }
}
