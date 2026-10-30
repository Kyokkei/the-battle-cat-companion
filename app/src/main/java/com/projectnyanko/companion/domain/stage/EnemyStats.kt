package com.projectnyanko.companion.domain.stage

/**
 * Intrinsic enemy stats — the numbers that do not move with a stage's magnification.
 *
 * Sourced from battlecatsinfo's enemy table (see `assets/neko/data/en/enemy_stats.json` for the
 * provenance and the bitmask decoding notes).
 */
data class EnemyStats(
    val id: Int,
    val name: String,
    val traits: List<String>,
    val isArea: Boolean,
    val range: Int,
    val speed: Int,
    val knockbacks: Int,
    val abilities: List<String>,
) {
    /** `Red · Floating`, or `Traitless` when the enemy carries no trait bits. */
    val traitLabel: String
        get() = traits.ifEmpty { listOf("Traitless") }.joinToString(" · ")

    val attackLabel: String
        get() = if (isArea) "Area attack" else "Single target"
}
