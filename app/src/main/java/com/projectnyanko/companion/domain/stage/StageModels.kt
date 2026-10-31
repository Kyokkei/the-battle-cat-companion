package com.projectnyanko.companion.domain.stage

/** Locale used to match the stage names shown by the selected game client. */
enum class StageLocale(val column: String) {
    EN("en"),
    JP("jp"),
    TW("tw"),
    KR("kr"),
}

data class StageRecord(
    val id: Long,
    val sourceId: String,
    val localizedNames: Map<StageLocale, List<String>>,
    val baseHealth: Long?,
    val energy: Long?,
    val length: Long?,
    val difficulty: String,
    val enemySpawns: List<EnemySpawn>,
    /**
     * Stages this one can spawn on clear — the game's "EX" stages. Clearing the carrier rolls each
     * of them at its own [ExStageRef.chance]; the game itself never tells the player the odds.
     */
    val exStages: List<ExStageRef> = emptyList(),
) {
    fun names(locale: StageLocale): List<String> = localizedNames[locale].orEmpty()
}

/** One link in a continuation run: the stage, its odds, and how deep into the chain it sits. */
data class ExStageStep(
    val stage: StageRecord,
    val chance: Int?,
    val depth: Int,
)

/** One entry from the stage file's `ex_stage` column. */
data class ExStageRef(
    /** Stage file id of the target, or null when the row only records the odds. */
    val sourceId: String?,
    /** Percent, when the file records one. */
    val chance: Int?,
)

data class EnemySpawn(
    val enemyId: Int,
    val count: Long?,
    val initialSpawn: Long?,
    val interval: Long?,
    val maximum: Long?,
    val probability: Long?,
    val bossFlag: Long?,
    val magnification: Long?,
    val rawFields: List<String>,
) {
    val hasIcon: Boolean = enemyId >= 0
}

data class StageCategory(
    val nameByLocale: Map<StageLocale, String>,
    val group: Int?,
    val mapIds: Set<Long>,
)

/**
 * A named run of stages that the local stage file only labels by entry (`Floor 42`), so the same
 * label exists once per tower. [firstStageId] and [lastStageId] are inclusive decoded stage IDs.
 */
data class StageSeries(
    val name: String,
    val firstStageId: Long,
    val lastStageId: Long,
) {
    fun contains(stageId: Long): Boolean = stageId in firstStageId..lastStageId
}

data class StageCatalogMetadata(
    val stageCount: Int,
    val translationRowCount: Int,
    val enemySpriteCount: Int,
    val missingEnemySpriteIds: Set<Int>,
    val source: String,
    val attribution: String,
    val license: String,
    val hashes: Map<String, String>,
)

data class StageCatalog(
    val stages: List<StageRecord>,
    val categories: List<StageCategory>,
    val metadata: StageCatalogMetadata,
    val series: List<StageSeries> = emptyList(),
    /** Map index (`stage.id / 1000`) to chapter label, supplied by the user. */
    val chapters: Map<Int, String> = emptyMap(),
    /** Label for one stage, for maps that span several chapters. Keyed by decoded stage id. */
    val stageChapters: Map<Long, String> = emptyMap(),
) {
    private val stagesById = stages.associateBy(StageRecord::id)

    private val stagesBySourceId = stages.associateBy(StageRecord::sourceId)

    /** Reverse of [StageRecord.exStages]: which stage you have to clear to see this one. */
    private val carrierByExId: Map<String, StageRecord> = buildMap {
        for (stage in stages) {
            for (ref in stage.exStages) ref.sourceId?.let { putIfAbsent(it, stage) }
        }
    }

    fun findById(id: Long): StageRecord? = stagesById[id]

    /** Lookup for the `ex_stage` links, which reference the stage file's own string ids. */
    fun findBySourceId(sourceId: String): StageRecord? = stagesBySourceId[sourceId]

    /**
     * The stage that can spawn [stage] on clear, when [stage] is itself an EX stage.
     *
     * An EX stage is a normal row in the file with no energy of its own, so without this the card
     * cannot tell the player why they cannot just play it.
     */
    fun carrierOf(stage: StageRecord): StageRecord? = carrierByExId[stage.sourceId]

    /**
     * The EX / continuation stages [stage] can drop into on clear, with whatever odds are known.
     *
     * Only explicit links count. An earlier version fell back to zero-energy stages named
     * `<carrier> (Hard)` and friends, which looked convincing and was wrong: those are rows from a
     * reworked event that PONOS removed from the game, still sitting in the bundled snapshot. Rows
     * the snapshot cannot express are corrected in `stage.tsv` itself instead of being guessed here.
     */
    fun exStageTargets(stage: StageRecord): List<Pair<StageRecord, Int?>> =
        stage.exStages.mapNotNull { ref ->
            val target = ref.sourceId?.let(::findBySourceId) ?: return@mapNotNull null
            target to ref.chance
        }

    /**
     * The whole continuation run, breadth first: level 1 is what this stage can roll into, level 2 is
     * what *those* roll into, and so on.
     *
     * The Proving Grounds chains four deep (First Round → 2nd → 3rd → 4th → Last, each with a Dawn
     * and a Dusk branch), which is why the card has to lay this out as a list rather than a tap.
     */
    fun exStageChain(stage: StageRecord, maxDepth: Int = 5): List<ExStageStep> {
        val out = ArrayList<ExStageStep>()
        val seen = hashSetOf(stage.id)
        var frontier = listOf(stage)
        var depth = 1
        while (frontier.isNotEmpty() && depth <= maxDepth) {
            val next = ArrayList<StageRecord>()
            for (parent in frontier) {
                for ((target, chance) in exStageTargets(parent)) {
                    if (!seen.add(target.id)) continue
                    out += ExStageStep(target, chance, depth)
                    next += target
                }
            }
            frontier = next
            depth++
        }
        return out
    }

    /** Name of the tower or series this stage belongs to, when the local data defines one. */
    fun seriesNameFor(stage: StageRecord): String? =
        series.firstOrNull { it.contains(stage.id) }?.name

    /**
     * Chapter this stage belongs to, e.g. `Cats of the Cosmos Ch.3 (Outbreak)`.
     *
     * The stage file only stores a map index in the id, so this is what tells eleven identically
     * named `South Africa` records apart. Null for maps the user has not labelled.
     */
    fun chapterNameFor(stage: StageRecord): String? =
        stageChapters[stage.id] ?: chapters[(stage.id / CHAPTER_ID_STRIDE).toInt()]

    private companion object {
        const val CHAPTER_ID_STRIDE = 1000L
    }

    fun findCandidates(text: String, locale: StageLocale): List<StageRecord> {
        val query = normalizeStageText(text)
        if (query.isEmpty()) return emptyList()
        val matches = stages.filter { stage ->
            stage.names(locale).any { name ->
                val normalizedName = normalizeStageText(name)
                val shorterLength = minOf(query.length, normalizedName.length)
                val longerLength = maxOf(query.length, normalizedName.length)
                normalizedName == query ||
                    (shorterLength >= 4 && shorterLength.toFloat() / longerLength >= 0.7f &&
                        (query.contains(normalizedName) || normalizedName.contains(query))) ||
                    isOcrTypoMatch(query, normalizedName)
            }
        }
        return preferMatchingNumber(query, matches, locale)
    }

    /**
     * Story areas and tower floors are told apart by their number, and the number is the part of a
     * card the recognizer gets right. Fuzzy matching two edits away in the letters is a plausible
     * misread, but the same read with a different number is a different stage: "Downpour Jungle
     * Area 16" also pulled in Areas 11-15, and "Floor 42" pulled in Floor 4. So when a candidate
     * carries exactly the numbers that were read, the others are dropped.
     */
    private fun preferMatchingNumber(
        query: String,
        matches: List<StageRecord>,
        locale: StageLocale,
    ): List<StageRecord> {
        val digits = query.filter(Char::isDigit)
        if (digits.isEmpty()) return matches
        val sameNumber = matches.filter { stage ->
            stage.names(locale).any { name -> normalizeStageText(name).filter(Char::isDigit) == digits }
        }
        return sameNumber.ifEmpty { matches }
    }
}

private fun isOcrTypoMatch(left: String, right: String): Boolean {
    val maximumLength = maxOf(left.length, right.length)
    // Four, not five: `Moon` and `Mars` are real stage names, and at five a single misread glyph
    // (`Mcon`) made them unmatchable, which is why the final stage of a chapter could not be read.
    if (minOf(left.length, right.length) < 4 || kotlin.math.abs(left.length - right.length) > 3) return false
    if (isTruncatedNumber(left, right)) return false
    val maximumEdits = maxOf(1, (maximumLength * 0.24f).toInt())
    var previous = IntArray(right.length + 1) { it }
    for (leftIndex in left.indices) {
        val current = IntArray(right.length + 1)
        current[0] = leftIndex + 1
        var rowMinimum = current[0]
        for (rightIndex in right.indices) {
            val substitution = previous[rightIndex] + if (left[leftIndex] == right[rightIndex]) 0 else 1
            current[rightIndex + 1] = minOf(
                substitution,
                previous[rightIndex + 1] + 1,
                current[rightIndex] + 1,
            )
            rowMinimum = minOf(rowMinimum, current[rightIndex + 1])
        }
        if (rowMinimum > maximumEdits) return false
        previous = current
    }
    return previous[right.length] <= maximumEdits
}

/**
 * `floor4` against `floor42`: the shorter name is the longer one cut off inside its number, which is
 * how a partially read card looks, not how a misread glyph looks. Without this, every tower floor
 * also offered the floor one digit shorter.
 */
private fun isTruncatedNumber(left: String, right: String): Boolean {
    val shorter = if (left.length <= right.length) left else right
    val longer = if (left.length <= right.length) right else left
    if (shorter.length == longer.length || !longer.startsWith(shorter)) return false
    return longer.substring(shorter.length).all(Char::isDigit)
}

fun normalizeStageText(value: String): String = buildString(value.length) {
    value.lowercase().forEach { char ->
        if (char.isLetterOrDigit()) append(char)
    }
}.replaceFirst(INITIAL_AI_OCR_CONFUSION, "ai")

private val INITIAL_AI_OCR_CONFUSION = Regex("^40(?=[a-z])")
