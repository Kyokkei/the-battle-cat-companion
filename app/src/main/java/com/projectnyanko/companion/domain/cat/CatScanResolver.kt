package com.projectnyanko.companion.domain.cat

import com.projectnyanko.companion.domain.stage.OcrStageLine

/** A cat read off the screen, with the level printed next to it when one was legible. */
data class CatScanHit(
    val unitId: Int,
    val formIndex: Int,
    val displayName: String,
    /** The level printed on the card, before plus-levels. Null when nothing legible was found. */
    val level: Int?,
    /** `+90` on a hyper-levelled cat; null when the card shows no plus level. */
    val plusLevel: Int?,
    val nameConfidence: Float,
    val matchedText: String,
) {
    /**
     * Level used for the stat projection. Plus-levels add the same growth as a normal level, so a
     * `20 +90` card is projected as level 110.
     */
    val effectiveLevel: Int? get() = level?.let { it + (plusLevel ?: 0) }
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

    /**
     * The label and its number are often merged into one detected line: the Mohawk Cat card reads
     * `Leve1 20`, with the recognizer turning the `l` into a `1`.
     */
    private val LEVEL_IN_LINE = Regex("""leve[l1]\s*(\d{1,3})""", RegexOption.IGNORE_CASE)
    private val PLUS_LINE = Regex("""^\+\s*([0-9OoIl]{1,2})$""")

    fun resolve(lines: List<OcrStageLine>, index: CatNameIndex): CatScanHit? {
        if (lines.isEmpty()) return null
        val name = bestName(lines, index) ?: return null
        val (level, plus) = readLevel(lines)
        return CatScanHit(
            unitId = name.match.entry.unitId,
            formIndex = name.match.entry.formIndex,
            displayName = name.match.entry.displayName,
            level = level,
            plusLevel = plus,
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

    private data class LevelRead(val level: Int?, val plus: Int?)

    private fun readLevel(lines: List<OcrStageLine>): LevelRead {
        val anchors = lines.filter { isLevelAnchor(it.text) }
        val levels = lines.mapNotNull { line -> parseLevel(line.text)?.let { it to line } }
        val pluses = lines.mapNotNull { line -> parsePlus(line.text)?.let { it to line } }

        val level = pick(levels, anchors)
        // A plus level belongs to the same card, so it only counts when it sits near the level.
        val plus = pluses.minByOrNull { (_, line) ->
            val centre = (line.top + line.bottom) / 2f
            val reference = level?.second
            if (reference == null) 0f else kotlin.math.abs((reference.top + reference.bottom) / 2f - centre)
        }?.let { (value, line) ->
            val reference = level?.second
            if (reference == null) {
                value
            } else {
                val distance = kotlin.math.abs((reference.top + reference.bottom) / 2f - (line.top + line.bottom) / 2f)
                if (distance <= MAX_LEVEL_DISTANCE) value else null
            }
        }
        return LevelRead(level?.first, plus)
    }

    private fun pick(
        candidates: List<Pair<Int, OcrStageLine>>,
        anchors: List<OcrStageLine>,
    ): Pair<Int, OcrStageLine>? {
        val inRange = candidates.filter { (value, _) -> value in CatLevelCurve.MIN_LEVEL..CatLevelCurve.MAX_LEVEL }
        if (inRange.isEmpty()) return null
        if (anchors.isEmpty()) {
            // Without the word "Level" on screen a lone plausible number is still the best guess, but
            // two of them means we cannot tell which is the level.
            return inRange.singleOrNull()
        }
        return inRange
            .map { (value, line) -> Triple(value, line, anchors.minOf { verticalDistance(it, line) }) }
            .filter { it.third <= MAX_LEVEL_DISTANCE }
            .minByOrNull { it.third }
            ?.let { it.first to it.second }
    }

    private fun verticalDistance(a: OcrStageLine, b: OcrStageLine): Float {
        val centreA = (a.top + a.bottom) / 2f
        val centreB = (b.top + b.bottom) / 2f
        return kotlin.math.abs(centreA - centreB)
    }

    private fun isLevelAnchor(text: String): Boolean {
        val lowered = text.lowercase().replace(" ", "")
        return lowered.contains("level") || lowered.contains("leve1")
    }

    /**
     * Reads a level from one OCR line. Three shapes reach here: a bare number (`50`), a number merged
     * with its label (`Leve1 20`), and a bare number with glyph confusions (`5O`).
     */
    internal fun parseLevel(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        LEVEL_IN_LINE.find(trimmed)?.let { match ->
            return match.groupValues[1].toIntOrNull()
        }
        return parseDigits(trimmed)
    }

    /** `+90` on a hyper-levelled cat. */
    internal fun parsePlus(text: String): Int? {
        val match = PLUS_LINE.find(text.trim()) ?: return null
        val digits = buildString {
            for (ch in match.groupValues[1]) append(GLYPH_CONFUSIONS[ch] ?: ch)
        }
        return digits.toIntOrNull()
    }

    private fun parseDigits(trimmed: String): Int? {
        if (trimmed.length > 3) return null
        // Every character has to be a digit or a known glyph confusion. Without this, the label
        // itself parses: "Level" ends in 'l', which maps to '1', and the level became 1.
        if (trimmed.any { ch -> !ch.isDigit() && ch !in GLYPH_CONFUSIONS }) return null
        val cleaned = buildString {
            for (ch in trimmed) append(GLYPH_CONFUSIONS[ch] ?: ch)
        }
        return cleaned.toIntOrNull()
    }

    /** Outlined game font: O/0 and l/1 are the two the recognizer actually mixes up. */
    private val GLYPH_CONFUSIONS = mapOf('O' to '0', 'o' to '0', 'l' to '1', 'I' to '1')
}
