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
    /** Box the level was read from, so the caller can look just below it for a plus level. */
    val levelBounds: OcrStageLine? = null,
    /** Box the name was read from; the level block always sits under it. */
    val nameBounds: OcrStageLine? = null,
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

    /** Two boxes within this much height count as the same font size. */
    private const val HEIGHT_EPSILON = 0.002f

    /** How far below the cat's name its level may print, in screen heights. */
    private const val MAX_LEVEL_BELOW = 0.35f

    /** How far sideways the level may sit from the name, in screen widths. */
    private const val MAX_LEVEL_ACROSS = 0.22f

    /** How far above a `+N` the level number may sit, in screen heights. */
    private const val MAX_PLUS_GAP = 0.06f

    /** How far the level number may sit from the word Level, in screen heights. */
    private const val MAX_LEVEL_DISTANCE = 0.15f

    /**
     * The label and its number are often merged into one detected line: the Mohawk Cat card reads
     * `Leve1 20`, with the recognizer turning the `l` into a `1`.
     */
    private val LEVEL_IN_LINE = Regex("""leve[l1]\s*(\d{1,3})""", RegexOption.IGNORE_CASE)
    private val PLUS_LINE = Regex("""^\+\s*([0-9OoIl]{1,2})$""")

    /**
     * A short word followed by the number, for labels the recognizer mangled past the point of
     * matching — `eaxe 20` instead of `Level 20`. The prefix cannot contain digits, so a counter like
     * `XP 38869800` does not qualify.
     */
    private val TRAILING_NUMBER = Regex("""^[^\d]{1,12}(\d{1,3})$""")

    fun resolve(lines: List<OcrStageLine>, index: CatNameIndex): CatScanHit? {
        if (lines.isEmpty()) return null
        val name = bestName(lines, index) ?: return null
        val read = readLevel(lines, name.line)
        val level = read.level
        val plus = read.plus
        return CatScanHit(
            unitId = name.match.entry.unitId,
            formIndex = name.match.entry.formIndex,
            displayName = name.match.entry.displayName,
            level = level,
            plusLevel = plus,
            nameConfidence = name.line.confidence,
            matchedText = name.line.text,
            levelBounds = read.levelLine,
            nameBounds = name.line,
        )
    }

    private data class NamedLine(val line: OcrStageLine, val match: CatNameMatch)

    private fun bestName(lines: List<OcrStageLine>, index: CatNameIndex): NamedLine? {
        var best: NamedLine? = null
        for (line in lines) {
            if (line.confidence < MIN_NAME_CONFIDENCE) continue
            val match = index.resolve(line.text) ?: continue
            val candidate = NamedLine(line, match)
            if (isBetter(candidate, best)) best = candidate
        }
        return best
    }

    /**
     * Picks the cat the player is actually looking at.
     *
     * Several cat names are on screen at once — the Upgrade screen shows the neighbours on either
     * side, and Organize Cats shows a whole row — and choosing by confidence alone returned the cat
     * two slots to the right. The selected card prints its name in a larger font, so the tallest box
     * wins; confidence and exactness only break ties.
     */
    private fun isBetter(candidate: NamedLine, current: NamedLine?): Boolean {
        if (current == null) return true
        val candidateHeight = candidate.line.bottom - candidate.line.top
        val currentHeight = current.line.bottom - current.line.top
        if (candidateHeight > currentHeight + HEIGHT_EPSILON) return true
        if (currentHeight > candidateHeight + HEIGHT_EPSILON) return false
        if (candidate.match.exact != current.match.exact) return candidate.match.exact
        if (candidate.line.confidence != current.line.confidence) {
            return candidate.line.confidence > current.line.confidence
        }
        return candidate.match.entry.displayName.length > current.match.entry.displayName.length
    }

    private data class LevelRead(val level: Int?, val plus: Int?, val levelLine: OcrStageLine? = null)

    private fun readLevel(lines: List<OcrStageLine>, nameLine: OcrStageLine): LevelRead {
        // A single detected line can hold both, e.g. `Max Level 38 +1`, so split before parsing.
        // Only levels the game could actually print; a stray `0` is a fragment, not a level.
        val levels = lines.mapNotNull { line -> splitLevelAndPlus(line.text).first?.let { it to line } }
            .filter { (value, _) -> value in CatLevelCurve.MIN_LEVEL..CatLevelCurve.MAX_LEVEL }
        val pluses = lines.mapNotNull { line -> splitLevelAndPlus(line.text).second?.let { it to line } }

        // Anchoring on the word "Level" proved unreliable: it comes back as `eaxe 20`, `Leve1 20` or
        // not at all. The card's own geometry is steadier — the level prints below the name and in
        // the same column, while the neighbouring cats' levels sit further out.
        val level = levelNearName(levels, nameLine)
            ?: pick(levels, lines.filter { isLevelAnchor(it.text) })
            ?: levelAbovePlus(levels, pluses)
            ?: nearestNumberBelow(levels, nameLine)

        val plus = pluses.minByOrNull { (_, line) ->
            val reference = level?.second
            if (reference == null) 0f else verticalDistance(reference, line)
        }?.let { (value, line) ->
            val reference = level?.second
            when {
                reference == null -> value
                verticalDistance(reference, line) <= MAX_LEVEL_DISTANCE -> value
                else -> null
            }
        }
        return LevelRead(level?.first, plus, level?.second)
    }

    /**
     * Splits `Max Level 38 +1` into level 38 and plus 1. The detector merges the label, the number and
     * the plus into one line often enough that parsing the whole string lost the level entirely.
     */
    internal fun splitLevelAndPlus(text: String): Pair<Int?, Int?> {
        val plusAt = text.indexOf('+')
        if (plusAt < 0) return parseLevel(text) to null
        val before = text.substring(0, plusAt)
        val after = text.substring(plusAt + 1).trim().take(2)
        val plus = parsePlus("+$after")
        val level = parseLevel(before) ?: parseLevel(text.substring(0, plusAt).trim())
        return level to plus
    }

    /** Last resort: the biggest plausible number under the name, whatever column it is in. */
    private fun nearestNumberBelow(
        levels: List<Pair<Int, OcrStageLine>>,
        nameLine: OcrStageLine,
    ): Pair<Int, OcrStageLine>? {
        val nameCentreY = (nameLine.top + nameLine.bottom) / 2f
        return levels
            .filter { (_, line) ->
                val centre = (line.top + line.bottom) / 2f
                val below = centre - nameCentreY
                below > 0f && below <= MAX_LEVEL_BELOW
            }
            .minByOrNull { (_, line) -> (line.top + line.bottom) / 2f - nameCentreY }
    }

    /** The level of the card the name belongs to: below the name, same column, closest wins. */
    private fun levelNearName(
        levels: List<Pair<Int, OcrStageLine>>,
        nameLine: OcrStageLine,
    ): Pair<Int, OcrStageLine>? {
        val nameCentreY = (nameLine.top + nameLine.bottom) / 2f
        val nameCentreX = (nameLine.left + nameLine.right) / 2f
        var best: Pair<Int, OcrStageLine>? = null
        var bestScore = Float.MAX_VALUE
        for ((value, line) in levels) {
            val centreY = (line.top + line.bottom) / 2f
            val centreX = (line.left + line.right) / 2f
            val below = centreY - nameCentreY
            val across = kotlin.math.abs(centreX - nameCentreX)
            if (below <= 0f || below > MAX_LEVEL_BELOW) continue
            if (across > MAX_LEVEL_ACROSS) continue
            val score = across * 2f + below
            if (score < bestScore) {
                bestScore = score
                best = value to line
            }
        }
        return best
    }

    /** The number printed immediately above a `+N` plus level. */
    private fun levelAbovePlus(
        levels: List<Pair<Int, OcrStageLine>>,
        pluses: List<Pair<Int, OcrStageLine>>,
    ): Pair<Int, OcrStageLine>? {
        if (pluses.isEmpty() || levels.isEmpty()) return null
        var best: Pair<Int, OcrStageLine>? = null
        var bestGap = Float.MAX_VALUE
        for ((_, plusLine) in pluses) {
            val plusCentre = (plusLine.top + plusLine.bottom) / 2f
            for ((value, line) in levels) {
                val gap = plusCentre - (line.top + line.bottom) / 2f
                // Only numbers above the plus, and close enough to belong to the same card.
                if (gap <= 0f || gap > MAX_PLUS_GAP) continue
                if (gap < bestGap) {
                    bestGap = gap
                    best = value to line
                }
            }
        }
        return best
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
        // A bare number first, so glyph confusions (`l0` -> 10) are honoured before anything else.
        parseDigits(trimmed)?.let { return it }
        // `+90` is a plus level, never the level itself.
        if (trimmed.startsWith("+")) return null
        // Otherwise the number is the last whitespace-separated token. The label is mangled in ways
        // no pattern survives — `Lexe1 45`, `eaxe 20`, `Leave 50` — but the digits stay intact.
        val last = trimmed.substringAfterLast(' ').trim()
        if (last.length in 1..3 && last.all { it.isDigit() }) return last.toIntOrNull()
        return null
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
