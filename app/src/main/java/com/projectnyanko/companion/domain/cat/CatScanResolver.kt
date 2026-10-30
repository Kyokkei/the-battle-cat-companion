package com.projectnyanko.companion.domain.cat

import com.projectnyanko.companion.domain.stage.OcrStageLine

/** A cat read off the screen, with the level printed next to it when one was legible. */
data class CatScanHit(
    val unitId: Int,
    val formIndex: Int,
    val displayName: String,
    val level: Int?,
    val nameConfidence: Float,
    val matchedText: String,
) {
    val corrected: Boolean get() = matchedText.trim().lowercase() != displayName.lowercase()
}

/**
 * Turns raw OCR lines from a Battle Cats cat screen into a unit, form and level.
 *
 * Nothing here depends on screen geometry beyond "the level sits near the word Level", which is why
 * the same code reads both the Upgrade detail card and the Organize Cats panel without a per-screen
 * layout table.
 */
object CatScanResolver {
    /** Below this the recognizer is guessing; a wrong cat is worse than no answer. */
    private const val MIN_NAME_CONFIDENCE = 0.45f

    /** How far the level number may sit from the word Level, in screen heights. */
    private const val MAX_LEVEL_DISTANCE = 0.15f

    fun resolve(lines: List<OcrStageLine>, index: CatNameIndex): CatScanHit? {
        if (lines.isEmpty()) return null
        val name = bestName(lines, index) ?: return null
        val level = bestLevel(lines, name.line)
        return CatScanHit(
            unitId = name.match.entry.unitId,
            formIndex = name.match.entry.formIndex,
            displayName = name.match.entry.displayName,
            level = level,
            nameConfidence = name.line.confidence,
            matchedText = name.line.text,
        )
    }

    private data class NamedLine(val line: OcrStageLine, val match: CatNameMatch)

    private fun bestName(lines: List<OcrStageLine>, index: CatNameIndex): NamedLine? {
        var best: NamedLine? = null
        for (line in lines) {
            if (line.confidence < MIN_NAME_CONFIDENCE) continue
            val match = index.resolve(line.text) ?: continue
            val current = best
            // Prefer the more confident read; on a tie prefer the longer name, because a truncated
            // crop matches short names just as well but says less.
            val better = current == null ||
                match.exact && !current.match.exact ||
                (match.exact == current.match.exact && line.confidence > current.line.confidence) ||
                (match.exact == current.match.exact && line.confidence == current.line.confidence &&
                    match.entry.displayName.length > current.match.entry.displayName.length)
            if (better) best = NamedLine(line, match)
        }
        return best
    }

    private fun bestLevel(lines: List<OcrStageLine>, nameLine: OcrStageLine): Int? {
        val candidates = lines.mapNotNull { line ->
            val value = parseLevel(line.text) ?: return@mapNotNull null
            value to line
        }.filter { (value, _) -> value in CatLevelCurve.MIN_LEVEL..CatLevelCurve.MAX_LEVEL }
        if (candidates.isEmpty()) return null

        val anchors = lines.filter { isLevelAnchor(it.text) }
        if (anchors.isEmpty()) {
            // Without the word "Level" on screen a lone plausible number is still the best guess, but
            // two of them means we cannot tell which is the level.
            return candidates.singleOrNull()?.first
        }
        val scored = candidates.map { (value, line) ->
            val distance = anchors.minOf { verticalDistance(it, line) }
            Triple(value, line, distance)
        }.filter { it.third <= MAX_LEVEL_DISTANCE }
        if (scored.isEmpty()) return null
        return scored.minByOrNull { it.third }?.first
    }

    private fun verticalDistance(a: OcrStageLine, b: OcrStageLine): Float {
        val centreA = (a.top + a.bottom) / 2f
        val centreB = (b.top + b.bottom) / 2f
        return kotlin.math.abs(centreA - centreB)
    }

    private fun isLevelAnchor(text: String): Boolean =
        text.lowercase().replace(" ", "").contains("level")

    /**
     * Reads a level from one OCR line. The game prints the number alone next to a stacked
     * "Max"/"Level" label, and the recognizer occasionally confuses O with 0 or l with 1 in the
     * outlined font, so those two substitutions are undone before parsing.
     */
    internal fun parseLevel(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 2) return null
        // Every character has to be a digit or a known glyph confusion. Without this, the label
        // itself parses: "Level" ends in 'l', which maps to '1', and the level became 1.
        if (trimmed.any { ch -> !ch.isDigit() && ch !in GLYPH_CONFUSIONS } ) return null
        val cleaned = buildString {
            for (ch in trimmed) append(GLYPH_CONFUSIONS[ch] ?: ch)
        }
        return cleaned.toIntOrNull()
    }

    /** Outlined game font: O/0 and l/1 are the two the recognizer actually mixes up. */
    private val GLYPH_CONFUSIONS = mapOf('O' to '0', 'o' to '0', 'l' to '1', 'I' to '1')
}
