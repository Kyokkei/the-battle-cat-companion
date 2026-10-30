package com.projectnyanko.companion.domain.stage

/** OCR output in screenshot coordinates. Coordinates are normalized to [0, 1]. */
data class OcrStageLine(
    val text: String,
    val confidence: Float,
    val top: Float,
    val left: Float,
    val bottom: Float,
    val right: Float,
)

data class StageCandidate(
    val stage: StageRecord,
    val matchedText: String,
    val confidence: Float,
    val top: Float,
    val left: Float,
    val bottom: Float,
    val right: Float,
) {
    val centerX: Float
        get() = (left + right) / 2f
}

data class StageMatchResult(
    val candidates: List<StageCandidate>,
    /**
     * The card the player has selected is always the horizontally centred one, so the candidate
     * closest to the centre wins. This used to require exactly one candidate inside a fixed window,
     * which returned nothing as soon as two readable names straddled the centre.
     */
    val likelySelected: StageCandidate? = candidates
        .filter { it.top in 0.0f..MAX_CENTRED_TOP }
        .minByOrNull { kotlin.math.abs(it.centerX - 0.5f) }
        ?.takeIf { kotlin.math.abs(it.centerX - 0.5f) <= MAX_CENTRED_OFFSET },
    val requiresConfirmation: Boolean = true,
) {
    val confirmedStage: StageRecord? = null

    private companion object {
        const val MAX_CENTRED_TOP = 0.62f

        /**
         * Half a carousel spacing. Neighbouring cards sit about 0.17 of the width either side of the
         * centre, so a looser window would suggest a neighbour whenever the centred card itself was
         * not read — exactly the wrong-stage guess the product rules forbid.
         */
        const val MAX_CENTRED_OFFSET = 0.10f
    }
}

/** Finds exact localized text candidates; selection is always confirmed by the user. */
object StageMatcher {
    fun match(
        catalog: StageCatalog,
        lines: List<OcrStageLine>,
        locale: StageLocale,
        minimumOcrConfidence: Float = 0.35f,
    ): StageMatchResult {
        require(minimumOcrConfidence in 0f..1f)
        val candidates = lines.asSequence()
            .filter { it.confidence >= minimumOcrConfidence && it.text.isNotBlank() }
            .flatMap { line ->
                catalog.findCandidates(line.text, locale).asSequence().map { stage ->
                    StageCandidate(
                        stage = stage,
                        matchedText = line.text,
                        confidence = line.confidence,
                        top = line.top,
                        left = line.left,
                        bottom = line.bottom,
                        right = line.right,
                    )
                }.toList().let { occurrences -> preferExactName(occurrences, locale) }.asSequence()
            }
            .sortedWith(compareByDescending<StageCandidate> { it.confidence }.thenBy { it.top }.thenBy { it.left })
            .distinctBy { it.stage.id }
            .toList()
        return StageMatchResult(candidates)
    }

    /**
     * Keeps only the stages whose name is exactly what the card said when such a stage exists.
     *
     * Older catalogues still carry the removed difficulty tiers of a stage (`Violet Flash (Hard)`,
     * `Violet Flash (Expert)`, ...), and fuzzy matching surfaced all of them as separate buttons for
     * a screen that only has one stage. If the read text equals a stage name outright, the longer
     * near-matches are discarded; otherwise every fuzzy match is left for the user to choose.
     */
    private fun preferExactName(occurrences: List<StageCandidate>, locale: StageLocale): List<StageCandidate> {
        if (occurrences.size < 2) return occurrences
        val exact = occurrences.filter { candidate ->
            val read = normalizeStageText(candidate.matchedText)
            candidate.stage.names(locale).any { normalizeStageText(it) == read }
        }
        return exact.ifEmpty { occurrences }
    }
}
