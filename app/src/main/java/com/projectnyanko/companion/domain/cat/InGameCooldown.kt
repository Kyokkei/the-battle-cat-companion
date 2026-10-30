package com.projectnyanko.companion.domain.cat

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Turns the bundled `stats.json` recharge value into the number the game actually uses.
 *
 * `stats.json` is scraped from catstat.tsv, which stores the **level 1 base cooldown with zero
 * treasures and zero upgrades** — that is why it is full of `x.07` values: the game runs at 30 FPS,
 * so a cooldown is always a whole number of frames, and 152 frames prints as 5.07s (152/30).
 *
 * MyGamatoto and BattleCatsStats show endgame deployment instead, which is the raw frame count
 * minus two flat reductions:
 *
 * - EoC Relativity Clock, all three chapters at 100% Superior: -30 frames (-1.00s)
 * - Cat Production / Research 20+10: -234 frames (-7.80s)
 *
 * ...and then clamped by PONOS' hard floor of 60 frames (2.00s) — no unit deploys faster than
 * that. The floor is what makes Mohawk (152f) and Eraser (242f) both land on exactly 2.00s, and
 * the flat reduction is why Saber Alter (2402f) only loses 8.80s instead of a percentage.
 *
 * Attack cycle (`freq`) is animation, not deployment, so it must never go through this.
 */
object InGameCooldown {
    /** The engine ticks at 30 FPS; every cooldown is an integer frame count. */
    const val FPS = 30.0

    /** Relativity Clock, chapters 1-3 at 100% Superior. */
    const val TREASURE_FRAMES = 30L

    /** Cat Production / Research at 20+10. */
    const val RESEARCH_FRAMES = 234L

    const val TOTAL_REDUCTION_FRAMES = TREASURE_FRAMES + RESEARCH_FRAMES

    /** PONOS' hard floor: 60 frames. */
    const val MIN_FRAMES = 60L

    /** Raw cooldown in whole frames, recovered from the 2-decimal seconds in the table. */
    fun rawFrames(rawSeconds: Double): Long = (rawSeconds * FPS).roundToLong()

    /** Frames after the endgame reductions, never below the floor. */
    fun inGameFrames(rawSeconds: Double): Long {
        if (rawSeconds <= 0.0) return 0L
        return (rawFrames(rawSeconds) - TOTAL_REDUCTION_FRAMES).coerceAtLeast(MIN_FRAMES)
    }

    fun inGameSeconds(rawSeconds: Double): Double = inGameFrames(rawSeconds) / FPS

    /** Display string, e.g. `2.00s` / `19.20s` / `71.27s`. */
    fun format(rawSeconds: Double): String =
        if (rawSeconds <= 0.0) "—" else String.format(Locale.ROOT, "%.2fs", inGameSeconds(rawSeconds))
}
