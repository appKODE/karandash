package io.karandash.plugins.formats

import io.karandash.Version
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.sdk.typed.AbstractTypedFormatModule
import io.karandash.utils.finding.FindingFingerprint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Config of the `codequality` format: none, the CodeClimate issue shape is fixed. */
@Serializable
class CodeQualityFormatConfig

/**
 * Pure formatter that converts a [ValidationReport] into a GitLab Code Quality report: a JSON array of CodeClimate
 * issues, uploaded by CI as `artifacts:reports:codequality` and shown in the MR widget and diff. A finding without a
 * file has no place in it and is left out.
 */
class CodeQualityFormatModule : AbstractTypedFormatModule<CodeQualityFormatConfig>(CodeQualityFormatConfig.serializer()) {
    override val id: String = ID
    override val name: String = "Code Quality Format"
    override val version: String = Version.VERSION
    override val formatId: String = FORMAT_ID
    override val description: String = "Formats validation results as a GitLab Code Quality (CodeClimate) report."

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.format.codequality"

        /** Format identifier used in [FormattedReport.formatId]. */
        const val FORMAT_ID = "codequality"
    }

    override suspend fun formatTyped(
        report: ValidationReport,
        config: CodeQualityFormatConfig,
    ): FormattedReport {
        val issues = buildJsonArray {
            report.findings.forEach { finding ->
                val file = finding.location as? Location.FileRef ?: return@forEach
                addJsonObject { issue(finding, file) }
            }
        }
        return FormattedReport(formatId = FORMAT_ID, body = issues.toString())
    }

    private fun JsonObjectBuilder.issue(
        finding: Finding,
        file: Location.FileRef,
    ) {
        put("type", "issue")
        put("description", finding.message)
        put("check_name", finding.rule)
        put("fingerprint", finding.fingerprint ?: FindingFingerprint.generate(finding.rule, finding.location, finding.message))
        put("severity", finding.severity.codeQuality())
        val body = listOfNotNull("Severity: ${finding.severity.name.lowercase()}", finding.explanation, finding.suggestion)
        putJsonObject("content") { put("body", body.joinToString("\n\n")) }
        putJsonObject("location") {
            put("path", file.path.removePrefix("./"))
            putJsonObject("lines") {
                put("begin", file.line ?: 1)
                file.endLine?.let { put("end", it) }
            }
        }
    }

    private fun Severity.codeQuality(): String =
        when (this) {
            Severity.ERROR -> "major"
            Severity.WARNING -> "minor"
            Severity.INFO -> "info"
        }
}
