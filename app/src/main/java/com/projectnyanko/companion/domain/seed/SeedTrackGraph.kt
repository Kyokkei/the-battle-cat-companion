package com.projectnyanko.companion.domain.seed

/**
 * Read-only view over the generated A/B roll graph for one seed, banner and "last cat".
 *
 * Mirrors the live tracker: the table is always numbered from 1, row 1 is the *next* roll, and the
 * cat you rolled just before ([lastCatId]) only exists to decide whether row 1 is a duplicate and
 * therefore rerolls. Rows are generated once, up front, because a node's `next` can point at a
 * rerolled duplicate several rows away.
 */
class SeedTrackGraph(
    val pool: GachaPool,
    val seed: Long,
    rowCount: Int,
    val lastCatId: Int = 0,
) {
    private val engine = SeedRollEngine(pool, seed)

    val rows: List<RollPair> = engine.generateRows(rowCount.coerceAtLeast(1), lastCatId)

    /** Synthetic node for the cat rolled before this table, or null when starting fresh. */
    val lastRoll: RolledCat? = engine.lastRoll

    val size: Int get() = rows.size

    fun row(sequence: Int): RollPair? = rows.getOrNull(sequence - 1)

    fun cat(sequence: Int, track: Int): RolledCat? {
        val pair = row(sequence) ?: return null
        return if (track == 0) pair.trackA else pair.trackB
    }

    /** Resolves a marker to the exact node it names, including its reroll / guaranteed variant. */
    fun cell(marker: TrackMarker): RolledCat? {
        if (marker.sequence == 0) return null
        val base = cat(marker.sequence, marker.track) ?: return null
        return when {
            marker.guaranteed -> base.guaranteedResult
            marker.rerolled -> base.rerolled
            else -> base
        }
    }

    /** The cat the very next roll produces: what the table shows as 1A. */
    fun nextNode(): RolledCat? = lastRoll?.next ?: rows.firstOrNull()?.trackA

    fun nextMarker(): TrackMarker? = nextNode()?.marker()

    /** The cat after [marker], following the graph (a reroll or a guarantee jumps rows). */
    fun next(marker: TrackMarker): RolledCat? = cell(marker)?.next

    /** Marker of the cat after [marker], for "you rolled this, what comes next?". */
    fun nextMarkerAfter(marker: TrackMarker): TrackMarker? = next(marker)?.marker()

    /**
     * The exact sequence of cats you would roll to reach [target] from here, or null when the
     * target sits on a branch this track never takes. This is the "can I actually get there?"
     * preview: it walks the real `next` edges, so a duplicate reroll shows up as a track switch.
     */
    fun pathTo(target: TrackMarker): List<RolledCat>? {
        val goal = cell(target) ?: return null
        val path = ArrayList<RolledCat>()
        val visited = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<RolledCat, Boolean>(),
        )
        var current = nextNode()
        while (current != null && visited.add(current)) {
            path += current
            if (current === goal) return path
            current = current.next
        }
        return null
    }

    /** Upcoming route from the current position; stops early if the graph runs out. */
    fun route(count: Int): List<RolledCat> {
        if (count <= 0) return emptyList()
        val result = ArrayList<RolledCat>(count)
        val visited = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<RolledCat, Boolean>(),
        )
        var current = nextNode()
        while (current != null && result.size < count && visited.add(current)) {
            result += current
            current = current.next
        }
        return result
    }
}
