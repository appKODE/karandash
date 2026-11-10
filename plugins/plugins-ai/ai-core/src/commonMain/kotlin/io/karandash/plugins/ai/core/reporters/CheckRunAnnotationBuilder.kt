package io.karandash.plugins.ai.core.reporters

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlinx.serialization.Serializable

/**
 * Builds platform-native code annotations from validation findings.
 * Output format is platform-agnostic - consumers map to GitHub/GitLab/Bitbucket APIs.
 */
object CheckRunAnnotationBuilder {
    /**
     * Converts findings with [Location.FileRef] locations into [CodeAnnotation] entries.
     * Findings without a file location are skipped.
     *
     * @param findings Validation findings to convert.
     * @return List of annotations for findings that have a file location.
     */
    fun build(findings: List<Finding>): List<CodeAnnotation> =
        findings.mapNotNull { finding ->
            val loc = finding.location as? Location.FileRef ?: return@mapNotNull null
            CodeAnnotation(
                path = loc.path,
                line = loc.line,
                severity = mapSeverity(finding.severity),
                title = finding.rule,
                message = finding.message,
                rawSeverity = finding.severity,
            )
        }

    /**
     * Produces a [CheckRunSummary] with conclusion and counts derived from [findings].
     *
     * @param findings All findings from the validation run.
     * @return Summary with conclusion (`failure`, `neutral`, or `success`) and counts.
     */
    fun summary(findings: List<Finding>): CheckRunSummary {
        val errors = findings.count { it.severity == Severity.ERROR }
        val warnings = findings.count { it.severity == Severity.WARNING }
        val conclusion = when {
            errors > 0 -> CONCLUSION_FAILURE
            warnings > 0 -> CONCLUSION_NEUTRAL
            else -> CONCLUSION_SUCCESS
        }
        return CheckRunSummary(
            title = CHECK_RUN_TITLE,
            conclusion = conclusion,
            totalAnnotations = findings.count { it.location is Location.FileRef },
            errors = errors,
            warnings = warnings,
        )
    }

    private fun mapSeverity(severity: Severity): String =
        when (severity) {
            Severity.ERROR -> ANNOTATION_FAILURE
            Severity.WARNING -> ANNOTATION_WARNING
            Severity.INFO -> ANNOTATION_NOTICE
        }

    private const val CHECK_RUN_TITLE = "Karandash Validation"
    private const val CONCLUSION_FAILURE = "failure"
    private const val CONCLUSION_NEUTRAL = "neutral"
    private const val CONCLUSION_SUCCESS = "success"
    private const val ANNOTATION_FAILURE = "failure"
    private const val ANNOTATION_WARNING = "warning"
    private const val ANNOTATION_NOTICE = "notice"
}

/**
 * A single code annotation derived from a [io.karandash.models.finding.Finding].
 *
 * @param path File path relative to the repository root.
 * @param line Optional line number within the file.
 * @param severity Platform-agnostic severity string: `failure`, `warning`, or `notice`.
 * @param title Rule ID used as the annotation title.
 * @param message Human-readable finding message.
 * @param rawSeverity Original [Severity] value before mapping.
 */
@Serializable
data class CodeAnnotation(
    val path: String,
    val line: Int?,
    val severity: String,
    val title: String,
    val message: String,
    val rawSeverity: Severity,
)

/**
 * Aggregated summary of a check run.
 *
 * @param title Display title for the check run.
 * @param conclusion Overall conclusion: `failure`, `neutral`, or `success`.
 * @param totalAnnotations Number of file-located findings that produced annotations.
 * @param errors Number of ERROR-severity findings.
 * @param warnings Number of WARNING-severity findings.
 */
@Serializable
data class CheckRunSummary(
    val title: String,
    val conclusion: String,
    val totalAnnotations: Int,
    val errors: Int,
    val warnings: Int,
)
