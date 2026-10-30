package com.projectnyanko.companion.domain.seed

/** Region is separate from display locale because capsule pools vary by game client. */
enum class GameRegion(val label: String) {
    EN("BCEN"),
    JP("BCJP"),
}

enum class CatRarity(val displayName: String, val upstreamId: Int) {
    RARE("Rare", 2),
    SUPER_RARE("Super Rare", 3),
    UBER_RARE("Uber Rare", 4),
    LEGEND_RARE("Legend Rare", 5),
}

data class CatSlot(
    val id: Int,
    val name: String,
    val rarity: CatRarity,
)

data class GachaPool(
    val eventId: String,
    val eventName: String,
    val region: GameRegion,
    val rareWeight: Int,
    val superRareWeight: Int,
    val uberRareWeight: Int,
    /** Slot order is significant: upstream chooses `slotSeed % slots.size`. */
    val slotsByRarity: Map<CatRarity, List<CatSlot>>,
    val guaranteedRolls: Int = 0,
    /** Already ordered future Uber slots, normally prepended before ordinary Uber slots. */
    val futureUbers: List<CatSlot> = emptyList(),
    val catalogVersion: String = "unknown",
) {
    init {
        require(eventId.isNotBlank())
        require(rareWeight >= 0 && superRareWeight >= 0 && uberRareWeight >= 0)
        val totalWeight = rareWeight.toLong() + superRareWeight + uberRareWeight
        require(totalWeight <= XorShift32.BASE.toLong())
        require(guaranteedRolls == 0 || guaranteedRolls == 11 || guaranteedRolls == 15)
        require(futureUbers.all { it.rarity == CatRarity.UBER_RARE })
        require(slotsByRarity.all { (rarity, slots) -> slots.all { it.rarity == rarity } })
    }

    fun slots(rarity: CatRarity): List<CatSlot> {
        val base = slotsByRarity[rarity].orEmpty()
        return if (rarity == CatRarity.UBER_RARE) futureUbers + base else base
    }
}

/** Mutable graph node matching Godfat's Cat links without recursive data-class equality. */
class RolledCat(
    val slot: CatSlot?,
    val rarity: CatRarity,
    val sequence: Int,
    val track: Int,
    val extraLabel: String = "",
    val rerollSteps: Int = 0,
    val raritySeed: Long = 0,
    val score: Int = 0,
    val slotSeed: Long = 0,
    val slotIndex: Int? = null,
    /**
     * Only set on the synthetic "last cat" node that exists purely to feed the duplicate check
     * for row 1; it has no slot and therefore no id of its own.
     */
    val explicitId: Int = -1,
) {
    var next: RolledCat? = null
    var rerolled: RolledCat? = null
    var guaranteedResult: RolledCat? = null
    var parent: RolledCat? = null
    var picked: Boolean = false

    val id: Int
        get() = if (explicitId >= 0) explicitId else slot?.id ?: -1

    val isGuaranteed: Boolean
        get() = 'G' in extraLabel

    val displayPosition: String
        get() = "$sequence${('A'.code + track).toChar()}$extraLabel"

    val displayName: String
        get() = slot?.name ?: "No unit in ${rarity.displayName} pool"

    fun marker(): TrackMarker = TrackMarker(sequence, track, 'R' in extraLabel, isGuaranteed)
}

data class RollPair(
    val sequence: Int,
    val trackA: RolledCat,
    val trackB: RolledCat,
)

data class TrackMarker(
    val sequence: Int,
    val track: Int,
    val rerolled: Boolean = false,
    val guaranteed: Boolean = false,
) {
    init {
        require(sequence >= 0)
        require(track in 0..1)
    }

    override fun toString(): String = "$sequence${('A'.code + track).toChar()}${if (rerolled) "R" else ""}${if (guaranteed) "G" else ""}"
}

data class SeedPrediction(
    val rows: List<RollPair>,
    val route: List<RolledCat>,
    val currentPosition: TrackMarker,
    val nextPosition: TrackMarker?,
)

data class RollHistoryEntry(
    val sequence: Int,
    val track: Int,
    val catName: String,
    val rarity: CatRarity,
)

data class SeedTrackerProfile(
    val id: Long = 0,
    val name: String,
    val seed: Long,
    val region: GameRegion,
    val eventId: String,
    val currentPosition: Int,
    val currentTrack: Int = 0,
)
