package com.projectnyanko.companion.domain.seed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedRollEngineTest {
    @Test
    fun rollsAreDeterministicForIdenticalInput() {
        val first = SeedRollEngine(SyntheticPoolFactory.create(), 1_169_179_061L).generateRows(8)
        val second = SeedRollEngine(SyntheticPoolFactory.create(), 1_169_179_061L).generateRows(8)

        assertEquals(first.map(::rowSummary), second.map(::rowSummary))
        assertEquals(8, first.size)
        assertTrueCatsHaveSlots(first)
    }

    @Test
    fun duplicateRareRollsAreMarkedAsTrackTransitions() {
        val prediction = SeedRollEngine(SyntheticPoolFactory.create(), 1_169_179_061L)
            .predict(lastSequence = 1, lastTrack = 0, count = 100)

        assertTrue(prediction.rows.any { it.trackA.rerolled != null || it.trackB.rerolled != null })
        assertTrue(prediction.route.isNotEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroSeedIsRejected() {
        SeedRollEngine(SyntheticPoolFactory.create(), 0L)
    }

    @Test
    fun guaranteedAndStepUpPoolsAttachGuaranteedUberNodes() {
        listOf(11, 15).forEach { guaranteeCount ->
            val prediction = SeedRollEngine(
                SyntheticPoolFactory.create(guaranteedRolls = guaranteeCount),
                1_169_179_061L,
            ).predict(lastSequence = 1, lastTrack = 0, count = 60)

            assertTrue(prediction.rows.any { pair ->
                pair.trackA.guaranteedResult != null || pair.trackB.guaranteedResult != null
            })
        }
    }

    @Test
    fun allDrawOutputsAStartingRouteFromPositionZero() {
        val prediction = SeedRollEngine(SyntheticPoolFactory.create(), 1_169_179_061L)
            .predict(lastSequence = 0, lastTrack = 0, count = 12)

        assertEquals(0, prediction.currentPosition.sequence)
        assertEquals(12, prediction.route.size)
        assertEquals("1A", prediction.route.first().displayPosition)
    }

    @Test
    fun guaranteedBatchesFollowUberNodeToAlternateLane() {
        val prediction = SeedRollEngine(
            SyntheticPoolFactory.create(guaranteedRolls = 11),
            1_169_179_061L,
        ).predictGuaranteedBatches(lastSequence = 0, lastTrack = 0, batchCount = 2)

        assertEquals(22, prediction.route.size)
        assertTrue(prediction.route[10].isGuaranteed)
        assertTrue(prediction.route[21].isGuaranteed)
        assertNotNull(prediction.route[10].next)
        assertEquals(prediction.route[10].next?.marker(), prediction.route[11].marker())
    }

    @Test
    fun rerollNodesAreFollowedByResolvedTrackEdges() {
        val prediction = SeedRollEngine(SyntheticPoolFactory.create(), 1_169_179_061L)
            .predict(lastSequence = 1, lastTrack = 0, count = 100)

        assertTrue(prediction.route.any { it.extraLabel.contains('R') })
        assertEquals(prediction.route.firstOrNull()?.marker(), prediction.nextPosition)
    }

    @Test
    fun thirdPartyReferenceFixtureIsPinnedSeparatelyFromCalculatedResults() {
        val expectedTrackA = listOf(
            "Salon Cat", "Fortune Teller Cat", "Weightlifter Cat", "Cat Toaster", "Shitakiri Sparrow",
            "Pirate Cat", "Onmyoji Cat", "Stilts Cat", "Cat Gunslinger", "Sniper the Recruit", "Viking Cat",
        )
        val expectedTrackB = listOf(
            "Juliet Cat", "Metal Cat", "Salon Cat", "Shitakiri Sparrow", "Onmyoji Cat",
            "Thief Cat", "Cat Gunslinger", "Tin Cat", "Surfer Cat", "Psychocat", "Pirate Cat",
        )
        assertEquals(expectedTrackA, GodfatReferenceFixture.after199A.map { it.name })
        assertEquals(expectedTrackB, GodfatReferenceFixture.after199B.map { it.name })
        assertEquals("200A", GodfatReferenceFixture.after199A.first().position)
        assertEquals("200B", GodfatReferenceFixture.after199B.first().position)
    }

    private fun rowSummary(pair: RollPair): List<String> = listOf(
        pair.trackA.displayPosition,
        pair.trackA.displayName,
        pair.trackA.next?.displayPosition.orEmpty(),
        pair.trackB.displayPosition,
        pair.trackB.displayName,
        pair.trackB.next?.displayPosition.orEmpty(),
    )

    private fun assertTrueCatsHaveSlots(pairs: List<RollPair>) {
        pairs.forEach { pair ->
            assertNotNull(pair.trackA.slot)
            assertNotNull(pair.trackB.slot)
        }
    }
}
