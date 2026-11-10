package io.karandash.utils

import io.karandash.duration.KarandashDuration
import kotlin.math.absoluteValue

/**
 * Formats millisecond durations into human-readable strings.
 *
 * one format for every duration karandash prints, the one [KarandashDuration] uses for config values:
 * each non-zero unit from years down to milliseconds, e.g. `123ms`, `5s 230ms`, `4m 53s 595ms`, `1h 12m 3s`.
 */
object DurationFormatter {
    /**
     * Formats a duration given in whole milliseconds.
     *
     * @param ms duration in milliseconds (negative values are formatted with their absolute value)
     * @return human-readable duration string
     */
    fun formatMs(ms: Long): String = KarandashDuration.ofMillis(ms.absoluteValue).toString()
}
