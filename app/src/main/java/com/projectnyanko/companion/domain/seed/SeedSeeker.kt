package com.projectnyanko.companion.domain.seed

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One cat the player actually saw, resolved to its index inside the event pool. */
data class ObservedRoll(
    val rarity: CatRarity,
    val slot: Int,
    val unitId: Int = -1,
    val name: String = "",
)

enum class SeekMethod { SLOT, RARITY }

data class SeekHit(
    /** Seed that puts the first entered cat on 1A. This is the number to save. */
    val startingSeed: Long,
    /** Seed the tracker uses to continue right after the entered rolls. */
    val currentSeed: Long,
    val runType: Int,
)

data class SeekProgress(
    val scanned: Long,
    val total: Long,
    val elapsedMillis: Long,
    val runType: Int,
) {
    val fraction: Float
        get() = if (total <= 0L) 0f else (scanned.toDouble() / total).toFloat().coerceIn(0f, 1f)
}

data class SeekOutcome(
    val hits: List<SeekHit>,
    val runType: Int,
    val method: SeekMethod,
    val elapsedMillis: Long,
    val scanned: Long,
    /** Two or more seeds reproduce the entered rolls, so the first one may not be the real one. */
    val ambiguous: Boolean,
) {
    val startingSeed: Long? get() = hits.firstOrNull()?.startingSeed
    val currentSeed: Long? get() = hits.firstOrNull()?.currentSeed
}

/**
 * Exhaustive search for the seed behind a run of observed gacha results.
 *
 * Port of Godfat's `Seeker-VampireFlower.c`, the program behind https://bc.godfat.org/seek. The
 * search space is the whole unsigned 32-bit range, so the hot loop works on primitive arrays and
 * hand-rolled unsigned maths, and every surviving candidate is replayed exactly before it is
 * reported.
 *
 * Deliberate differences from the C original:
 *  - A duplicate rare is detected by unit id rather than slot index. Equivalent whenever a pool's
 *    unit ids are unique, and it is what the game (and Godfat's own table renderer) does for the
 *    21 bundled pools that repeat a unit inside one rarity.
 *  - The "first cat was itself a duplicate reroll" retry (run types 1 and 2) applies its
 *    `size - 1` divisor in both scan strategies. The C only does that in the slot strategy, so
 *    its rarity strategy silently cannot find those seeds.
 */
class SeedSeeker(
    private val threadCount: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 16),
) {
    fun seek(
        pool: GachaPool,
        rolls: List<ObservedRoll>,
        stopAfter: Int = 2,
        isCancelled: () -> Boolean = { false },
        onProgress: (SeekProgress) -> Unit = {},
    ): SeekOutcome {
        require(stopAfter >= 1)
        val plan = SeekPlan.of(pool, rolls)
        val startedAt = System.nanoTime()
        var scannedTotal = 0L
        var lastMethod = SeekMethod.SLOT

        for (runType in plan.runTypes()) {
            val method = plan.methodFor(runType)
            lastMethod = method
            val hits = ArrayList<SeekHit>(stopAfter)
            val stop = AtomicBoolean(false)
            val scanned = AtomicLong(0)
            val total = plan.estimatedCandidates(runType, method)
            val chunk = (UINT32_SPACE + threadCount - 1) / threadCount
            val workers = ArrayList<Thread>(threadCount)

            for (index in 0 until threadCount) {
                val from = chunk * index
                val to = minOf(chunk * (index + 1), UINT32_SPACE)
                val worker = Thread {
                    scanRange(
                        plan = plan,
                        runType = runType,
                        method = method,
                        from = from,
                        to = to,
                        stopAfter = stopAfter,
                        hits = hits,
                        scanned = scanned,
                        stop = stop,
                        isCancelled = isCancelled,
                    )
                }
                worker.name = "seed-seeker-$index"
                workers += worker
            }

            workers.forEach(Thread::start)
            while (true) {
                val first = workers.firstOrNull() ?: break
                first.join(PROGRESS_INTERVAL_MILLIS)
                if (workers.none { it.isAlive }) break
                onProgress(
                    SeekProgress(
                        scanned = scanned.get(),
                        total = total,
                        elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
                        runType = runType,
                    ),
                )
                if (isCancelled()) stop.set(true)
            }
            workers.forEach { it.join() }
            scannedTotal += scanned.get()

            if (hits.isNotEmpty()) {
                val ordered = hits.sortedBy { it.startingSeed }
                return SeekOutcome(
                    hits = ordered,
                    runType = runType,
                    method = method,
                    elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
                    scanned = scannedTotal,
                    ambiguous = ordered.size > 1,
                )
            }
            if (isCancelled()) break
        }

        return SeekOutcome(
            hits = emptyList(),
            runType = -1,
            method = lastMethod,
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
            scanned = scannedTotal,
            ambiguous = false,
        )
    }

    private fun scanRange(
        plan: SeekPlan,
        runType: Int,
        method: SeekMethod,
        from: Long,
        to: Long,
        stopAfter: Int,
        hits: MutableList<SeekHit>,
        scanned: AtomicLong,
        stop: AtomicBoolean,
        isCancelled: () -> Boolean,
    ) {
        var pendingScanned = 0L
        var checksUntilProgress = PROGRESS_INTERVAL_CHECKS
        val limit = minOf(to, UINT32_SPACE)

        fun flush() {
            if (pendingScanned != 0L) {
                scanned.addAndGet(pendingScanned)
                pendingScanned = 0L
            }
        }

        /** @return true when this worker should stop. */
        fun record(candidate: Long): Boolean {
            val hit = plan.hit(candidate, runType, method)
            synchronized(hits) {
                if (hits.size < stopAfter) hits.add(hit)
                if (hits.size >= stopAfter) {
                    stop.set(true)
                    return true
                }
            }
            return false
        }

        if (method == SeekMethod.SLOT) {
            val stride = plan.slotStride(runType)
            if (stride <= 0) return
            val residue = plan.slotResidue(runType)
            if (residue < 0 || residue >= stride) return
            var candidate = from + ((residue - (from % stride) + stride) % stride)
            while (candidate < limit) {
                if (plan.accepts(candidate, runType, method)) {
                    flush()
                    if (record(candidate)) return
                }
                candidate += stride
                pendingScanned++
                if (--checksUntilProgress <= 0) {
                    checksUntilProgress = PROGRESS_INTERVAL_CHECKS
                    flush()
                    if (stop.get() || isCancelled()) return
                }
            }
        } else {
            val low = plan.rarityBandLow()
            val high = plan.rarityBandHigh()
            if (high <= low) return
            var blockStart = from - (from % XorShift32.BASE)
            while (blockStart < limit) {
                for (offset in low until high) {
                    val candidate = blockStart + offset
                    if (candidate >= from && candidate < limit &&
                        plan.accepts(candidate, runType, method)
                    ) {
                        flush()
                        if (record(candidate)) return
                    }
                    pendingScanned++
                    if (--checksUntilProgress <= 0) {
                        checksUntilProgress = PROGRESS_INTERVAL_CHECKS
                        flush()
                        if (stop.get() || isCancelled()) return
                    }
                }
                blockStart += XorShift32.BASE
            }
        }
        flush()
    }

    private companion object {
        const val UINT32_SPACE: Long = 0x1_0000_0000L
        const val PROGRESS_INTERVAL_MILLIS: Long = 120
        const val PROGRESS_INTERVAL_CHECKS: Int = 1 shl 15
    }
}

/**
 * Immutable description of one search: pool shape plus the exact cats to look for.
 *
 * Built once per candidate slot assignment so the hot loop only ever reads IntArrays.
 */
internal class SeekPlan private constructor(
    private val rarityOf: IntArray,
    private val slotOf: IntArray,
    private val sizes: IntArray,
    private val idsByRarity: Array<IntArray>,
    private val rareBoundary: Int,
    private val superBoundary: Int,
    private val uberBoundary: Int,
) {
    fun runTypes(): List<Int> {
        if (rarityOf[0] != CatRarity.RARE.ordinal) return listOf(0)
        val size = sizes[CatRarity.RARE.ordinal]
        if (size < 2) return listOf(0)
        val slot = slotOf[0]
        return when {
            slot == 0 -> listOf(0, 1)
            slot == size - 1 -> listOf(0, 2)
            slot < size / 2 -> listOf(0, 1, 2)
            else -> listOf(0, 2, 1)
        }
    }

    /**
     * Mirror of `determine_fastest_approach`: compare how many candidates each strategy would
     * visit. Both are exhaustive, so the choice only affects speed.
     */
    fun methodFor(runType: Int): SeekMethod {
        val rarity = rarityOf[0]
        val range = bandHigh(rarity) - bandLow(rarity)
        val count = if (runType == 0) sizes[rarity] else sizes[rarity] - 1
        if (count <= 0) return SeekMethod.RARITY
        val rarityCost = (UINT32_SPACE / XorShift32.BASE) * range
        val slotCost = (UINT32_SPACE / count) * 1.5
        return if (rarityCost < slotCost) SeekMethod.RARITY else SeekMethod.SLOT
    }

    fun estimatedCandidates(runType: Int, method: SeekMethod): Long {
        val rarity = rarityOf[0]
        return if (method == SeekMethod.SLOT) {
            val stride = slotStride(runType)
            if (stride <= 0) 0L else UINT32_SPACE / stride
        } else {
            (UINT32_SPACE / XorShift32.BASE) * (bandHigh(rarity) - bandLow(rarity))
        }
    }

    fun slotStride(runType: Int): Int =
        if (runType == 0) sizes[rarityOf[0]] else sizes[rarityOf[0]] - 1

    fun slotResidue(runType: Int): Int = if (runType == 2) slotOf[0] - 1 else slotOf[0]

    fun rarityBandLow(): Int = bandLow(rarityOf[0])

    fun rarityBandHigh(): Int = bandHigh(rarityOf[0])

    /** True when [candidate] reproduces every entered cat. */
    fun accepts(candidate: Long, runType: Int, method: SeekMethod): Boolean {
        val value = candidate.toInt()
        return if (method == SeekMethod.SLOT) {
            // The stride already fixed the first cat's slot seed, so only the rest is replayed.
            fastPath(value, runType) && verify(XorShift32.retreatInt(value, backtrackSteps(runType, method)), runType)
        } else {
            val slotSeed = XorShift32.advanceInt(value)
            firstSlotMatches(slotSeed, runType) &&
                fastPath(slotSeed, runType) &&
                verify(XorShift32.retreatInt(value, backtrackSteps(runType, method)), runType)
        }
    }

    fun hit(candidate: Long, runType: Int, method: SeekMethod): SeekHit {
        val value = candidate.toInt()
        val begin = XorShift32.retreatInt(value, backtrackSteps(runType, method))
        return SeekHit(
            startingSeed = begin.toLong() and 0xFFFF_FFFFL,
            currentSeed = replay(begin, runType).toLong() and 0xFFFF_FFFFL,
            runType = runType,
        )
    }

    private fun backtrackSteps(runType: Int, method: SeekMethod): Int = when {
        method == SeekMethod.RARITY -> 1
        runType == 0 -> 2
        else -> 3
    }

    private fun firstSlotMatches(slotSeed: Int, runType: Int): Boolean {
        val size = sizes[rarityOf[0]]
        return when (runType) {
            0 -> remainderUnsigned(slotSeed, size) == slotOf[0]
            1 -> remainderUnsigned(slotSeed, size - 1) == slotOf[0]
            else -> remainderUnsigned(slotSeed, size - 1) == slotOf[0] - 1
        }
    }

    /**
     * Cheap filter: the rarity roll of every cat after the first is ignored because matching the
     * slot is strictly stronger. Survivors are replayed by [verify].
     */
    private fun fastPath(seedIn: Int, runType: Int): Boolean {
        if (runType != 0 && !firstSlotMatches(seedIn, runType)) return false
        var seed = seedIn
        var index = 1
        while (index < rarityOf.size) {
            seed = XorShift32.advanceInt(seed)
            seed = XorShift32.advanceInt(seed)
            val rarity = rarityOf[index]
            if (sizes[rarity] <= 0) return false
            var slot = remainderUnsigned(seed, sizes[rarity])
            if (rarity == CatRarity.RARE.ordinal && rarityOf[index - 1] == CatRarity.RARE.ordinal) {
                val previousId = idsByRarity[CatRarity.RARE.ordinal][slotOf[index - 1]]
                if (idsByRarity[CatRarity.RARE.ordinal][slot] == previousId) {
                    seed = XorShift32.advanceInt(seed)
                    if (sizes[CatRarity.RARE.ordinal] < 2) return false
                    val alternative = remainderUnsigned(seed, sizes[CatRarity.RARE.ordinal] - 1)
                    slot = if (alternative >= slot) alternative + 1 else alternative
                }
            }
            if (slot != slotOf[index]) return false
            index++
        }
        return true
    }

    /** Exact replay from the seed that starts the entered run. */
    fun verify(begin: Int, runType: Int): Boolean {
        var seed = begin
        for (index in rarityOf.indices) {
            if (runType != 0 && index == 0) {
                seed = XorShift32.advanceInt(seed)
                seed = XorShift32.advanceInt(seed)
                seed = XorShift32.advanceInt(seed)
                continue
            }
            seed = XorShift32.advanceInt(seed)
            val score = remainderUnsigned(seed, XorShift32.BASE)
            val rarity = rarityFor(score)
            if (rarity.ordinal != rarityOf[index]) return false
            if (sizes[rarity.ordinal] <= 0) return false
            seed = XorShift32.advanceInt(seed)
            var slot = remainderUnsigned(seed, sizes[rarity.ordinal])
            if (rarity == CatRarity.RARE && index > 0 &&
                rarityOf[index - 1] == CatRarity.RARE.ordinal
            ) {
                val previousId = idsByRarity[CatRarity.RARE.ordinal][slotOf[index - 1]]
                if (idsByRarity[CatRarity.RARE.ordinal][slot] == previousId) {
                    seed = XorShift32.advanceInt(seed)
                    if (sizes[CatRarity.RARE.ordinal] < 2) return false
                    val alternative = remainderUnsigned(seed, sizes[CatRarity.RARE.ordinal] - 1)
                    slot = if (alternative >= slot) alternative + 1 else alternative
                }
            }
            if (slot != slotOf[index]) return false
        }
        return true
    }

    private fun replay(begin: Int, runType: Int): Int {
        var seed = begin
        for (index in rarityOf.indices) {
            if (runType != 0 && index == 0) {
                seed = XorShift32.advanceInt(seed)
                seed = XorShift32.advanceInt(seed)
                seed = XorShift32.advanceInt(seed)
                continue
            }
            seed = XorShift32.advanceInt(seed)
            val rarity = rarityFor(remainderUnsigned(seed, XorShift32.BASE))
            seed = XorShift32.advanceInt(seed)
            val slot = remainderUnsigned(seed, sizes[rarity.ordinal])
            if (rarity == CatRarity.RARE && index > 0 &&
                rarityOf[index - 1] == CatRarity.RARE.ordinal
            ) {
                val previousId = idsByRarity[CatRarity.RARE.ordinal][slotOf[index - 1]]
                if (idsByRarity[CatRarity.RARE.ordinal][slot] == previousId) {
                    seed = XorShift32.advanceInt(seed)
                }
            }
        }
        return seed
    }

    private fun rarityFor(score: Int): CatRarity = when {
        score < rareBoundary -> CatRarity.RARE
        score < superBoundary -> CatRarity.SUPER_RARE
        score < uberBoundary -> CatRarity.UBER_RARE
        else -> CatRarity.LEGEND_RARE
    }

    private fun bandLow(rarity: Int): Int = when (rarity) {
        CatRarity.RARE.ordinal -> 0
        CatRarity.SUPER_RARE.ordinal -> rareBoundary
        CatRarity.UBER_RARE.ordinal -> superBoundary
        else -> uberBoundary
    }

    private fun bandHigh(rarity: Int): Int = when (rarity) {
        CatRarity.RARE.ordinal -> rareBoundary
        CatRarity.SUPER_RARE.ordinal -> superBoundary
        CatRarity.UBER_RARE.ordinal -> uberBoundary
        else -> XorShift32.BASE
    }

    companion object {
        private const val UINT32_SPACE: Long = 0x1_0000_0000L
        const val MAX_ROLLS: Int = 11

        fun of(pool: GachaPool, rolls: List<ObservedRoll>): SeekPlan {
            require(rolls.isNotEmpty()) { "Enter at least one cat you rolled." }
            require(rolls.size <= MAX_ROLLS) { "At most $MAX_ROLLS rolls can be searched at once." }
            val idsByRarity = Array(CatRarity.entries.size) { rarity ->
                pool.slots(CatRarity.entries[rarity]).map(CatSlot::id).toIntArray()
            }
            val sizes = IntArray(CatRarity.entries.size) { idsByRarity[it].size }
            val rarityOf = IntArray(rolls.size)
            val slotOf = IntArray(rolls.size)
            rolls.forEachIndexed { index, roll ->
                val rarity = roll.rarity.ordinal
                require(roll.slot in 0 until sizes[rarity]) {
                    "${roll.name.ifBlank { "Cat ${roll.unitId}" }} is not in this banner's " +
                        "${roll.rarity.displayName} pool."
                }
                rarityOf[index] = rarity
                slotOf[index] = roll.slot
            }
            require(sizes[rarityOf[0]] > 0) { "The first cat's rarity pool is empty." }
            val rareBoundary = pool.rareWeight
            val superBoundary = rareBoundary + pool.superRareWeight
            val uberBoundary = superBoundary + pool.uberRareWeight
            return SeekPlan(
                rarityOf = rarityOf,
                slotOf = slotOf,
                sizes = sizes,
                idsByRarity = idsByRarity,
                rareBoundary = rareBoundary,
                superBoundary = superBoundary,
                uberBoundary = uberBoundary,
            )
        }
    }
}

/** Unsigned 32-bit remainder for a value that arrived as a signed Int bit pattern. */
internal fun remainderUnsigned(value: Int, divisor: Int): Int =
    ((value.toLong() and 0xFFFF_FFFFL) % divisor).toInt()
