package io.karandash.models.finding

import kotlinx.serialization.Serializable

@Serializable
enum class Severity {
    ERROR,
    WARNING,
    INFO;

    companion object {
        fun fromString(
            value: String,
            default: Severity = INFO,
        ): Severity = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: default
    }
}
