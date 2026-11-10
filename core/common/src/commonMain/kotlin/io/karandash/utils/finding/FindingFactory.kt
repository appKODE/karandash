package io.karandash.utils.finding

import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Severity

/**
 * Factory for creating [Finding] instances with consistent structure.
 * All public methods delegate to the private [make] function, which builds through the [finding] DSL
 * (one construction path for every core rule finding).
 */
object FindingFactory {
    private fun make(
        severity: Severity,
        rule: String,
        message: String,
        file: String?,
        line: Int?,
        explanation: String?,
        suggestion: String?,
        metadata: Map<String, String>,
        fixSuggestions: List<FixSuggestion>,
    ) = finding(rule, severity, message) {
        this.file = file
        this.line = line
        this.explanation = explanation
        this.suggestion = suggestion
        this.metadata = metadata
        this.fixSuggestions = fixSuggestions
    }

    fun error(
        rule: String,
        message: String,
        file: String? = null,
        line: Int? = null,
        explanation: String? = null,
        suggestion: String? = null,
        metadata: Map<String, String> = emptyMap(),
        fixSuggestions: List<FixSuggestion> = emptyList(),
    ) = make(Severity.ERROR, rule, message, file, line, explanation, suggestion, metadata, fixSuggestions)

    fun warn(
        rule: String,
        message: String,
        file: String? = null,
        line: Int? = null,
        explanation: String? = null,
        suggestion: String? = null,
        metadata: Map<String, String> = emptyMap(),
        fixSuggestions: List<FixSuggestion> = emptyList(),
    ) = make(Severity.WARNING, rule, message, file, line, explanation, suggestion, metadata, fixSuggestions)

    fun info(
        rule: String,
        message: String,
        file: String? = null,
        line: Int? = null,
        explanation: String? = null,
        suggestion: String? = null,
        metadata: Map<String, String> = emptyMap(),
        fixSuggestions: List<FixSuggestion> = emptyList(),
    ) = make(Severity.INFO, rule, message, file, line, explanation, suggestion, metadata, fixSuggestions)
}
