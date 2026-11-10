package io.karandash.plugins.ai.core

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Formats a [Double] to [decimals] decimal places.
 *
 * KMP-compatible alternative to `String.format()` which is JVM-only.
 *
 * Examples:
 * - `3.14159.formatDecimals(1)` → `"3.1"`
 * - `3.14159.formatDecimals(2)` → `"3.14"`
 * - `42.0.formatDecimals(0)` → `"42"`
 *
 * @param decimals Number of decimal places (0 for integer formatting).
 * @return Formatted string representation.
 */
fun Double.formatDecimals(decimals: Int): String {
    if (decimals <= 0) return this.roundToLong().toString()
    val factor = 10.0.pow(decimals)
    val rounded = (this * factor).roundToLong()
    val wholePart = rounded / factor.toLong()
    val fracPart = kotlin.math.abs(rounded % factor.toLong())
    return "$wholePart.${fracPart.toString().padStart(decimals, '0')}"
}
