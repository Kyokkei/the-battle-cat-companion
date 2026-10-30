package com.projectnyanko.companion.domain.stage

import com.projectnyanko.companion.data.stage.onnx.PpOcrCtcDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PpOcrCtcDecoderTest {
    @Test
    fun repeatedTokensCollapseBlankSeparatesWordsAndConfidenceIsBounded() {
        // Classes: blank, H, i, space.
        val rows = arrayOf(
            floatArrayOf(0.01f, 0.97f, 0.01f, 0.01f),
            floatArrayOf(0.02f, 0.93f, 0.03f, 0.02f),
            floatArrayOf(0.98f, 0.01f, 0.005f, 0.005f),
            floatArrayOf(0.01f, 0.02f, 0.02f, 0.95f),
            floatArrayOf(0.01f, 0.02f, 0.96f, 0.01f),
        )

        val result = PpOcrCtcDecoder.decode(rows, listOf("H", "i"))

        assertEquals("H i", result.text)
        assertTrue(result.confidence in 0f..1f)
    }

    @Test(expected = java.io.IOException::class)
    fun mismatchedDictionaryAndTensorClassCountFailsClosed() {
        PpOcrCtcDecoder.decode(arrayOf(floatArrayOf(0f, 1f, 0f)), listOf("A", "B"))
    }
}
