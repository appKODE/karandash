package io.karandash.size

private val SIZE_PATTERN = Regex("""(\d+)\s*(tb|gb|mb|kb|b)""", RegexOption.IGNORE_CASE)

private const val BYTES_PER_KB = 1_024L
private const val BYTES_PER_MB = 1_048_576L
private const val BYTES_PER_GB = 1_073_741_824L
private const val BYTES_PER_TB = 1_099_511_627_776L

internal fun parseSize(input: String): KarandashSize {
    val trimmed = input.trim()

    if (trimmed.toLongOrNull() != null) {
        error(
            "Raw number '$input' not accepted for size. " +
                "Use explicit unit: '${input}b', '${input}kb', '${input}mb', etc.",
        )
    }

    val match = SIZE_PATTERN.matchEntire(trimmed)
        ?: error("Invalid size: '$input'. Use format like '1024b', '512kb', '10mb', '1gb', '1tb'")

    val amount = match.groupValues[1].toLongOrNull() ?: error("Size '$input' is too large")
    val factor = when (val unit = match.groupValues[2].lowercase()) {
        "b" -> 1L
        "kb" -> BYTES_PER_KB
        "mb" -> BYTES_PER_MB
        "gb" -> BYTES_PER_GB
        "tb" -> BYTES_PER_TB
        else -> error("Unknown size unit: $unit")
    }
    if (amount > Long.MAX_VALUE / factor) error("Size '$input' is too large")
    val bytes = amount * factor
    return KarandashSize.ofBytes(bytes)
}

internal fun formatHumanReadable(bytes: Long): String {
    if (bytes == 0L) return "0b"

    val units = listOf(
        "tb" to BYTES_PER_TB,
        "gb" to BYTES_PER_GB,
        "mb" to BYTES_PER_MB,
        "kb" to BYTES_PER_KB,
        "b" to 1L,
    )

    for ((label, factor) in units) {
        if (bytes >= factor && bytes % factor == 0L) {
            return "${bytes / factor}$label"
        }
    }
    return "${bytes}b"
}
