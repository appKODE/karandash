package io.karandash.percent

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * A domain percent type backed by a fraction in the range `[0.0, 1.0]`.
 *
 * Parses human-readable strings like `"70%"` (divided by 100) or bare fractions
 * like `"0.7"`. Use [parse] to construct from config strings. Use the factory
 * functions ([ofPercent], [ofFraction]) for programmatic construction.
 */
@Serializable(with = KarandashPercentSerializer::class)
@kotlin.jvm.JvmInline
value class KarandashPercent private constructor(
    /** Fraction in the range `[0.0, 1.0]`. `0.0` = 0%, `1.0` = 100%. */
    val fraction: Double,
) : Comparable<KarandashPercent> {
    init {
        require(fraction in 0.0..1.0) {
            "Percent must be between 0% and 100%, got ${(fraction * 100.0).roundToInt()}%"
        }
    }

    /** Percent value in `[0, 100]` range as a [Double]. */
    val percent: Double get() = fraction * 100.0

    /** Percent value in `[0, 100]` range rounded to nearest [Int]. */
    val percentInt: Int get() = (fraction * 100.0).roundToInt()

    override fun compareTo(other: KarandashPercent): Int = fraction.compareTo(other.fraction)

    override fun toString(): String = "$percentInt%"

    companion object {
        /** Zero percent. */
        val ZERO: KarandashPercent = KarandashPercent(0.0)

        /** One hundred percent. */
        val HUNDRED: KarandashPercent = KarandashPercent(1.0)

        /**
         * Parses a percent string.
         *
         * Accepted formats:
         * - `"70%"` → `KarandashPercent(0.70)` (percent notation, divided by 100)
         * - `"0.7"` → `KarandashPercent(0.70)` (bare fraction with decimal point, no `%`)
         * - `"100%"` → `KarandashPercent(1.0)`
         * - `"0%"` → `KarandashPercent(0.0)`
         *
         * @throws IllegalArgumentException if the input is invalid or out of range.
         */
        fun parse(input: String): KarandashPercent {
            val trimmed = input.trim()
            require(trimmed.isNotEmpty()) { "Percent string must not be empty" }
            require(trimmed.endsWith("%") || '.' in trimmed) {
                "Ambiguous percent value '$input'. Use '$input%' for percent or '0.$input' for fraction."
            }

            return if (trimmed.endsWith("%")) {
                val numeric = trimmed.removeSuffix("%").trim()
                val value = requireNotNull(numeric.toDoubleOrNull()) {
                    "Invalid percent value: '$input'"
                }
                KarandashPercent(value / 100.0)
            } else {
                val value = requireNotNull(trimmed.toDoubleOrNull()) {
                    "Invalid percent fraction: '$input'"
                }
                KarandashPercent(value)
            }
        }

        /** Constructs from a fraction in `[0.0, 1.0]`. */
        fun ofFraction(d: Double): KarandashPercent = KarandashPercent(d)

        /** Constructs from a whole percent in `[0, 100]`. */
        fun ofPercent(p: Int): KarandashPercent = KarandashPercent(p / 100.0)
    }
}
