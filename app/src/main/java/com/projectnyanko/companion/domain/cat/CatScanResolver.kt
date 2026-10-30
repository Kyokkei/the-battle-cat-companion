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
 * Every distance here is measured in **name heights** rather than screen fractions. The cat's name
 * is the one box whose size tracks the UI scale, so a 20:9 phone, a tablet and a landscape crop all
 * produce the same numbers — that is what lets the same code read the Upgrade card and the Organize
 * Cats panel without a per-screen layout table.
 */
object CatScanResolver {
    /** Below this the recognizer is guessing; a wrong cat is worse than no answer. */
    private const val MIN_NAME_CONFIDENCE = 0.45f

    /** Two boxes within this much height count as the same font size. */
    private const val HEIGHT_EPSILON = 0.002f

    /** How far below the name its level may print, in name heights. */
    private const val MAX_LEVEL_BELOW = 8f

    /** How far sideways the level may sit from the name, in name heights. */
    private const val MAX_LEVEL_ACROSS = 2.5f

    /** How far sideways a `+N` may sit from the name, in name heights. */
    private const val MAX_PLUS_ACROSS = 2.5f

    /** How far a number may sit from the `+N` or the `Max Level` word it belongs to. */
    private const val MAX_BLOCK_GAP = 1.8f

    /**
     * No cat tile prints a level below 20: the number under `Max Level` is the *cap*, and caps start
     * at 20. Anything smaller is a fragment of a two-digit number — `30` arriving as `3` is what
     * turned a maxed Dread Pirate Catley into a level-3 cat with a third of its health.
     */
    private const val MIN_TILE_LEVEL = 20

    /**
     * The label and its number are often merged into one detected line: the Mohawk Cat card reads
     * `Leve1 20`, with the recognizer turning the `l` into a `1`.
     */
    private val LEVEL_IN_LINE = Regex("""leve[l1]\s*(\d{1,3})""", RegexOption.IGNORE_CASE)

    /**
     * A plus level. The game draws `+` as a full-width glyph, which the recognizer returns as the
     * CJK `十` or a full-width `＋` often enough that ignoring those dropped every `+90` on screen.
     */
    private val PLUS_LINE = Regex("""^[+＋十]\s*([0-9OoIl]{1,2})$""")

    /** The same glyphs, for finding a plus inside a merged line. */
    private val PLUS_CHARS = charArrayOf('+', '＋', '十')

    fun resolve(lines: List<OcrStageLine>, index: CatNameIndex): CatScanHit? {
        if (lines.isEmpty()) return null
        val name = bestName(lines, index) ?: return null
        val read = readLevel(lines, name.line)
        return CatScanHit(
            unitId = name.match.entry.unitId,
            formIndex = name.match.entry.formIndex,
            displayName = name.match.entry.displayName,
            level = read.level,
            plusLevel = read.plus,
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
        val nameHeight = (nameLine.bottom - nameLine.top).coerceAtLeast(1e-4f)
        val nameCentreX = (nameLine.left + nameLine.right) / 2f
        val nameCentreY = (nameLine.top + nameLine.bottom) / 2f

        val numbers = ArrayList<Pair<Int, OcrStageLine>>(8)
        val pluses = ArrayList<Pair<Int, OcrStageLine>>(4)
        for (line in lines) {
            if (line === nameLine) continue
            val (value, plus) = splitLevelAndPlus(line.text)
            if (value != null) numbers += value to line
            if (plus != null) pluses += plus to line
        }

        // The plus is the rarest token on a cat screen — one per card — so it is the steadiest
        // anchor: the level is the number printed directly above it, in its own column. That holds
        // on the Upgrade card and on the Organize panel, and it needs no absolute coordinates.
        val plusHere = pluses
            .filter { (_, line) -> across(line, nameCentreX) <= MAX_PLUS_ACROSS * nameHeight }
            .minByOrNull { (_, line) -> across(line, nameCentreX) }

        val abovePlus = plusHere?.let { (_, plusLine) -> numberAbove(numbers, plusLine) }
        val found = abovePlus
            ?: numberUnderLabel(numbers, lines, nameHeight, nameCentreX)
            ?: numberBelowName(numbers, nameHeight, nameCentreX, nameCentreY)

        val levelLine = found?.second
        val level = found?.first?.takeIf { it >= MIN_TILE_LEVEL }

        // The plus must belong to the same card: below the level and in its column, or — when the
        // level was merged into one line with it — on that same row.
        val plus = plusHere?.first ?: pluses.filter { (_, line) ->
            val reference = levelLine ?: return@filter false
            val boxHeight = (reference.bottom - reference.top).coerceAtLeast(1e-4f)
            val below = centreY(line) - centreY(reference)
            below >= -0.2f * boxHeight && below <= MAX_BLOCK_GAP * boxHeight &&
                across(line, centreX(reference)) <= MAX_BLOCK_GAP * boxHeight
        }.minByOrNull { (_, line) ->
            kotlin.math.abs(centreY(line) - centreY(levelLine ?: line))
        }?.first

        return LevelRead(level, plus, if (level != null) levelLine else null)
    }

    /**
     * The number printed immediately above a `+N`. Their boxes nearly touch, so the gap is measured
     * against the number's own height rather than the screen.
     */
    private fun numberAbove(
        numbers: List<Pair<Int, OcrStageLine>>,
        plusLine: OcrStageLine,
    ): Pair<Int, OcrStageLine>? {
        var best: Pair<Int, OcrStageLine>? = null
        var bestGap = Float.MAX_VALUE
        for ((value, line) in numbers) {
            val boxHeight = (line.bottom - line.top).coerceAtLeast(1e-4f)
            val gap = plusLine.top - line.bottom
            if (gap < -0.4f * boxHeight) continue
            if (gap > MAX_BLOCK_GAP * boxHeight) continue
            if (across(line, centreX(plusLine)) > MAX_BLOCK_GAP * boxHeight) continue
            if (gap < bestGap) {
                bestGap = gap
                best = value to line
            }
        }
        return best
    }

    /** The number printed directly under a `Max` / `Level` word, in that word's column. */
    private fun numberUnderLabel(
        numbers: List<Pair<Int, OcrStageLine>>,
        lines: List<OcrStageLine>,
        nameHeight: Float,
        nameCentreX: Float,
    ): Pair<Int, OcrStageLine>? {
        val labels = lines.filter {
            isLevelAnchor(it.text) && across(it, nameCentreX) <= MAX_LEVEL_ACROSS * nameHeight
        }
        if (labels.isEmpty()) return null
        var best: Pair<Int, OcrStageLine>? = null
        var bestScore = Float.MAX_VALUE
        for (label in labels) {
            for ((value, line) in numbers) {
                val boxHeight = (line.bottom - line.top).coerceAtLeast(1e-4f)
                val gap = line.top - label.bottom
                if (gap < -0.6f * boxHeight) continue
                if (gap > MAX_BLOCK_GAP * boxHeight) continue
                val acrossLabel = across(line, centreX(label))
                if (acrossLabel > MAX_BLOCK_GAP * boxHeight) continue
                val score = gap + acrossLabel
                if (score < bestScore) {
                    bestScore = score
                    best = value to line
                }
            }
        }
        return best
    }

    /** The level of the card the name belongs to: below the name, in the name's column. */
    private fun numberBelowName(
        numbers: List<Pair<Int, OcrStageLine>>,
        nameHeight: Float,
        nameCentreX: Float,
        nameCentreY: Float,
    ): Pair<Int, OcrStageLine>? {
        var best: Pair<Int, OcrStageLine>? = null
        var bestScore = Float.MAX_VALUE
        for ((value, line) in numbers) {
            val below = (centreY(line) - nameCentreY) / nameHeight
            val acrossName = across(line, nameCentreX) / nameHeight
            if (below <= 0f || below > MAX_LEVEL_BELOW) continue
            if (acrossName > MAX_LEVEL_ACROSS) continue
            val score = acrossName * 2f + below
            if (score < bestScore) {
                bestScore = score
                best = value to line
            }
        }
        return best
    }

    /**
     * Splits `Max Level 38 +1` into level 38 and plus 1. The detector merges the label, the number and
     * the plus into one line often enough that parsing the whole string lost the level entirely.
     */
    internal fun splitLevelAndPlus(text: String): Pair<Int?, Int?> {
        val plusAt = text.indexOfAny(PLUS_CHARS)
        if (plusAt < 0) return parseLevel(text) to null
        val plusToken = text.substring(plusAt).trim().substringBefore(' ')
        return parseLevel(text.substring(0, plusAt)) to parsePlus(plusToken)
    }

    private fun isLevelAnchor(text: String): Boolean {
        val lowered = text.lowercase().replace(" ", "")
        return lowered.contains("level") || lowered.contains("leve1") || lowered.contains("max")
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
        if (trimmed.startsWith("+") || trimmed.startsWith("十") || trimmed.startsWith("＋")) return null
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

    /**
     * Reads a level block that was cropped on its own, with no cat name inside the crop.
     *
     * Used by the second pass, which re-reads the block at 4x after the full-screen pass missed it.
     * Same rules as [readLevel] minus the name: anchor on the plus, else on the label, else take the
     * only plausible number in the crop.
     */
    fun readLevelBlock(lines: List<OcrStageLine>): Pair<Int?, Int?>? {
        if (lines.isEmpty()) return null
        val numbers = ArrayList<Pair<Int, OcrStageLine>>(6)
        val pluses = ArrayList<Pair<Int, OcrStageLine>>(2)
        for (line in lines) {
            val (value, plus) = splitLevelAndPlus(line.text)
            if (value != null) numbers += value to line
            if (plus != null) pluses += plus to line
        }

        // Biggest plus first: the crop holds one card, so the tallest is the one we cropped for.
        for ((plusValue, plusLine) in pluses.sortedByDescending { it.second.bottom - it.second.top }) {
            numberAbove(numbers, plusLine)?.let { (level, _) ->
                val usable = level.takeIf { it >= MIN_TILE_LEVEL }
                if (usable != null) return usable to plusValue
            }
        }

        val label = lines.filter { isLevelAnchor(it.text) }.minByOrNull { it.top }
        if (label != null) {
            val under = numbers.filter { (value, line) ->
                val boxHeight = (line.bottom - line.top).coerceAtLeast(1e-4f)
                val gap = line.top - label.bottom
                value >= MIN_TILE_LEVEL && gap >= -0.6f * boxHeight &&
                    gap <= MAX_BLOCK_GAP * boxHeight && across(line, centreX(label)) <= MAX_BLOCK_GAP * boxHeight
            }.minByOrNull { (_, line) -> line.top }
            if (under != null) return under.first to pluses.firstOrNull()?.first
        }

        val plausible = numbers.filter { it.first >= MIN_TILE_LEVEL }
        if (plausible.size == 1) return plausible.first().first to pluses.firstOrNull()?.first
        return null
    }

    private fun centreX(line: OcrStageLine): Float = (line.left + line.right) / 2f

    private fun centreY(line: OcrStageLine): Float = (line.top + line.bottom) / 2f

    private fun across(line: OcrStageLine, referenceX: Float): Float =
        kotlin.math.abs(centreX(line) - referenceX)

    /** Outlined game font: O/0 and l/1 are the two the recognizer actually mixes up. */
    private val GLYPH_CONFUSIONS = mapOf('O' to '0', 'o' to '0', 'l' to '1', 'I' to '1')
}
