package com.projectnyanko.companion.ui.seed

/**
 * Name of an evolution stage by index, following the game's own wording: Base, Evolved, True and
 * Ultra. Kept out of the Compose file so it stays trivially unit-testable; anything past the
 * fourth stage (rare enough that the bundled table has none) falls back to a plain number.
 */
internal fun formLabel(index: Int): String = when (index) {
    0 -> "Base Form"
    1 -> "Evolved Form"
    2 -> "True Form"
    3 -> "Ultra Form"
    else -> "Form ${index + 1}"
}
