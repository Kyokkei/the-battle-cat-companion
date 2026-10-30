package com.projectnyanko.companion.ui.stage

/**
 * What the floating paw reads.
 *
 * These are deliberately separate modes rather than one auto-detecting scan: the stage matcher will
 * happily resolve a stray number on a cat screen to a stage id (an XP counter reading `34500` became
 * stage `045-00`), which then swallowed the frame before the cat reader ever ran. Holding the bubble
 * for half a second switches what the paw looks for, so neither reader has to guess.
 */
enum class ScanMode {
    STAGE,
    CAT;

    fun toggled(): ScanMode = if (this == STAGE) CAT else STAGE

    companion object {
        fun fromName(value: String?): ScanMode =
            entries.firstOrNull { it.name == value } ?: STAGE
    }
}
