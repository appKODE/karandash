package io.karandash.testfixtures

import io.karandash.api.finding.LocationKey
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity

private const val DEFAULT_TEST_RULE = "test-rule"

/**
 * Shared test factory for [Finding] instances.
 *
 * All parameters have sensible defaults. Override only what is relevant to the test.
 */
fun createTestFinding(
    ruleId: String = DEFAULT_TEST_RULE,
    severity: Severity = Severity.WARNING,
    message: String = "Test finding message",
    file: String? = null,
    line: Int? = null,
    explanation: String? = null,
    suggestion: String? = null,
    metadata: Map<String, String> = emptyMap(),
    fixSuggestions: List<FixSuggestion> = emptyList(),
): Finding =
    Finding(
        severity = severity,
        rule = ruleId,
        message = message,
        location = file?.let { Location.FileRef(it, line) },
        explanation = explanation,
        suggestion = suggestion,
        metadata = metadata,
        fixSuggestions = fixSuggestions,
    )

/** Shortcut for an error-severity finding. */
fun createTestError(
    ruleId: String = DEFAULT_TEST_RULE,
    message: String = "Test error finding",
    file: String? = null,
    line: Int? = null,
): Finding = createTestFinding(ruleId = ruleId, severity = Severity.ERROR, message = message, file = file, line = line)

/** Shortcut for a warning-severity finding. */
fun createTestWarning(
    ruleId: String = DEFAULT_TEST_RULE,
    message: String = "Test warning finding",
    file: String? = null,
    line: Int? = null,
): Finding = createTestFinding(ruleId = ruleId, severity = Severity.WARNING, message = message, file = file, line = line)

/** Shortcut for an info-severity finding. */
fun createTestInfo(
    ruleId: String = DEFAULT_TEST_RULE,
    message: String = "Test info finding",
): Finding = createTestFinding(ruleId = ruleId, severity = Severity.INFO, message = message)

/**
 * Shared test factory for a [ValidationReport] over [findings], as the pipeline builds it for format modules:
 * findings with a file location are inline-eligible and grouped by location, the rest are summary-only.
 */
fun createTestReport(
    findings: List<Finding> = listOf(createTestError(file = "src/App.kt", line = 3), createTestWarning()),
    version: String = "test",
): ValidationReport {
    val located = findings.mapNotNull { f -> (f.location as? Location.FileRef)?.let { f to LocationKey(it.path, it.line) } }
    val inline = located.map { it.first }
    return ValidationReport(
        passed = findings.none { it.severity == Severity.ERROR },
        findings = findings,
        summary = findings.summarize(),
        groupedByLocation = located.groupBy({ it.second }, { it.first }),
        inlineEligible = inline,
        summaryOnly = findings.filterNot { it in inline },
        complianceChecklist = emptyList(),
        explanations = emptyMap(),
        metadata = ReportMetadata(version = version, timestamp = 0L),
    )
}
