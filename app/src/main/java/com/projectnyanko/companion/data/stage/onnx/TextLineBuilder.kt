package com.projectnyanko.companion.data.stage.onnx

/**
 * One axis-aligned text box in frame pixels, as produced by the DB detector.
 *
 * Kept free of `android.graphics.Rect` so the merge rules below can be unit tested on the JVM.
 */
data class TextBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val score: Float,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Joins detector boxes that belong to the same text line.
 *
 * The detector splits a stage name whenever something visually interrupts it — the blue arrow over
 * `Hangover Couch` produced `Hangover` plus `C`, `c` — and a recognizer fed only `Hangover` cannot
 * match `Hangover Couch`. Two boxes are joined when they sit on one line and the horizontal gap
 * between them is smaller than a line height. That threshold keeps neighbouring cards apart: the
 * gap between two carousel cards is roughly three line heights, while the gap inside a split name is
 * well under one.
 */
object TextLineBuilder {
    private const val MAX_GAP_RATIO = 0.9f
    private const val MIN_VERTICAL_OVERLAP = 0.6f

    fun merge(boxes: List<TextBox>): List<TextBox> {
        if (boxes.size < 2) return boxes
        // Left-to-right accumulation: a line grows as its boxes arrive, so the gap test always runs
        // against the whole line read so far rather than against one fragment of it.
        val pending = boxes.sortedWith(compareBy({ it.top }, { it.left }))
        val lines = ArrayList<MutableList<TextBox>>()
        for (box in pending) {
            val line = lines.firstOrNull { joins(it, box) }
            if (line != null) line.add(box) else lines.add(mutableListOf(box))
        }
        return lines.map(::union).sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun joins(line: List<TextBox>, box: TextBox): Boolean {
        val top = line.minOf(TextBox::top)
        val bottom = line.maxOf(TextBox::bottom)
        val lineHeight = (bottom - top).coerceAtLeast(1)
        val boxHeight = box.height.coerceAtLeast(1)
        val overlap = minOf(bottom, box.bottom) - maxOf(top, box.top)
        if (overlap <= 0) return false
        if (overlap.toFloat() / minOf(lineHeight, boxHeight) < MIN_VERTICAL_OVERLAP) return false
        val left = line.minOf(TextBox::left)
        val right = line.maxOf(TextBox::right)
        val gap = maxOf(box.left - right, left - box.right)
        return gap <= MAX_GAP_RATIO * maxOf(lineHeight, boxHeight)
    }

    private fun union(line: List<TextBox>): TextBox = TextBox(
        left = line.minOf(TextBox::left),
        top = line.minOf(TextBox::top),
        right = line.maxOf(TextBox::right),
        bottom = line.maxOf(TextBox::bottom),
        score = line.minOf(TextBox::score),
    )
}
