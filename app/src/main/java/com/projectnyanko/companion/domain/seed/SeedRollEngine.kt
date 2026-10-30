package com.projectnyanko.companion.domain.seed

/** Pure deterministic port of Godfat's gacha track graph generation. */
class SeedRollEngine(
    private val pool: GachaPool,
    startingSeed: Long,
) {
    /**
     * Upstream `Gacha.new` advances the seed once on construction, so the number you paste from
     * the tracker is the state *before* the first roll, not the first rarity roll itself. Skipping
     * that step shifts every row by half a roll.
     */
    private var seed = XorShift32.advance(XorShift32.normalize(startingSeed))
    private var previousA: RolledCat? = null
    private var previousB: RolledCat? = null
    private var hasGenerated = false

    /**
     * Synthetic node standing for "the cat you rolled just before this table". Only its id matters:
     * it feeds the duplicate check for row 1, which is why the same table looks different when you
     * arrive from different cats.
     */
    var lastRoll: RolledCat? = null
        private set

    init {
        require(startingSeed in 1L..0xFFFF_FFFFL) {
            "Seed must be a non-zero unsigned 32-bit value."
        }
    }

    /** Generate the route graph and follow the selected result for [count] upcoming nodes. */
    fun predict(lastSequence: Int, lastTrack: Int, count: Int): SeedPrediction {
        require(lastSequence >= 0)
        require(lastTrack in 0..1)
        require(count >= 0)
        check(!hasGenerated) { "A SeedRollEngine instance can only generate one prediction graph." }

        require(count <= 1_000) { "Prediction requests are limited to 1,000 rolls." }
        // A duplicate can jump by several rows; reserve enough graph tail for each requested route edge.
        val maxJump = maxRouteJump()
        val routeTail = Math.multiplyExact(count + pool.guaranteedRolls, maxJump)
        val rows = generateGraph(maxOf(1, Math.addExact(lastSequence, Math.addExact(routeTail, 32))))
        val current = if (lastSequence == 0) null else rows[lastSequence - 1].cat(lastTrack)
        val routeStart = current?.next ?: if (lastSequence == 0) rows.first().cat(lastTrack) else null
        val route = follow(routeStart, count)
        val currentPosition = current?.marker() ?: TrackMarker(0, lastTrack)

        return SeedPrediction(
            rows = rows,
            route = route,
            currentPosition = currentPosition,
            nextPosition = route.firstOrNull()?.marker(),
        )
    }

    /** Simulate consecutive 11-roll guarantees or 15-roll step-up batches. */
    fun predictGuaranteedBatches(lastSequence: Int, lastTrack: Int, batchCount: Int): SeedPrediction {
        require(lastSequence >= 0)
        require(lastTrack in 0..1)
        require(batchCount in 0..100)
        require(pool.guaranteedRolls > 0) { "This event has no guaranteed or step-up draw." }
        check(!hasGenerated) { "A SeedRollEngine instance can only generate one prediction graph." }

        val requestedRows = Math.multiplyExact(
            Math.multiplyExact(batchCount, pool.guaranteedRolls),
            maxRouteJump(),
        )
        val rows = generateGraph(maxOf(1, Math.addExact(lastSequence, maxOf(requestedRows, 32))))
        val current = if (lastSequence == 0) null else rows[lastSequence - 1].cat(lastTrack)
        var batchStart = current?.next ?: if (lastSequence == 0) rows.first().cat(lastTrack) else null
        val route = ArrayList<RolledCat>(batchCount * pool.guaranteedRolls)

        for (batch in 0 until batchCount) {
            val anchor = batchStart ?: break
            var cursor = anchor
            route += cursor
            var complete = true
            for (step in 1 until pool.guaranteedRolls - 1) {
                val following = cursor.next
                if (following == null) {
                    complete = false
                    break
                }
                cursor = following
                route += cursor
            }
            if (!complete || cursor.next == null) break
            val guaranteed = anchor.guaranteedResult ?: break
            route += guaranteed
            batchStart = guaranteed.next
        }

        return SeedPrediction(
            rows = rows,
            route = route,
            currentPosition = current?.marker() ?: TrackMarker(0, lastTrack),
            nextPosition = route.firstOrNull()?.marker(),
        )
    }

    /** Test/data tooling can request raw two-track rows; the returned rows have graph links finalized. */
    fun generateRows(rowCount: Int, lastCatId: Int = 0): List<RollPair> {
        require(rowCount > 0)
        check(!hasGenerated) { "A SeedRollEngine instance can only generate one prediction graph." }
        return generateGraph(rowCount, lastCatId)
    }

    fun rollPairs(startSequence: Int, count: Int): List<RollPair> {
        require(startSequence > 0)
        require(count >= 0)
        check(!hasGenerated) { "A SeedRollEngine instance can only generate one prediction graph." }
        if (count == 0) return emptyList()
        val endSequence = Math.addExact(startSequence, count - 1)
        val rows = generateGraph(endSequence)
        return rows.subList(startSequence - 1, endSequence)
    }

    private fun maxRouteJump(): Int {
        val largestRareMultiplicity = pool.slots(CatRarity.RARE)
            .groupingBy(CatSlot::id)
            .eachCount()
            .values
            .maxOrNull() ?: 0
        return XorShift32.nextIndex(track = 1, steps = largestRareMultiplicity).coerceAtLeast(2)
    }

    private fun generateGraph(rowCount: Int, lastCatId: Int = 0): List<RollPair> {
        hasGenerated = true
        // The previous cat has to exist *before* row 1 is rolled: it is what decides whether row 1
        // is a duplicate, and the reroll node it creates must be in place before the reroll links
        // are finished. Upstream does the same by seeding `last_both` before rolling.
        if (lastCatId != 0) {
            val last = RolledCat(
                slot = null,
                rarity = CatRarity.RARE,
                sequence = 0,
                track = 0,
                explicitId = lastCatId,
            )
            previousA = last
            lastRoll = last
        }
        val rows = ArrayList<RollPair>(rowCount)
        for (sequence in 1..rowCount) rows += rollPair(sequence)
        finishRerolledLinks(rows)
        if (pool.guaranteedRolls > 0) finishGuaranteed(rows, pool.guaranteedRolls)
        return rows
    }

    private fun rollPair(sequence: Int): RollPair {
        val aRaritySeed = takeSeed()
        val bRaritySeed = seed
        val a = rollCat(aRaritySeed, takeSeed(), sequence, track = 0)
        val b = rollCat(bRaritySeed, seed, sequence, track = 1)

        fillCatLinks(a, previousA)
        fillCatLinks(b, previousB)
        previousA = a
        previousB = b
        return RollPair(sequence, a, b)
    }

    private fun takeSeed(): Long {
        val current = seed
        seed = XorShift32.advance(seed)
        return current
    }

    private fun rollCat(raritySeed: Long, slotSeed: Long, sequence: Int, track: Int): RolledCat {
        val score = (raritySeed % XorShift32.BASE).toInt()
        val rareUpper = pool.rareWeight
        val superRareUpper = rareUpper + pool.superRareWeight
        val uberUpper = superRareUpper + pool.uberRareWeight
        val rarity = when {
            score < rareUpper -> CatRarity.RARE
            score < superRareUpper -> CatRarity.SUPER_RARE
            score < uberUpper -> CatRarity.UBER_RARE
            else -> CatRarity.LEGEND_RARE
        }
        return newCat(rarity, slotSeed, sequence, track, raritySeed, score)
    }

    private fun newCat(
        rarity: CatRarity,
        slotSeed: Long,
        sequence: Int,
        track: Int,
        raritySeed: Long = 0,
        score: Int = 0,
        extraLabel: String = "",
        next: RolledCat? = null,
    ): RolledCat {
        val slots = pool.slots(rarity)
        val slotIndex = if (slots.isEmpty()) null else (slotSeed % slots.size).toInt()
        return RolledCat(
            slot = slotIndex?.let(slots::get),
            rarity = rarity,
            sequence = sequence,
            track = track,
            extraLabel = extraLabel,
            raritySeed = raritySeed,
            score = score,
            slotSeed = slotSeed,
            slotIndex = slotIndex,
        ).also { it.next = next }
    }

    /** Mirrors upstream fill_cat_links(cat, last_cat), including replacement of the route edge. */
    private fun fillCatLinks(cat: RolledCat?, lastCat: RolledCat?) {
        if (cat == null || lastCat == null) return
        if (cat.isDuplicateRareOf(lastCat)) {
            val rerolled = cat.rerolled ?: rerollCat(cat).also {
                cat.rerolled = it
                it.parent = lastCat
            }
            lastCat.next = rerolled
        } else {
            lastCat.next = cat
        }
    }

    private fun RolledCat.isDuplicateRareOf(other: RolledCat): Boolean =
        rarity == CatRarity.RARE && other.rarity == CatRarity.RARE && id > 0 && id == other.id

    private fun rerollCat(cat: RolledCat): RolledCat {
        val originalId = cat.id
        val pool = pool.slots(cat.rarity)
        val remainingIds = pool.map(CatSlot::id).toMutableList()
        // The game picks from the pool with the duplicate removed, so the winning index is an
        // index into that shortened list. Keep the mapping back to the real slot, because the
        // seeker and the UI both need the position inside the full banner pool.
        val remainingSlots = MutableList(pool.size) { it }
        var reducedIndex = cat.slotIndex ?: return cat
        var nextSeed = cat.slotSeed
        val maximumAttempts = remainingIds.count { it == originalId }
        repeat(maximumAttempts) { attempt ->
            nextSeed = XorShift32.advance(nextSeed)
            remainingIds.removeAt(reducedIndex)
            remainingSlots.removeAt(reducedIndex)
            check(remainingIds.isNotEmpty()) { "A duplicate Rare pool has no alternative cat." }
            reducedIndex = (nextSeed % remainingIds.size).toInt()
            if (remainingIds[reducedIndex] != originalId) {
                val slotIndex = remainingSlots[reducedIndex]
                return RolledCat(
                    slot = pool[slotIndex],
                    rarity = cat.rarity,
                    sequence = cat.sequence,
                    track = cat.track,
                    extraLabel = "${cat.extraLabel}R",
                    rerollSteps = attempt + 1,
                    raritySeed = cat.raritySeed,
                    score = cat.score,
                    slotSeed = nextSeed,
                    slotIndex = slotIndex,
                )
            }
        }
        error("Upstream duplicate reroll search exhausted without finding an alternate slot.")
    }

    private fun finishRerolledLinks(rows: List<RollPair>) {
        rows.forEachIndexed { rowIndex, pair ->
            listOf(pair.trackA, pair.trackB).forEach { rolledCat ->
                val rerolled = rolledCat.rerolled ?: return@forEach
                val nextRow = rowIndex + XorShift32.nextIndex(rolledCat.track, rerolled.rerollSteps)
                val nextTrack = XorShift32.nextTrack(rolledCat.track, rerolled.rerollSteps)
                val nextCat = rows.getOrNull(nextRow)?.cat(nextTrack)
                fillCatLinks(nextCat, rerolled)
            }
        }
    }

    private fun finishGuaranteed(rows: List<RollPair>, guaranteedRolls: Int) {
        rows.forEach { pair ->
            listOf(pair.trackA, pair.trackB).forEach { rolledCat ->
                fillGuaranteed(rows, guaranteedRolls, rolledCat)
                rolledCat.rerolled?.let { fillGuaranteed(rows, guaranteedRolls, it) }
            }
        }
    }

    private fun fillGuaranteed(rows: List<RollPair>, guaranteedRolls: Int, rolledCat: RolledCat) {
        val last = followOne(rolledCat, guaranteedRolls - 1) ?: return
        val nextRowIndex = last.sequence - (last.track xor 1)
        val nextTrack = last.track xor 1
        val nextCat = rows.getOrNull(nextRowIndex)?.cat(nextTrack) ?: return
        val raritySource = rows.getOrNull(last.sequence - 1)?.cat(last.track) ?: return
        val guaranteed = newCat(
            rarity = CatRarity.UBER_RARE,
            slotSeed = raritySource.raritySeed,
            sequence = rolledCat.sequence,
            track = rolledCat.track,
            extraLabel = "${rolledCat.extraLabel}G",
            next = nextCat,
        )
        rolledCat.guaranteedResult = guaranteed
    }

    private fun followOne(start: RolledCat, steps: Int): RolledCat? {
        var current: RolledCat? = start
        repeat(steps) {
            current = current?.next ?: return null
        }
        return current
    }

    private fun follow(start: RolledCat?, count: Int): List<RolledCat> {
        if (count == 0) return emptyList()
        val route = ArrayList<RolledCat>(count)
        val visited = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<RolledCat, Boolean>(),
        )
        var current = start
        while (current != null && route.size < count && visited.add(current)) {
            route += current
            current = current.next
        }
        return route
    }

    private fun RollPair.cat(track: Int): RolledCat = if (track == 0) trackA else trackB
}
