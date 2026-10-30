package com.projectnyanko.companion.domain.cat

/**
 * Battle Cats stat growth.
 *
 * The game gives every unit +20% of its base HP and attack per level, so the value at level `L` is
 * `base * (1 + 0.2 * (L - 1))`. The bundled `stats.json` only stores one reference level (30), so the
 * base has to be recovered from it and then re-projected onto whatever level the player's screen
 * shows.
 *
 * Base values are not always whole numbers: HP is frequently a half-integer (a unit with base 2.5 HP
 * exists), which is why a plain `value / 6.8` loses about 5% of units. Working in half-units keeps
 * the whole thing exact:
 *
 *     half  = round(valueAtReference * 10 / (referenceLevel + 4))
 *     value = half * (level + 4) / 10            (integer division)
 *
 * With reference level 30 this is `half * (L + 4) / 10` and reproduces the bundled table for 99.03%
 * of all forms; the remainder are rows the source table itself has wrong (a known 1.2x discrepancy on
 * some third forms).
 */
object CatLevelCurve {
    /** Lowest level the game shows. */
    const val MIN_LEVEL = 1

    /**
     * Sanity bound, not a game rule. A normal cat caps at 20 base levels but can carry +90 plus
     * levels, and plus levels grow stats exactly like normal ones, so the projection has to accept
     * levels well past 60.
     */
    const val MAX_LEVEL = 200

    /** Recovers the level-1 base from a value printed at [referenceLevel], in half-units. */
    fun baseHalfUnits(valueAtReference: Int, referenceLevel: Int): Long {
        if (valueAtReference <= 0) return 0L
        val denominator = (referenceLevel + 4).toDouble()
        if (denominator <= 0.0) return 0L
        return Math.round(valueAtReference * 10.0 / denominator)
    }

    /** Projects a half-unit base onto [level]. Levels outside 1..60 are clamped. */
    fun valueAt(baseHalfUnits: Long, level: Int): Int {
        if (baseHalfUnits <= 0L) return 0
        val clamped = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        return (baseHalfUnits * (clamped + 4L) / 10L).toInt()
    }

    /** Convenience: the value at [level] for a stat whose reference value is [valueAtReference]. */
    fun project(valueAtReference: Int, referenceLevel: Int, level: Int): Int =
        valueAt(baseHalfUnits(valueAtReference, referenceLevel), level)
}
