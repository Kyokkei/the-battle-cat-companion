package com.projectnyanko.companion.data.stage.onnx

import java.io.IOException

/** CTC blank/repeat/space decoding contract shared by PP-OCR recognizers. */
object PpOcrCtcDecoder {
    fun decode(rows: Array<FloatArray>, dictionary: List<String>): OcrTextResult {
        if (rows.isEmpty()) throw IOException("PP-OCR returned no time steps.")
        val classes = rows.first().size
        if (classes != dictionary.size + 2) throw IOException("PP-OCR dictionary/class count mismatch.")
        var probabilities = true
        rows.forEach { row ->
            if (row.size != classes) throw IOException("PP-OCR logits shape mismatch.")
            row.forEach { value ->
                if (!value.isFinite()) throw IOException("PP-OCR output contains non-finite values.")
                if (value < 0f || value > 1f) probabilities = false
            }
        }

        val text = StringBuilder()
        var confidenceTotal = 0f
        var tokenCount = 0
        var previous = BLANK_ID
        rows.forEach { row ->
            val best = row.indices.maxByOrNull(row::get) ?: BLANK_ID
            val confidence = if (probabilities) {
                val sum = row.sum()
                if (sum <= 0f) 0f else row[best] / sum
            } else {
                val max = row[best]
                val denominator = row.sumOf { kotlin.math.exp((it - max).toDouble()) }
                if (denominator <= 0.0) 0f else (kotlin.math.exp((row[best] - max).toDouble()) / denominator).toFloat()
            }
            if (best != BLANK_ID && best != previous) {
                if (best == classes - 1) text.append(' ')
                else text.append(dictionary[best - 1])
                confidenceTotal += confidence
                tokenCount++
            }
            previous = best
        }
        return OcrTextResult(text.toString().trim(), if (tokenCount == 0) 0f else confidenceTotal / tokenCount)
    }

    private const val BLANK_ID = 0
}
