package io.karandash.duration

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A domain duration type backed by milliseconds.
 *
 * Parses human-readable strings like `"30s"`, `"5m"`, `"2h"`, `"7d"`, `"1w"`, `"1mo"`, `"1y"`,
 * and compound forms like `"1h 30m"`. The special value `"never"` maps to [NEVER] (`-1` millis).
 *
 * Use [parse] to construct from config strings. Use the factory functions ([ofMillis], [ofSeconds],
 * etc.) for programmatic construction.
 */
@Serializable(with = KarandashDurationSerializer::class)
@kotlin.jvm.JvmInline
value class KarandashDuration private constructor(
    /** Raw milliseconds. `-1` represents [NEVER]. */
    val millis: Long,
) : Comparable<KarandashDuration> {
    /** Duration expressed in whole seconds (truncated). */
    val seconds: Long get() = millis / MS_PER_SECOND

    /** Duration expressed in whole minutes (truncated). */
    val minutes: Long get() = millis / MS_PER_MINUTE

    /** Duration expressed in whole hours (truncated). */
    val hours: Long get() = millis / MS_PER_HOUR

    /** Converts to a stdlib [Duration]. */
    fun toKotlinDuration(): Duration = millis.milliseconds

    override fun compareTo(other: KarandashDuration): Int = millis.compareTo(other.millis)

    override fun toString(): String = formatHumanReadable(millis)

    companion object {
        /** Zero duration. */
        val ZERO: KarandashDuration = KarandashDuration(0L)

        /** Sentinel meaning "no expiry / infinite". Backed by `-1` millis. */
        val NEVER: KarandashDuration = KarandashDuration(-1L)

        /** Constructs from raw milliseconds. */
        fun ofMillis(ms: Long): KarandashDuration = KarandashDuration(ms)

        /** Constructs from whole seconds. */
        fun ofSeconds(s: Long): KarandashDuration = KarandashDuration(s * MS_PER_SECOND)

        /** Constructs from whole minutes. */
        fun ofMinutes(m: Long): KarandashDuration = KarandashDuration(m * MS_PER_MINUTE)

        /** Constructs from whole hours. */
        fun ofHours(h: Long): KarandashDuration = KarandashDuration(h * MS_PER_HOUR)

        /**
         * Parses a human-readable duration string.
         *
         * Supported units: `ms`, `s`, `m`, `h`, `d`, `w`, `mo`, `y`.
         * Compound forms (e.g. `"1h 30m"`) are summed.
         * The string `"never"` returns [NEVER].
         *
         * @throws IllegalStateException if the input is invalid or a bare number without a unit.
         */
        fun parse(input: String): KarandashDuration = parseDuration(input)
    }
}
