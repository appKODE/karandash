package io.karandash.models.finding

/**
 * Formatting helpers for [Severity] across different output formats.
 *
 * Centralises format-specific severity strings so reporters do not duplicate
 * `when` blocks for SARIF, Reviewdog, JSON, and ANSI colour output.
 */
object SeverityFormat {
    private const val ERROR = "error"
    private const val INFO = "info"

    /** SARIF severity level string. */
    fun toSarif(severity: Severity): String =
        when (severity) {
            Severity.ERROR -> ERROR
            Severity.WARNING -> "warning"
            Severity.INFO -> "note"
        }

    /** Reviewdog severity level string. */
    fun toReviewdog(severity: Severity): String =
        when (severity) {
            Severity.ERROR -> "ERROR"
            Severity.WARNING -> "WARNING"
            Severity.INFO -> "INFO"
        }

    /** JSON-friendly lowercase severity string. */
    fun toJson(severity: Severity): String =
        when (severity) {
            Severity.ERROR -> ERROR
            Severity.WARNING -> "warning"
            Severity.INFO -> INFO
        }

    /** The severity icon of a human-facing report: ❌, ⚠️ or ℹ️. */
    fun toEmoji(severity: Severity): String =
        when (severity) {
            Severity.ERROR -> "\u274C"
            Severity.WARNING -> "\u26A0\uFE0F"
            Severity.INFO -> "\u2139\uFE0F"
        }

    /** A short severity word for terminal columns: `error`, `warn`, `info`. */
    fun toTag(severity: Severity): String =
        when (severity) {
            Severity.ERROR -> ERROR
            Severity.WARNING -> "warn"
            Severity.INFO -> INFO
        }

    /** ANSI colour escape code for terminal output. */
    fun toAnsiColor(severity: Severity): String =
        when (severity) {
            Severity.ERROR -> "\u001b[31m"

            // red
            Severity.WARNING -> "\u001b[33m"

            // yellow
            Severity.INFO -> "\u001b[36m" // cyan
        }
}
