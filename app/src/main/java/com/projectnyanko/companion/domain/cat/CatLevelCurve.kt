package com.projectnyanko.companion.domain.cat

import com.projectnyanko.companion.domain.seed.CatRarity

/**
 * Battle Cats stat growth.
 *
 * The bundled table stores one reference level (30). From it the level-1 base is
 * `S1 = S30 / 17.0`, and growth is piecewise rather than a single line:
 *
 *     L <= 60          S1 * (2.0 + 0.5 * L)              +0.5   * S1 per level
 *     60 < L <= 80     Stat(60) + (L - 60) * 0.25 * S1   +0.25  * S1 per level
 *     80 < L <= 130    Stat(80) + (L - 80) * 0.125 * S1  +0.125 * S1 per level
 *
 * Below level 60 this is algebraically identical to `base * (1 + 0.2 * (L - 1))`, which is why
 * Pizza Cat at 50 matched the reference exactly; the tiers only matter once plus levels push a cat
 * past 60.
 */
object CatLevelCurve {
    const val MIN_LEVEL = 1

    /** Highest total level any rarity can reach (Uber 60+70, Super Rare 50+80). */
    const val MAX_LEVEL = 130

    /** The level the bundled `stats.json` values are stated at. */
    const val REFERENCE_LEVEL = 30

    /**
     * Hard cap for a total level (base + plus), per rarity.
     *
     * Used to reject impossible OCR reads: a Legend Rare cannot be level 128, so a stray `128` on
     * screen is a misread rather than a level.
     */
    fun capFor(rarity: CatRarity?): Int = when (rarity) {
        CatRarity.UBER_RARE -> 130            // base 60 + plus 70
        CatRarity.SUPER_RARE -> 130           // base 50 + plus 80
        CatRarity.RARE -> 130                 // base 50 + plus 80
        CatRarity.LEGEND_RARE -> 70           // base 50 + plus 20
        null -> 110                           // Normal: base 20 + plus 90
    }

    /** Level-1 base for a stat whose level-30 value is [statAtReference]. */
    fun baseStat(statAtReference: Int): Double = statAtReference / 17.0

    /** The stat at [level], given the level-30 value. */
    fun statAt(statAtReference: Int, level: Int, normalCat: Boolean = false): Int {
        if (statAtReference <= 0) return 0
        val clamped = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        val s1 = baseStat(statAtReference)
        val value = if (normalCat) {
            // Normal Cats are a legacy exception: they halve at 60 like everyone else but never
            // quarter, so 61..110 all grow at +0.25. Verified against the game at level 110:
            // Mohawk 8900 and Eraser 26700, both exact. (Full growth to 90 gave 10400/31200.)
            val statAt60 = s1 * (2.0 + 60 * 0.5)
            if (clamped <= 60) s1 * (2.0 + 0.5 * clamped) else statAt60 + (clamped - 60) * 0.25 * s1
        } else {
            val statAt60 = s1 * (2.0 + 60 * 0.5)
            val statAt80 = statAt60 + 20 * 0.25 * s1
            when {
                clamped <= 60 -> s1 * (2.0 + 0.5 * clamped)
                clamped <= 80 -> statAt60 + (clamped - 60) * 0.25 * s1
                else -> statAt80 + (clamped - 80) * 0.125 * s1
            }
        }
        return kotlin.math.round(value).toInt()
    }

    /** Convenience for callers that still pass a reference level; only level 30 is stored. */
    fun project(valueAtReference: Int, referenceLevel: Int, level: Int): Int {
        val normalised = if (referenceLevel == REFERENCE_LEVEL) {
            valueAtReference
        } else {
            kotlin.math.round(valueAtReference * 17.0 / (2.0 + 0.5 * referenceLevel)).toInt()
        }
        return statAt(normalised, level)
    }

    /** True when the unit has no gacha rarity, i.e. the Normal Cats. */
    fun isNormalCat(rarity: CatRarity?): Boolean = rarity == null
}
