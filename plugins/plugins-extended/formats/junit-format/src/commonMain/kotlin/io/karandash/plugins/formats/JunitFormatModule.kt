package io.karandash.plugins.formats

import io.karandash.Version
import io.karandash.api.ReporterConfig
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.helpers.reporter.displayName
import io.karandash.helpers.reporter.escapeXml
import io.karandash.helpers.reporter.facts
import io.karandash.helpers.reporter.toRangeDisplayString
import io.karandash.models.finding.FIX_HINT_METADATA_KEYS
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FindingField
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.finding.codeChange
import io.karandash.models.finding.fixHintLines
import io.karandash.models.finding.groupedByRule
import io.karandash.sdk.typed.AbstractTypedFormatModule
import io.karandash.utils.finding.toDisplayString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which findings a JUnit test case fails on (`reporters.junit.failOn`).
 *
 * A severity fails the findings of that severity and above; [THRESHOLD] follows the run's own fail threshold:
 * errors, and warnings too under `--strict`.
 */
@Serializable
enum class JunitFailOn {
    @SerialName("error")
    ERROR,

    @SerialName("warning")
    WARNING,

    @SerialName("info")
    INFO,

    @SerialName("threshold")
    THRESHOLD,
}

/**
 * Config for [JunitFormatModule] (`reporters.junit`).
 *
 * @param includeAiMetadata Lists every metadata key of a finding, also those the report shows elsewhere (sections,
 *   scores, suggested code); by default only the others are listed.
 * @param language Language of the fix-hint labels; set from `report.language` by the pipeline.
 * @param failOn Lowest severity marked `<failure>`; the rest pass with a `<system-out>`. Default: warnings and errors.
 * @param strict Set from the CLI `--strict` flag by the pipeline; read only by `failOn: threshold`.
 */
@Serializable
data class JunitFormatConfig(
    val includeAiMetadata: Boolean = false,
    val language: String? = null,
    val failOn: JunitFailOn = JunitFailOn.WARNING,
    val strict: Boolean = false,
) : ReporterConfig {
    /** The lowest failing severity. */
    internal val failSeverity: Severity
        get() = when (failOn) {
            JunitFailOn.ERROR -> Severity.ERROR
            JunitFailOn.WARNING -> Severity.WARNING
            JunitFailOn.INFO -> Severity.INFO
            JunitFailOn.THRESHOLD -> if (strict) Severity.WARNING else Severity.ERROR
        }
}

/**
 * Pure formatter that converts a [ValidationReport] into JUnit XML.
 *
 * Extracts the JUnit XML generation logic from `JunitXmlReporterModule`. Each
 * validation rule is mapped to a `<testsuite>` and each finding to one `<testcase>`: findings at or above
 * [JunitFormatConfig.failOn] (default WARNING) carry a `<failure>`, the rest pass with a `<system-out>`.
 *
 * No I/O is performed -- the resulting [FormattedReport.body] is delivered by an output module.
 */
class JunitFormatModule : AbstractTypedFormatModule<JunitFormatConfig>(JunitFormatConfig.serializer()) {
    override val id: String = ID
    override val name: String = "JUnit Format"
    override val version: String = Version.VERSION
    override val formatId: String = FORMAT_ID
    override val description: String = "Formats validation results as JUnit XML for CI test result integration."

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.format.junit"

        /** Format identifier used in [FormattedReport.formatId]. */
        const val FORMAT_ID = "junit"

        private const val COMMIT_PREFIX = 8
    }

    override suspend fun formatTyped(
        report: ValidationReport,
        config: JunitFormatConfig,
    ): FormattedReport {
        val body = buildXml(report.findings, config)
        return FormattedReport(formatId = FORMAT_ID, body = body)
    }

    private fun buildXml(
        findings: List<Finding>,
        config: JunitFormatConfig,
    ): String {
        val byRule = findings.groupedByRule()
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        val failures = findings.count { it.isFailure(config) }
        sb.append("<testsuites name=\"karandash\" tests=\"${findings.size}\" failures=\"$failures\" errors=\"0\">\n")
        for ((rule, ruleFindings) in byRule) {
            val ruleFailures = ruleFindings.count { it.isFailure(config) }
            sb.append(
                "  <testsuite name=\"${rule.escapeXml()}\" tests=\"${ruleFindings.size}\" failures=\"$ruleFailures\" errors=\"0\">\n",
            )
            ruleFindings.forEachIndexed { index, finding -> appendTestCase(sb, rule, index + 1, finding, config) }
            sb.append("  </testsuite>\n")
        }
        sb.append("</testsuites>\n")
        return sb.toString()
    }

    private fun Finding.isFailure(config: JunitFormatConfig): Boolean = severity.ordinal <= config.failSeverity.ordinal

    private fun appendTestCase(
        sb: StringBuilder,
        rule: String,
        number: Int,
        finding: Finding,
        config: JunitFormatConfig,
    ) {
        val where = when (val location = finding.location) {
            is Location.CommitRef -> "commit:${location.sha.take(COMMIT_PREFIX)}"
            null -> "#$number"
            else -> location.toDisplayString()
        }
        sb.append("    <testcase name=\"${"$rule $where".escapeXml()}\" classname=\"karandash.rules.${rule.escapeXml()}\">\n")
        val body = buildFindingBody(finding, config).escapeXml()
        if (finding.isFailure(config)) {
            sb.append("      <failure message=\"${finding.message.escapeXml()}\" type=\"${finding.severity.name}\">\n")
            sb.append(body)
            sb.append("\n      </failure>\n")
        } else {
            sb.append("      <system-out>").append(body).append("</system-out>\n")
        }
        sb.append("    </testcase>\n")
    }

    private fun buildFindingBody(
        finding: Finding,
        config: JunitFormatConfig,
    ): String =
        buildString {
            append("Rule: ${finding.rule}")
            append("\nSeverity: ${finding.severity.name}")
            append("\nMessage: ${finding.message}")
            finding.fixHintLines(config.language).forEach { append("\n$it") }
            finding.explanation?.let { append("\nExplanation: $it") }
            finding.location?.let { append("\nLocation: ${it.toRangeDisplayString()}") }
            finding.errorCode?.let { append("\nError code: $it") }
            finding.confidence?.let { append("\nConfidence: $it") }
            // includeAiMetadata shows every key; otherwise the keys rendered elsewhere stay out
            val hidden = if (config.includeAiMetadata) FIX_HINT_METADATA_KEYS else FindingField.RENDERED_METADATA_KEYS
            for ((key, value) in finding.metadata - hidden) append("\n$key: $value")
            finding.codeChange()?.let { append("\nSuggested change (lines ${it.startLine}-${it.endLine}):\n${it.suggestedCode}") }
            for ((key, value) in finding.facts().filter { it.first != "confidence" }) append("\n$key: $value")
            for (attachment in finding.attachments) {
                append("\n--- ${attachment.displayName} ---\n")
                append(attachment.content)
            }
        }
}
