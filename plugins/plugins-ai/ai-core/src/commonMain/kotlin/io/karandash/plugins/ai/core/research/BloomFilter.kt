package io.karandash.plugins.ai.core.research

import kotlin.math.pow

/**
 * Bloom filter for O(1) probabilistic membership testing.
 * Used for pre-screening: skip rules instantly when no relevant patterns are possible.
 *
 * False positives possible, false negatives impossible.
 */
class BloomFilter(
    private val size: Int = DEFAULT_SIZE,
    private val hashCount: Int = DEFAULT_HASH_COUNT,
) {
    private val bits = BooleanArray(size)
    private var itemCount: Int = 0

    /** Adds [item] to the filter. */
    fun add(item: String) {
        for (i in 0 until hashCount) {
            val idx = hash(item, i)
            bits[idx] = true
        }
        itemCount++
    }

    /**
     * Returns true if [item] might be in the filter.
     * False negatives are impossible; false positives are possible.
     */
    fun mightContain(item: String): Boolean {
        for (i in 0 until hashCount) {
            if (!bits[hash(item, i)]) return false
        }
        return true
    }

    /** Returns the number of items added to this filter. */
    fun itemCount(): Int = itemCount

    /**
     * Estimated false positive probability based on current fill ratio.
     */
    fun falsePositiveProbability(): Double {
        val fillRatio = bits.count { it }.toDouble() / size
        return fillRatio.pow(hashCount.toDouble())
    }

    private fun hash(
        item: String,
        seed: Int
    ): Int {
        var h = seed * HASH_SEED_MULTIPLIER
        for (c in item) {
            h = h * HASH_CHAR_MULTIPLIER + c.code
        }
        return (h and Int.MAX_VALUE) % size
    }

    companion object {
        private const val DEFAULT_SIZE = 1024
        private const val DEFAULT_HASH_COUNT = 3
        private const val HASH_SEED_MULTIPLIER = 131
        private const val HASH_CHAR_MULTIPLIER = 31

        /**
         * Creates a pre-built bloom filter from a list of patterns.
         * Useful for pre-screening file paths against rule patterns.
         *
         * @param patterns The patterns to add.
         * @param size The bit array size; larger values reduce false positive rate.
         * @return A [BloomFilter] populated with all [patterns].
         */
        fun fromPatterns(
            patterns: List<String>,
            size: Int = DEFAULT_SIZE
        ): BloomFilter {
            val filter = BloomFilter(size)
            for (pattern in patterns) {
                filter.add(pattern)
            }
            return filter
        }
    }
}
