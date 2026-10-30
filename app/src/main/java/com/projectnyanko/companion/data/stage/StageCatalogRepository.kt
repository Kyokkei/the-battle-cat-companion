package com.projectnyanko.companion.data.stage

import android.content.Context
import com.projectnyanko.companion.domain.stage.StageCatalog
import org.json.JSONObject

/** Loads the supplied local TSV/JSON catalog once; it never contacts a server. */
class StageCatalogRepository(context: Context) {
    private val assets = context.applicationContext.assets
    @Volatile
    private var cachedCatalog: StageCatalog? = null

    fun getCatalog(): StageCatalog = cachedCatalog ?: synchronized(this) {
        cachedCatalog ?: loadCatalog().also { cachedCatalog = it }
    }

    private fun loadCatalog(): StageCatalog {
        val stageBytes = assets.open("stages/stage.tsv").use { it.readBytes() }
        val translationBytes = assets.open("stages/tstage.tsv").use { it.readBytes() }
        val schemeBytes = assets.open("stages/stage_scheme.json").use { it.readBytes() }
        val sourceManifest = assets.open("stages/source-manifest.json").use { it.readBytes() }
        val towerLabels = runCatching { assets.open("stages/tower_labels.json").use { it.readBytes() } }.getOrNull()
        val chapterLabels = runCatching { assets.open("stages/chapter_labels.json").use { it.readBytes() } }.getOrNull()
        val provenance = JSONObject(sourceManifest.toString(Charsets.UTF_8))
        val enemyIds = assets.list("enemies").orEmpty().mapNotNull { name ->
            ENEMY_SPRITE_PATTERN.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
        }.toSet()
        return StageCatalogParser.parse(
            stageTsv = stageBytes,
            translationTsv = translationBytes,
            schemeJson = schemeBytes,
            enemySpriteIds = enemyIds,
            source = provenance.optString("source", "User-supplied local stage data"),
            attribution = provenance.optString("attribution", "User-supplied local data"),
            license = provenance.optString("license", "Source license not recorded"),
            towerLabelsJson = towerLabels,
            chapterLabelsJson = chapterLabels,
        )
    }

    private companion object {
        val ENEMY_SPRITE_PATTERN = Regex("enemy_(\\d+)\\.png")
    }
}
