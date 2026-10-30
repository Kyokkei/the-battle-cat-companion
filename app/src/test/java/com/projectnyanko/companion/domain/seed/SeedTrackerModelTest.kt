package com.projectnyanko.companion.domain.seed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interaction model the tracker is built on, checked against the cases the live table shows.
 *
 * The table always restarts at 1A; "the cat you rolled before" only decides whether row 1 is a
 * duplicate. Rolling a duplicate gives you the *rerolled* cat and moves you to the other lane, which
 * is why 9A Wushu → 10A Wushu is really 9A Wushu → Bishop → 11B.
 */
class SeedTrackerModelTest {
    private val event = "2026-10-23_946"
    private val seed = 1_169_179_061L

    @Test
    fun duplicateRollShowsTheRerolledCatAndJumpsLane() {
        val graph = SeedTrackGraph(TestCatalog.pool(event), seed, 20)
        assertEquals("Wushu Cat", graph.cat(9, 0)?.displayName)
        assertEquals("Wushu Cat", graph.cat(10, 0)?.displayName)

        val reroll = graph.cat(10, 0)?.rerolled
        assertNotNull("10A must be a duplicate reroll", reroll)
        assertEquals("Bishop Cat", reroll?.displayName)
        assertEquals("10AR", reroll?.displayPosition)

        // Bishop sends you to 11B, not 11A: the extra half roll flips the lane.
        assertEquals("11B", reroll?.next?.displayPosition)
        assertEquals("Viking Cat", reroll?.next?.displayName)
    }

    @Test
    fun advancingRebasesTheTableToTheNextRoll() {
        val pool = TestCatalog.pool(event)
        val graph = SeedTrackGraph(pool, seed, 20)
        val third = requireNotNull(graph.cat(3, 0))
        val expectedNext = requireNotNull(graph.nextMarkerAfter(TrackMarker(3, 0)))

        val rebased = SeedTrackGraph(pool, third.slotSeed, 20, third.id)
        assertEquals(graph.cell(expectedNext)?.displayName, rebased.nextNode()?.displayName)
        assertEquals(TrackMarker(1, 0), rebased.nextMarker())
    }

    @Test
    fun advancingOntoADuplicateRerollsTheFirstRow() {
        val pool = TestCatalog.pool(event)
        val graph = SeedTrackGraph(pool, seed, 20)
        val ninth = requireNotNull(graph.cat(9, 0))

        val rebased = SeedTrackGraph(pool, ninth.slotSeed, 20, ninth.id)
        assertEquals("Wushu Cat", rebased.cat(1, 0)?.displayName)
        assertNotNull("row 1 must reroll when it repeats the last cat", rebased.cat(1, 0)?.rerolled)

        // The very first cat you will actually get is the reroll, then the other lane.
        val first = requireNotNull(rebased.nextNode())
        assertEquals("Bishop Cat", first.displayName)
        assertEquals("2B", first.next?.displayPosition)
        assertEquals("Viking Cat", first.next?.displayName)
    }

    @Test
    fun freshTableHasNoRerollOnTheFirstRow() {
        val graph = SeedTrackGraph(TestCatalog.pool(event), seed, 10)
        assertEquals(0, graph.lastCatId)
        assertNull(graph.cat(1, 0)?.rerolled)
        assertEquals("Thief Cat", graph.nextNode()?.displayName)
    }

    @Test
    fun pathPreviewListsEveryRollNeededToReachATarget() {
        val graph = SeedTrackGraph(TestCatalog.pool(event), seed, 20)
        val path = requireNotNull(graph.pathTo(TrackMarker(3, 0)))
        assertEquals(listOf("Thief Cat", "Witch Cat", "Viking Cat"), path.map { it.displayName })
    }

    @Test
    fun pathPreviewFollowsTheRerollInsteadOfStopping() {
        val graph = SeedTrackGraph(TestCatalog.pool(event), seed, 20)
        // 11B is only reachable through the 10A reroll, so the path must contain Bishop.
        val path = requireNotNull(graph.pathTo(TrackMarker(11, 1)))
        assertEquals(
            listOf(
                "Thief Cat", "Witch Cat", "Viking Cat", "Bishop Cat", "Rover Cat",
                "Kotatsu Cat", "Swordsman Cat", "Driller Cat", "Wushu Cat", "Bishop Cat", "Viking Cat",
            ),
            path.map { it.displayName },
        )
        assertEquals("10AR", path[9].displayPosition)
    }
}
