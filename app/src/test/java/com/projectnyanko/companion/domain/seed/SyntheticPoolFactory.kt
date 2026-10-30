package com.projectnyanko.companion.domain.seed

/** Synthetic pool for deterministic engine tests only; never presented as game data. */
object SyntheticPoolFactory {
    fun create(guaranteedRolls: Int = 0): GachaPool = GachaPool(
        eventId = "synthetic-test-pool",
        eventName = "Synthetic test pool",
        region = GameRegion.EN,
        rareWeight = XorShift32.BASE,
        superRareWeight = 0,
        uberRareWeight = 0,
        slotsByRarity = mapOf(
            CatRarity.RARE to listOf(
                CatSlot(1, "Synthetic Cat A", CatRarity.RARE),
                CatSlot(2, "Synthetic Cat B", CatRarity.RARE),
                CatSlot(3, "Synthetic Cat C", CatRarity.RARE),
            ),
            CatRarity.UBER_RARE to listOf(
                CatSlot(101, "Synthetic Uber", CatRarity.UBER_RARE),
            ),
        ),
        guaranteedRolls = guaranteedRolls,
    )
}
