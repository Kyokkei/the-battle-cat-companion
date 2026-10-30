package com.projectnyanko.companion.data.catalog

import com.projectnyanko.companion.domain.cat.CatNameEntry
import com.projectnyanko.companion.domain.cat.CatNameIndex
import com.projectnyanko.companion.domain.seed.GameRegion

/**
 * Builds and caches the on-screen-name lookup the cat scanner matches against.
 *
 * The index is derived from the same bundled table the stats dialog reads, so a name can only ever
 * resolve to a unit whose numbers we can actually show.
 */
class CatScanCatalog(private val repository: UnitStatsRepository) {
    private val indexes = mutableMapOf<GameRegion, CatNameIndex>()

    @Synchronized
    fun index(region: GameRegion): CatNameIndex = indexes.getOrPut(region) {
        val entries = ArrayList<CatNameEntry>(2048)
        for (unit in repository.all(region).values) {
            unit.forms.forEachIndexed { formIndex, form ->
                if (form.name.isBlank()) return@forEachIndexed
                entries += CatNameEntry(
                    unitId = unit.id,
                    formIndex = formIndex,
                    displayName = form.name,
                )
            }
        }
        CatNameIndex(entries)
    }
}
