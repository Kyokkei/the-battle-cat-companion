package com.projectnyanko.companion.domain.cat

/** One evolution stage that the game can print on screen. */
data class CatNameEntry(
    val unitId: Int,
    val formIndex: Int,
    val displayName: String,
)

/** A resolved name, with how far it had to be corrected to get there. */
data class CatNameMatch(
    val entry: CatNameEntry,
    val distance: Int,
) {
    val exact: Boolean get() = distance == 0
}

/**
 * Maps an OCR string back to a unit and evolution stage.
 *
 * The same unit prints a different name per form — `Koneko`, `Superstar Koneko` — and two different
 * units can print similar names (`Koneko` at 783 vs `Metal Maiden Koneko` at 873, whose evolved form
 * is `Sonic Siren Koneko`). Matching therefore has to be on the full form name, not the unit name.
 *
 * The recognizer reads these banners at 0.97-1.00 confidence, so exact matches dominate; the edit
 * distance path exists for dropped or doubled characters (a clipped crop once produced
 * `Sonic Siren Konek`).
 */
class CatNameIndex(entries: List<CatNameEntry>) {
    private val byNormalized: Map<String, CatNameEntry>

    init {
        val map = HashMap<String, CatNameEntry>(entries.size * 2)
        for (entry in entries) {
            val key = normalize(entry.displayName)
            if (key.isEmpty()) continue
            // Two units genuinely share a name ("Cat Bros"); keep the first and let the caller's
            // other signals decide. Everything else is unique.
            map.putIfAbsent(key, entry)
        }
        byNormalized = map
    }

    val size: Int get() = byNormalized.size

    fun resolve(text: String): CatNameMatch? {
        val query = normalize(text)
        if (query.length < MIN_QUERY_LENGTH) return null
        byNormalized[query]?.let { return CatNameMatch(it, 0) }

        val allowed = allowedDistance(query.length)
        if (allowed == 0) return null
        var best: CatNameEntry? = null
        var bestDistance = Int.MAX_VALUE
        var bestLengthDelta = Int.MAX_VALUE
        var tied = false
        for ((key, entry) in byNormalized) {
            if (kotlin.math.abs(key.length - query.length) > allowed) continue
            val distance = editDistance(query, key, allowed)
            if (distance > allowed) continue
            val lengthDelta = kotlin.math.abs(key.length - query.length)
            when {
                distance < bestDistance -> {
                    best = entry
                    bestDistance = distance
                    bestLengthDelta = lengthDelta
                    tied = false
                }
                distance == bestDistance && lengthDelta < bestLengthDelta -> {
                    best = entry
                    bestLengthDelta = lengthDelta
                    tied = false
                }
                distance == bestDistance && lengthDelta == bestLengthDelta -> tied = true
            }
        }
        // An unresolved tie means the screen does not say enough to pick one cat; guessing here would
        // silently show the wrong unit's stats.
        if (best == null || tied) return null
        return CatNameMatch(best, bestDistance)
    }

    private companion object {
        const val MIN_QUERY_LENGTH = 4

        /** Short names get a tighter budget so `Cat` cannot drift onto `Tank Cat`. */
        fun allowedDistance(length: Int): Int = when {
            length <= 6 -> 1
            length <= 14 -> 2
            else -> 3
        }

        fun normalize(text: String): String {
            val builder = StringBuilder(text.length)
            var lastSpace = true
            for (ch in text) {
                when {
                    ch.isLetterOrDigit() -> {
                        builder.append(ch.lowercaseChar())
                        lastSpace = false
                    }
                    !lastSpace -> {
                        builder.append(' ')
                        lastSpace = true
                    }
                }
            }
            return builder.toString().trim()
        }

        /** Bounded Levenshtein; returns [limit] + 1 as soon as it is clear the budget is blown. */
        fun editDistance(a: String, b: String, limit: Int): Int {
            if (a == b) return 0
            if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                var rowMinimum = current[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(
                        previous[j] + 1,
                        current[j - 1] + 1,
                        previous[j - 1] + cost,
                    )
                    if (current[j] < rowMinimum) rowMinimum = current[j]
                }
                if (rowMinimum > limit) return limit + 1
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.length]
        }
    }
}
