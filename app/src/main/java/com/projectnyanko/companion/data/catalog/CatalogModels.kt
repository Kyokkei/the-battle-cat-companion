package com.projectnyanko.companion.data.catalog

import com.projectnyanko.companion.domain.seed.GameRegion
import com.projectnyanko.companion.domain.seed.GachaPool
import com.projectnyanko.companion.domain.seed.SeedPrediction

data class GachaEventSummary(
    val eventId: String,
    val name: String,
    val startDate: String,
    val endDate: String,
    val poolId: Int,
    val guaranteedRolls: Int,
)

data class CatalogMetadata(
    val region: GameRegion,
    val source: String,
    val sourceCommit: String,
    val captureDate: String,
    val latestScheduledEventStart: String,
    val eventsSha256: String,
    val poolsSha256: String,
    val unitsSha256: String,
    val eventCount: Int,
    val poolCount: Int,
    val unitCount: Int,
    val rightsNotice: String,
)

sealed interface CatalogLookup {
    data class Available(
        val pool: GachaPool,
        val event: GachaEventSummary,
        val metadata: CatalogMetadata,
    ) : CatalogLookup

    data class Unavailable(val reason: String) : CatalogLookup
}

sealed interface CatalogScreenState {
    data object Loading : CatalogScreenState
    data class Ready(
        val lookup: CatalogLookup.Available,
        val prediction: SeedPrediction,
    ) : CatalogScreenState
    data class Unavailable(val reason: String) : CatalogScreenState
}
