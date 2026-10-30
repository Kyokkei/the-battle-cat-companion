package com.projectnyanko.companion.domain.seed

/** Live Godfat table capture used only as a regression oracle, never as generated output. */
data class ReferenceRoll(
    val position: String,
    val name: String,
    val rarity: CatRarity? = null,
    val switchedFrom: String? = null,
)

object GodfatReferenceFixture {
    const val sourceUrl: String =
        "https://bc.godfat.org/?seed=1169179061&last=199&event=2026-10-23_946&count=210"
    const val capturedAt: String = "2026-10-30"
    const val seed: Long = 1_169_179_061L
    const val eventId: String = "2026-10-23_946"
    const val eventName: String = "Hanasaka Cat added! Mythical fantasy Cat heroes!"
    const val lastPosition: Int = 199

    /**
     * Rows 200A-210A of the live table.
     *
     * The first capture of this fixture was read from an app build that never applied Godfat's
     * initial seed advance, so it was labelled A but actually held the B lane one row earlier.
     * These values were re-read from the rendered table on 2026-10-30.
     */
    val after199A = listOf(
        ReferenceRoll("200A", "Salon Cat"),
        ReferenceRoll("201A", "Fortune Teller Cat"),
        ReferenceRoll("202A", "Weightlifter Cat"),
        ReferenceRoll("203A", "Cat Toaster"),
        ReferenceRoll("204A", "Shitakiri Sparrow"),
        ReferenceRoll("205A", "Pirate Cat"),
        ReferenceRoll("206A", "Onmyoji Cat"),
        ReferenceRoll("207A", "Stilts Cat"),
        ReferenceRoll("208A", "Cat Gunslinger"),
        ReferenceRoll("209A", "Sniper the Recruit"),
        ReferenceRoll("210A", "Viking Cat"),
    )

    /** Rows 200B-210B of the live table. */
    val after199B = listOf(
        ReferenceRoll("200B", "Juliet Cat"),
        ReferenceRoll("201B", "Metal Cat"),
        ReferenceRoll("202B", "Salon Cat"),
        ReferenceRoll("203B", "Shitakiri Sparrow"),
        ReferenceRoll("204B", "Onmyoji Cat"),
        ReferenceRoll("205B", "Thief Cat"),
        ReferenceRoll("206B", "Cat Gunslinger"),
        ReferenceRoll("207B", "Tin Cat"),
        ReferenceRoll("208B", "Surfer Cat"),
        ReferenceRoll("209B", "Psychocat"),
        ReferenceRoll("210B", "Pirate Cat"),
    )
}
