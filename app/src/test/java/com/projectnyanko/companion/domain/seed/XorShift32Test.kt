package com.projectnyanko.companion.domain.seed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XorShift32Test {
    @Test
    fun advanceAndRetreatAreInversesForNonZeroSeeds() {
        listOf(1L, 42L, 1_169_179_061L, 4_294_967_295L).forEach { seed ->
            assertEquals(seed, XorShift32.retreat(XorShift32.advance(seed)))
        }
    }

    @Test
    fun advanceUsesUnsignedThirtyTwoBitState() {
        assertEquals(268_476_417L, XorShift32.advance(1L))
        assertTrue(XorShift32.advance(4_294_967_295L) in 0L..4_294_967_295L)
    }

    @Test
    fun nextTrackAndIndexMatchUpstreamExamples() {
        val examples = listOf(
            Triple(0, 1, 1 to 1),
            Triple(1, 1, 2 to 0),
            Triple(0, 2, 2 to 0),
            Triple(1, 2, 2 to 1),
            Triple(0, 3, 2 to 1),
            Triple(1, 3, 3 to 0),
        )
        examples.forEach { (track, steps, expected) ->
            assertEquals(expected.first, XorShift32.nextIndex(track, steps))
            assertEquals(expected.second, XorShift32.nextTrack(track, steps))
        }
    }
}
