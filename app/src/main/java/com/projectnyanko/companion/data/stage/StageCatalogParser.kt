package com.projectnyanko.companion.data.stage

import com.projectnyanko.companion.domain.stage.EnemySpawn
import com.projectnyanko.companion.domain.stage.ExStageRef
import com.projectnyanko.companion.domain.stage.StageCatalog
import com.projectnyanko.companion.domain.stage.StageCatalogMetadata
import com.projectnyanko.companion.domain.stage.StageCategory
import com.projectnyanko.companion.domain.stage.StageLocale
import com.projectnyanko.companion.domain.stage.StageRecord
import com.projectnyanko.companion.domain.stage.StageSeries
import org.json.JSONObject
import java.security.MessageDigest

/** Parses the locally supplied Battle Cats stage files without network access. */
object StageCatalogParser {
    private val stageRequiredColumns = setOf("id", "name_tw", "name_jp", "health", "energy", "len", "enemy_lines")
    private val translationRequiredColumns = StageLocale.entries.mapTo(mutableSetOf()) { it.column }

    fun parse(
        stageTsv: ByteArray,
        translationTsv: ByteArray,
        schemeJson: ByteArray,
        enemySpriteIds: Set<Int>,
        source: String,
        attribution: String,
        license: String,
        towerLabelsJson: ByteArray? = null,
        chapterLabelsJson: ByteArray? = null,
    ): StageCatalog {
        val translations = parseTranslations(translationTsv)
        val stages = parseStages(stageTsv, translations)
        val scheme = JSONObject(schemeJson.toString(Charsets.UTF_8))
        val categories = parseCategories(scheme)
        val series = towerLabelsJson?.let { parseSeries(it) }.orEmpty()
        val chapterLabels = chapterLabelsJson?.let { parseChapters(it) }
        val chapters = chapterLabels?.byMap.orEmpty()
        val stageChapters = chapterLabels?.byStage.orEmpty()
        val missingSprites = stages.asSequence()
            .flatMap { it.enemySpawns.asSequence() }
            .map(EnemySpawn::enemyId)
            .filterNot(enemySpriteIds::contains)
            .toSet()

        return StageCatalog(
            stages = stages,
            categories = categories,
            series = series,
            chapters = chapters,
            stageChapters = stageChapters,
            metadata = StageCatalogMetadata(
                stageCount = stages.size,
                translationRowCount = translations.size,
                enemySpriteCount = enemySpriteIds.size,
                missingEnemySpriteIds = missingSprites,
                source = source,
                attribution = attribution,
                license = license,
                hashes = mapOf(
                    "stage.tsv" to sha256(stageTsv),
                    "tstage.tsv" to sha256(translationTsv),
                    "stage_scheme.json" to sha256(schemeJson),
                ),
            ),
        )
    }

    private fun parseStages(stageTsv: ByteArray, translations: List<Map<StageLocale, String>>): List<StageRecord> {
        val rows = readTsv(stageTsv)
        require(rows.isNotEmpty()) { "stage.tsv has no header." }
        val header = rows.first().toSet()
        require(header.containsAll(stageRequiredColumns)) { "stage.tsv is missing required columns." }
        val index = rows.first().withIndex().associate { it.value to it.index }
        val seen = HashSet<Long>()
        val translationIndexes = buildTranslationIndexes(translations)

        return rows.drop(1).mapIndexedNotNull { rowIndex, row ->
            if (row.all(String::isBlank)) return@mapIndexedNotNull null
            require(row.size >= index.size && row.drop(index.size).all(String::isBlank)) {
                "stage.tsv row ${rowIndex + 2} has ${row.size} fields; expected ${index.size} plus optional empty trailing fields."
            }
            val normalizedRow = row.take(index.size)
            val sourceId = value(normalizedRow, index, "id")
            val id = sourceId.toLongOrNull(36)
                ?: throw IllegalArgumentException("Invalid base-36 stage ID '$sourceId' on row ${rowIndex + 2}.")
            require(seen.add(id)) { "Duplicate stage ID '$sourceId'." }

            val jpName = value(normalizedRow, index, "name_jp").trim()
            val twName = value(normalizedRow, index, "name_tw").trim()
            val matchingTranslations = translationsFor(jpName, twName, translationIndexes)
            val localizedNames = StageLocale.entries.associateWith { locale ->
                buildList {
                    when (locale) {
                        StageLocale.JP -> if (jpName.isNotEmpty()) add(jpName)
                        StageLocale.TW -> if (twName.isNotEmpty()) add(twName)
                        else -> Unit
                    }
                    matchingTranslations.mapNotNullTo(this) { it[locale]?.trim()?.takeIf(String::isNotEmpty) }
                }.distinct()
            }.filterValues(List<String>::isNotEmpty)

            StageRecord(
                id = id,
                sourceId = sourceId,
                localizedNames = localizedNames,
                baseHealth = parseDecimal(value(normalizedRow, index, "health")),
                energy = parseBase36(value(normalizedRow, index, "energy")),
                length = parseDecimal(value(normalizedRow, index, "len")),
                difficulty = index["difficulty"]?.let { normalizedRow[it] }.orEmpty(),
                enemySpawns = parseEnemyLines(value(normalizedRow, index, "enemy_lines"), rowIndex + 2),
                exStages = parseExStages(value(normalizedRow, index, "ex_stage")),
            )
        }
    }

    /**
     * Reads the `ex_stage` column.
     *
     * `$2enlk|3` is one EX stage at 3%, `$2ergg,2eqoq|50,50` is two at 50% each. 194 rows still use
     * the older `100,032,00,01$` form: the odds are the first field, but the rest indexes an EX table
     * this build does not ship, so those keep the chance and lose the name.
     */
    internal fun parseExStages(raw: String): List<ExStageRef> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        if (!text.startsWith("$")) {
            val chance = text.substringBefore(',').trim().toIntOrNull()
            return if (chance != null && chance in 1..100) listOf(ExStageRef(null, chance)) else emptyList()
        }
        val body = text.removePrefix("$")
        val ids = body.substringBefore('|').split(',').map(String::trim).filter(String::isNotEmpty)
        if (ids.isEmpty()) return emptyList()
        val chances = body.substringAfter('|', "").split(',').map { it.trim().toIntOrNull() }
        return ids.mapIndexed { position, id -> ExStageRef(id, chances.getOrNull(position)) }
    }

    /**
     * Finds the translation rows for one stage.
     *
     * Some blocks of `tstage.tsv` list the Japanese and Traditional-Chinese names in the opposite
     * columns from `stage.tsv`, so an exact (jp, tw) pair lookup misses and the stage ends up with no
     * English name at all — 1,221 of the 6,328 local stages before this fallback, including every
     * Cat of the Cosmos chapter the OCR reads correctly (`Urura`, `Thanxbye`, `Aguham`, `Somolon`).
     * An English name that does not exist cannot be matched, so the join now also tries the reversed
     * pair and then each name on its own, and takes the first source that actually carries English.
     */
    private fun translationsFor(
        jpName: String,
        twName: String,
        indexes: TranslationIndexes,
    ): List<Map<StageLocale, String>> {
        if (jpName.isEmpty() && twName.isEmpty()) return emptyList()
        val jpBare = stripChapterSuffix(jpName)
        val twBare = stripChapterSuffix(twName)
        val candidates = buildList {
            if (jpName.isNotEmpty() && twName.isNotEmpty()) {
                add(indexes.byPair[jpName to twName].orEmpty())
                add(indexes.byPair[twName to jpName].orEmpty())
            }
            if (jpName.isNotEmpty()) add(indexes.jp[jpName].orEmpty())
            if (twName.isNotEmpty()) add(indexes.tw[twName].orEmpty())
            // `西表島 第1章` / `月球 第1章` carry a chapter number the translation table does not:
            // without this, the three Empire of Cats Moon stages resolved to no English name at all
            // and were listed under their Japanese collab label instead of `Moon`.
            if (jpBare != jpName || twBare != twName) {
                if (jpBare.isNotEmpty() && twBare.isNotEmpty()) {
                    add(indexes.byPair[jpBare to twBare].orEmpty())
                    add(indexes.byPair[twBare to jpBare].orEmpty())
                }
                if (jpBare.isNotEmpty()) add(indexes.jp[jpBare].orEmpty())
                if (twBare.isNotEmpty()) add(indexes.tw[twBare].orEmpty())
            }
        }
        return candidates.firstOrNull { rows -> rows.any { it[StageLocale.EN] != null } }
            ?: candidates.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    private fun stripChapterSuffix(name: String): String = name.replace(CHAPTER_SUFFIX, "").trim()

    private fun parseTranslations(bytes: ByteArray): List<Map<StageLocale, String>> {
        val rows = readTsv(bytes)
        require(rows.isNotEmpty()) { "tstage.tsv has no header." }
        require(rows.first().toSet().containsAll(translationRequiredColumns)) {
            "tstage.tsv must include jp, tw, en and kr columns."
        }
        val indexes = rows.first().withIndex().associate { it.value to it.index }
        return rows.drop(1).mapIndexedNotNull { rowIndex, row ->
            if (row.all(String::isBlank)) return@mapIndexedNotNull null
            val trimmed = row.dropLastWhile { row.size > indexes.size && it.isBlank() }
            val normalizedRow = when {
                trimmed.size == indexes.size -> trimmed
                trimmed.size > indexes.size && trimmed.subList(indexes.size - 1, trimmed.lastIndex).all(String::isBlank) ->
                    trimmed.take(indexes.size - 1) + trimmed.last()
                else -> throw IllegalArgumentException("tstage.tsv row ${rowIndex + 2} has an invalid field count.")
            }
            StageLocale.entries.mapNotNull { locale ->
                normalizedRow[indexes.getValue(locale.column)].trim().takeIf(String::isNotEmpty)?.let { locale to it }
            }.toMap()
        }
    }

    private fun parseEnemyLines(value: String, rowNumber: Int): List<EnemySpawn> = value.split('|')
        .filter(String::isNotBlank)
        .map { line ->
            val fields = line.split(',')
            require(fields.size >= 8) { "stage.tsv row $rowNumber has a malformed enemy line." }
            val enemyId = fields[0].toLongOrNull(36)?.toInt()
                ?: throw IllegalArgumentException("Invalid base-36 enemy ID '${fields[0]}' on stage row $rowNumber.")
            EnemySpawn(
                enemyId = enemyId,
                count = parseEncodedLong(fields[1]),
                initialSpawn = parseEncodedLong(fields[2]),
                interval = parseEncodedLong(fields[3]),
                maximum = parseEncodedLong(fields[4]),
                probability = parseEncodedLong(fields[5]),
                bossFlag = parseEncodedLong(fields[6]),
                magnification = parseEncodedLong(fields[7]),
                rawFields = fields,
            )
        }

    /** Reads the user-supplied tower/series ranges that give identical floor labels a name. */
    private fun parseSeries(json: ByteArray): List<StageSeries> {
        val root = JSONObject(json.toString(Charsets.UTF_8))
        val groups = root.optJSONArray("groups") ?: return emptyList()
        return buildList {
            for (index in 0 until groups.length()) {
                val item = groups.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val first = item.optLong("firstStageId", -1)
                val last = item.optLong("lastStageId", -1)
                if (name.isEmpty() || first < 0 || last < first) continue
                add(StageSeries(name = name, firstStageId = first, lastStageId = last))
            }
        }
    }

    /**
     * Chapter labels, keyed either by map index (`3000`) or by a single stage (`3000:47`).
     *
     * A map-wide key is the common case. The per-stage form exists because map 3000 holds all three
     * Empire of Cats chapters — they share stages 0-46 — but their three Moons sit at 47, 49 and 50
     * and each belongs to a different chapter.
     */
    private fun parseChapters(json: ByteArray): ChapterLabels {
        val root = JSONObject(json.toString(Charsets.UTF_8))
        val chapters = root.optJSONObject("chapters") ?: return ChapterLabels(emptyMap(), emptyMap())
        val byMap = HashMap<Int, String>()
        val byStage = HashMap<Long, String>()
        chapters.keys().forEach { key ->
            val name = chapters.optString(key).trim()
            if (name.isEmpty()) return@forEach
            val separator = key.indexOf(':')
            if (separator < 0) {
                key.toIntOrNull()?.let { byMap[it] = name }
            } else {
                val map = key.substring(0, separator).toIntOrNull() ?: return@forEach
                val stageIndex = key.substring(separator + 1).toIntOrNull() ?: return@forEach
                byStage[map.toLong() * 1000L + stageIndex] = name
            }
        }
        return ChapterLabels(byMap, byStage)
    }

    private data class ChapterLabels(val byMap: Map<Int, String>, val byStage: Map<Long, String>)

    private fun parseCategories(scheme: JSONObject): List<StageCategory> {        val categories = scheme.optJSONObject("categories") ?: return emptyList()
        val defaultCategories = categories.optJSONArray("default") ?: return emptyList()
        return buildList {
            for (index in 0 until defaultCategories.length()) {
                val item = defaultCategories.optJSONObject(index) ?: continue
                val names = StageLocale.entries.mapNotNull { locale ->
                    item.optString("name_${locale.column}").takeIf(String::isNotBlank)?.let { locale to it }
                }.toMap()
                val mapIds = item.optJSONArray("maps")?.let { maps ->
                    buildSet {
                        for (mapIndex in 0 until maps.length()) {
                            maps.optLong(mapIndex, -1).takeIf { it >= 0 }?.let(::add)
                        }
                    }
                }.orEmpty()
                add(
                    StageCategory(
                        nameByLocale = names,
                        group = item.optInt("group").takeIf { item.has("group") },
                        mapIds = mapIds,
                    ),
                )
            }
        }
    }

    private fun buildTranslationIndexes(
        translations: List<Map<StageLocale, String>>,
    ): TranslationIndexes {
        val jp = HashMap<String, MutableList<Map<StageLocale, String>>>()
        val tw = HashMap<String, MutableList<Map<StageLocale, String>>>()
        val byPair = HashMap<Pair<String, String>, MutableList<Map<StageLocale, String>>>()
        translations.forEach { row ->
            val jpName = row[StageLocale.JP]
            val twName = row[StageLocale.TW]
            jpName?.let { jp.getOrPut(it, ::mutableListOf).add(row) }
            twName?.let { tw.getOrPut(it, ::mutableListOf).add(row) }
            if (!jpName.isNullOrBlank() && !twName.isNullOrBlank()) {
                byPair.getOrPut(jpName to twName, ::mutableListOf).add(row)
            }
        }
        return TranslationIndexes(jp, tw, byPair)
    }

    private fun readTsv(bytes: ByteArray): List<List<String>> = bytes.toString(Charsets.UTF_8)
        .lineSequence()
        .filter(String::isNotEmpty)
        .map { it.removeSuffix("\r").split('\t') }
        .toList()

    private fun value(row: List<String>, indexes: Map<String, Int>, name: String): String =
        row[indexes.getValue(name)]

    /**
     * Energy is stored base 36, like the ids: `2v` is 103 and `28` is 80.
     *
     * Decoding it as decimal first looked harmless and silently halved the cost of 1,446 of the
     * 6,328 stages — every value that happened to contain only digits. It surfaced when the three
     * Into the Future Moons read 28/90/100 instead of 80/90/100.
     */
    private fun parseBase36(value: String): Long? = value.trim().takeIf(String::isNotEmpty)?.let {
        it.toLongOrNull(36) ?: it.toLongOrNull()
    }

    /** Base HP and stage length are stored in plain decimal (`7000`, `450000`, `4000`). */
    private fun parseDecimal(value: String): Long? = value.trim().takeIf(String::isNotEmpty)?.let {
        it.toLongOrNull() ?: it.toLongOrNull(36)
    }

    /** Enemy rows mix both: probability is decimal, magnification is base 36. */
    private fun parseEncodedLong(value: String): Long? = parseDecimal(value)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private val CHAPTER_SUFFIX = Regex("第[0-9０-９]+章$")

    private data class TranslationIndexes(
        val jp: Map<String, List<Map<StageLocale, String>>>,
        val tw: Map<String, List<Map<StageLocale, String>>>,
        val byPair: Map<Pair<String, String>, List<Map<StageLocale, String>>>,
    )
}
