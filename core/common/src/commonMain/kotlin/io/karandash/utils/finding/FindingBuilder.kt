package io.karandash.utils.finding

import io.karandash.models.finding.Finding
import io.karandash.models.finding.FindingCategory
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity

/**
 * DSL builder for constructing [Finding] instances.
 *
 * Usage:
 * ```
 * finding("title-format", Severity.ERROR, "Title too short") {
 *     file = "README.md"
 *     line = 42
 *     explanation = "Titles must be at least 10 characters"
 *     suggestion = "Expand the title with more detail"
 *     category = FindingCategory.CONVENTION
 * }
 * ```
 */
class FindingBuilder {
    var file: String? = null
    var line: Int? = null
    var explanation: String? = null
    var suggestion: String? = null
    var metadata: Map<String, String> = emptyMap()
    var fixSuggestions: List<FixSuggestion> = emptyList()
    var fingerprint: String? = null
    var priority: Int = Finding.DEFAULT_PRIORITY
    var confidence: Float? = null
    var relatedFindings: List<String> = emptyList()
    var category: FindingCategory? = null
    var errorCode: String? = null
}

/**
 * Creates a [Finding] using the builder DSL.
 *
 * @param rule Rule identifier (e.g. "title-format").
 * @param severity Finding severity level.
 * @param message Human-readable message.
 * @param block Optional builder block for additional fields.
 */
fun finding(
    rule: String,
    severity: Severity,
    message: String,
    block: FindingBuilder.() -> Unit = {},
): Finding {
    val builder = FindingBuilder().apply(block)
    return Finding(
        severity = severity,
        rule = rule,
        message = message,
        location = builder.file?.let { Location.FileRef(it, builder.line) },
        explanation = builder.explanation,
        suggestion = builder.suggestion,
        metadata = builder.metadata,
        fixSuggestions = builder.fixSuggestions,
        fingerprint = builder.fingerprint,
        priority = builder.priority,
        confidence = builder.confidence,
        relatedFindings = builder.relatedFindings,
        category = builder.category,
        errorCode = builder.errorCode,
    )
}
