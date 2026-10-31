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
            .toList()
        // Keep the un-deduplicated list: when the centred card is misread as its neighbour, both
        // occurrences carry the same stage id, and de-duplication would drop the one whose position
        // says it is the middle of the carousel.
        val occurrences = candidates
        val deduped = occurrences.distinctBy { it.stage.id }
        // The selected card's label is drawn yellow-on-white with a heavy outline, and the recognizer
        // reads it as `Feathergeddon II` when it says `III` — one stroke lost. No amount of matching
        // recovers a wrong read, so the neighbours decide instead: the carousel is an ordered list,
        // and the card between two known stages is the one that sits between them in the catalog.
        val inferred = inferFromNeighbours(catalog, occurrences)
        if (inferred == null) return StageMatchResult(deduped)
        val anchor = deduped.minByOrNull { kotlin.math.abs(it.centerX - 0.5f) }
        val withInferred = if (deduped.none { it.stage.id == inferred.id } && anchor != null) {
            deduped + anchor.copy(stage = inferred)
        } else {
            deduped
        }
        val likely = withInferred.firstOrNull { it.stage.id == inferred.id }
            ?: StageMatchResult(withInferred).likelySelected
        return StageMatchResult(withInferred, likely)
    }

    /**
     * The stage that must be the selected card, worked out from its neighbours.
     *
     * A stage-select carousel draws a run of consecutive stages. If the card left of centre matches
     * stage *n* and the card right of centre matches stage *n + 2*, the card between them can only be
     * *n + 1* — which is how `Feathergeddon III` gets found even when its own label is misread as
     * `II`, a stage that is already sitting at *n*.
     *
     * Deliberately strict: it returns nothing unless both neighbours resolved and the catalog gap is
     * exactly two. A wrong inference here would be worse than no inference, because the card is
     * presented as the likely selection.
     */
    internal fun inferFromNeighbours(
        catalog: StageCatalog,
        candidates: List<StageCandidate>,
    ): StageRecord? {
        if (candidates.size < 3) return null
        val ordered = candidates.sortedBy { it.centerX }
        val centre = ordered.indices.minByOrNull { kotlin.math.abs(ordered[it].centerX - 0.5f) } ?: return null
        if (centre == 0 || centre == ordered.lastIndex) return null
        val stages = catalog.stages
        val leftIndex = stages.indexOfFirst { it.id == ordered[centre - 1].stage.id }
        val rightIndex = stages.indexOfFirst { it.id == ordered[centre + 1].stage.id }
        if (leftIndex < 0 || rightIndex != leftIndex + 2) return null
        return stages[leftIndex + 1]
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
