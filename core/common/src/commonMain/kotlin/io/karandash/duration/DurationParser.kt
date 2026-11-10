package io.karandash.duration

private val DURATION_PATTERN = Regex("""(\d+)\s*(ms|mo|[smhdwy])""")

// the one set of unit constants, shared with [KarandashDuration] and [formatHumanReadable].
internal const val MS_PER_SECOND = 1_000L
internal const val MS_PER_MINUTE = 60_000L
internal const val MS_PER_HOUR = 3_600_000L
private const val MS_PER_DAY = 86_400_000L
private const val MS_PER_YEAR = 365 * MS_PER_DAY

private const val DAYS_PER_WEEK = 7L
private const val DAYS_PER_MONTH = 30L
private const val DAYS_PER_YEAR = 365L

internal fun parseDuration(input: String): KarandashDuration {
    val trimmed = input.trim()

    if (trimmed == "never" || trimmed == "-1") return KarandashDuration.NEVER

    if (trimmed.toLongOrNull() != null) {
        error(
            "Raw number '$input' not accepted for duration. " +
                "Use explicit unit: '${input}s', '${input}ms', '${input}m', etc.",
        )
    }

    val matches = DURATION_PATTERN.findAll(trimmed).toList()
    // Text outside the unit groups (`1h30`, `2hours`, `-5s`) is an error, not ignored.
    if (matches.isEmpty() || DURATION_PATTERN.replace(trimmed, "").isNotBlank()) {
        error("Invalid duration: '$input'. Use format like '30s', '5m', '2h', '7d', '1y' or compound '1h 30m'")
    }
    val totalMillis = matches.fold(0L) { total, match ->
        val amount = match.groupValues[1].toLongOrNull() ?: error("Duration '$input' is too large")
        val factor = unitMillis(match.groupValues[2])
        if (amount > (Long.MAX_VALUE - total) / factor) error("Duration '$input' is too large")
        total + amount * factor
    }

    return KarandashDuration.ofMillis(totalMillis)
}

private fun unitMillis(unit: String): Long =
    when (unit) {
        "ms" -> 1L
        "s" -> MS_PER_SECOND
        "m" -> MS_PER_MINUTE
        "h" -> MS_PER_HOUR
        "d" -> MS_PER_DAY
        "w" -> DAYS_PER_WEEK * MS_PER_DAY
        "mo" -> DAYS_PER_MONTH * MS_PER_DAY
        "y" -> DAYS_PER_YEAR * MS_PER_DAY
        else -> error("Unknown duration unit: $unit")
    }

internal fun formatHumanReadable(millis: Long): String {
    if (millis < 0) return "never"
    if (millis == 0L) return "0s"

    val units = listOf(
        "y" to MS_PER_YEAR,
        "d" to MS_PER_DAY,
        "h" to MS_PER_HOUR,
        "m" to MS_PER_MINUTE,
        "s" to MS_PER_SECOND,
        "ms" to 1L,
    )

    val parts = mutableListOf<String>()
    var remaining = millis
    for ((label, factor) in units) {
        if (remaining >= factor) {
            val count = remaining / factor
            remaining %= factor
            parts += "$count$label"
        }
    }
    return parts.joinToString(" ")
}
