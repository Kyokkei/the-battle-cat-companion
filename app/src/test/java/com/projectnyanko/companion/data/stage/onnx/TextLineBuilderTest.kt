package com.projectnyanko.companion.data.stage.onnx

import org.junit.Assert.assertEquals
import org.junit.Test

class TextLineBuilderTest {
    @Test
    fun nameSplitByAnOverlayIsJoinedBackIntoOneLine() {
        // Real 2400x1080 capture: the blue "current position" arrow cut `Hangover Couch` in three.
        val boxes = listOf(
            TextBox(1878, 144, 2029, 189, 0.84f),
            TextBox(2025, 152, 2054, 176, 0.52f),
            TextBox(2077, 159, 2100, 172, 0.41f),
        )

        val merged = TextLineBuilder.merge(boxes)

        assertEquals(1, merged.size)
        assertEquals(1878, merged.single().left)
        assertEquals(2100, merged.single().right)
        assertEquals(0.41f, merged.single().score)
    }

    @Test
    fun neighbouringCardsAreNeverJoined() {
        // The gap between two carousel cards is about three line heights; joining them would read
        // two stage names as one.
        val boxes = listOf(
            TextBox(1054, 154, 1343, 207, 0.85f),
            TextBox(1509, 140, 1730, 191, 0.85f),
            TextBox(1878, 144, 2029, 189, 0.71f),
        )

        val merged = TextLineBuilder.merge(boxes)

        assertEquals(3, merged.size)
    }

    @Test
    fun labelsOnDifferentRowsStaySeparate() {
        val boxes = listOf(
            TextBox(981, 102, 1068, 137, 0.85f),
            TextBox(981, 154, 1343, 207, 0.85f),
        )

        assertEquals(2, TextLineBuilder.merge(boxes).size)
    }

    @Test
    fun mergeIsStableForUnorderedInputAndSingleBoxes() {
        val single = TextBox(10, 20, 110, 60, 0.9f)
        assertEquals(listOf(single), TextLineBuilder.merge(listOf(single)))
        assertEquals(emptyList<TextBox>(), TextLineBuilder.merge(emptyList()))

        val unsorted = listOf(
            TextBox(300, 40, 400, 80, 0.9f),
            TextBox(100, 42, 280, 78, 0.8f),
        )
        val merged = TextLineBuilder.merge(unsorted)
        assertEquals(1, merged.size)
        assertEquals(100, merged.single().left)
        assertEquals(400, merged.single().right)
    }
}
