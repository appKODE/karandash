package io.karandash.plugins.ai.core.architecture

import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.utils.GlobMatcher
import kotlinx.serialization.Serializable

/**
 * Living ADR (Architecture Decision Record) validation.
 *
 * Validates that MR changes are consistent with documented ADRs.
 * ADR constraints are expressed as file path glob patterns combined with
 * forbidden or required code patterns.
 */
object AdrValidator {
    /**
     * Validates the MR against a set of ADR constraints.
     *
     * @param mr The merge request to validate.
     * @param adrs List of ADR constraints to check against.
     * @return List of findings for ADR violations; empty when all constraints pass.
     */
    fun validate(
        mr: MergeRequest,
        adrs: List<AdrConstraint>
    ): List<Finding> {
        val findings = mutableListOf<Finding>()

        for (adr in adrs) {
            val affectedFiles = mr.changes.filter { change ->
                adr.affectedPaths.any { pattern -> matchesGlob(change.path, pattern) }
            }

            if (affectedFiles.isEmpty()) continue

            findings += checkForbiddenPatterns(adr, affectedFiles)
            findings += checkRequiredPatterns(adr, affectedFiles)
        }

        return findings
    }

    private fun checkForbiddenPatterns(
        adr: AdrConstraint,
        affectedFiles: List<FileChange>,
    ): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (forbidden in adr.forbiddenPatterns) {
            for (file in affectedFiles) {
                val diff = file.diffContent ?: continue
                if (violates(diff, forbidden, adr.allowedPatterns)) {
                    findings.add(
                        Finding(
                            severity = Severity.fromString(adr.severity, default = Severity.WARNING),
                            message = "ADR '${adr.name}' violated: forbidden pattern '$forbidden' found in ${file.path}",
                            rule = "adr:${adr.id}",
                            explanation = adr.rationale.ifEmpty { null },
                        ),
                    )
                }
            }
        }
        return findings
    }

    /** True when [forbidden] is in [diff] on a line that holds none of the [allowed] patterns. */
    private fun violates(
        diff: String,
        forbidden: String,
        allowed: List<String>,
    ): Boolean {
        if (allowed.isEmpty()) return diff.contains(forbidden)
        return diff.lineSequence().any { line -> line.contains(forbidden) && allowed.none { line.contains(it) } }
    }

    private fun checkRequiredPatterns(
        adr: AdrConstraint,
        affectedFiles: List<FileChange>,
    ): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (required in adr.requiredPatterns) {
            val anyMatch = affectedFiles.any { file ->
                file.diffContent?.contains(required) == true
            }
            if (!anyMatch) {
                findings.add(
                    Finding(
                        severity = Severity.fromString(adr.severity, default = Severity.WARNING),
                        message = "ADR '${adr.name}': required pattern '$required' not found in affected files",
                        rule = "adr:${adr.id}",
                        explanation = adr.rationale.ifEmpty { null },
                    ),
                )
            }
        }
        return findings
    }

    internal fun matchesGlob(
        path: String,
        pattern: String
    ): Boolean = GlobMatcher.matches(path, pattern)
}

/**
 * An architecture decision record constraint.
 *
 * @param id Unique ADR identifier (e.g. "ADR-001").
 * @param name Human-readable name (e.g. "No direct database access from controllers").
 * @param affectedPaths Glob patterns for files this ADR applies to.
 * @param forbiddenPatterns Code patterns that violate this ADR when found in a diff.
 * @param requiredPatterns Code patterns that must be present in the diff of at least one affected file.
 * @param rationale Why this ADR exists; included in finding explanations.
 * @param severity Finding severity when violated ("error", "warning", or "info").
 * @param allowedPatterns Code patterns that exempt a forbidden match on the same diff line.
 */
@Serializable
data class AdrConstraint(
    val id: String,
    val name: String,
    val affectedPaths: List<String> = emptyList(),
    val forbiddenPatterns: List<String> = emptyList(),
    val requiredPatterns: List<String> = emptyList(),
    val rationale: String = "",
    val severity: String = "warning",
    val allowedPatterns: List<String> = emptyList(),
)
