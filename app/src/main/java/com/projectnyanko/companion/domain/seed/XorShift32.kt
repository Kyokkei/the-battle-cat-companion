package com.projectnyanko.companion.domain.seed

/** 32-bit xorshift used by the upstream Battle Cats roll tracker. */
object XorShift32 {
    const val BASE: Int = 10_000
    private const val MASK: Long = 0xFFFF_FFFFL

    fun normalize(seed: Long): Long = seed and MASK

    fun advance(seed: Long): Long {
        var value = normalize(seed)
        value = value xor ((value shl 13) and MASK)
        value = value xor (value ushr 17)
        value = value xor ((value shl 15) and MASK)
        return value and MASK
    }

    fun retreat(seed: Long): Long {
        var value = normalize(seed)
        value = value xor ((value shl 15) and MASK)
        value = value xor ((value shl 30) and MASK)
        value = value xor (value ushr 17)
        value = value xor ((value shl 13) and MASK)
        value = value xor ((value shl 26) and MASK)
        return value and MASK
    }

    /**
     * Same generator on the raw 32-bit pattern. The seed searcher runs this tens of millions of
     * times per core, where the Long-based version above costs an extra mask per step. Int shifts
     * in Kotlin already discard the high bits, so the bit pattern is identical to unsigned 32-bit.
     */
    fun advanceInt(seed: Int): Int {
        var value = seed
        value = value xor (value shl 13)
        value = value xor (value ushr 17)
        value = value xor (value shl 15)
        return value
    }

    fun retreatInt(seed: Int, steps: Int): Int {
        var value = seed
        repeat(steps) {
            value = value xor (value shl 15)
            value = value xor (value shl 30)
            value = value xor (value ushr 17)
            value = value xor (value shl 13)
            value = value xor (value shl 26)
        }
        return value
    }

    fun nextIndex(track: Int, steps: Int): Int {
        require(track in 0..1)
        require(steps >= 0)
        return ((track + steps) / 2) + 1
    }

    fun nextTrack(track: Int, steps: Int): Int {
        require(track in 0..1)
        require(steps >= 0)
        return ((track + steps - 1) xor 1) and 1
    }
}
