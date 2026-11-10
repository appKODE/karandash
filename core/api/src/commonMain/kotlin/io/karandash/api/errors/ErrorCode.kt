package io.karandash.api.errors

import kotlinx.serialization.Serializable

/**
 * Represents a catalogued error with structured metadata for enhanced error reporting.
 */
@Serializable
public data class ErrorCode(
    val code: String,
    val title: String,
    val category: ErrorCategory,
    val severity: ErrorSeverity,
    val messageTemplate: String,
    val suggestion: String?,
    val docsUrl: String?
)

public enum class ErrorCategory {
    CONFIG,
    PROVIDER,
    VALIDATION,
    PLUGIN
}

public enum class ErrorSeverity {
    FATAL,
    ERROR,
    WARNING,
    INFO
}
