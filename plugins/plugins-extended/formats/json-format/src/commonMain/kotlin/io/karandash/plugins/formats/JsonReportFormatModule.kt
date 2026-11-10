package io.karandash.plugins.formats

import io.karandash.Version
import io.karandash.api.config.PreserveKeys
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.toFlatLocation
import io.karandash.helpers.reporter.ReporterSummary
import io.karandash.helpers.reporter.toReporterSummary
import io.karandash.models.ActivePolicy
import io.karandash.models.finding.CodeChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FindingCategory
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Location
import io.karandash.models.finding.Mismatch
import io.karandash.models.finding.codeChange
import io.karandash.models.finding.expectedFormat
import io.karandash.models.finding.mismatch
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.config.EmptyConfig
import io.karandash.sdk.typed.AbstractTypedFormatModule
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Pure formatter that converts a [ValidationReport] into a JSON string.
 *
 * Extracts the JSON serialization logic from `JsonReporterModule`. No I/O is
 * performed -- the resulting [FormattedReport.body] is delivered by an output module.
 */
class JsonReportFormatModule : AbstractTypedFormatModule<EmptyConfig>(EmptyConfig.serializer()) {
    override val id: String = ID
    override val name: String = "JSON Format"
    override val version: String = Version.VERSION
    override val formatId: String = FORMAT_ID
    override val description: String = "Formats validation results as JSON."

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.format.json"

        /** Format identifier used in [FormattedReport.formatId]. */
        const val FORMAT_ID = "json"

        /** Version of the JSON report layout; bumped only for a breaking change, additive fields keep it. */
        private const val SCHEMA_VERSION = 1
    }

    override suspend fun formatTyped(
        report: ValidationReport,
        config: EmptyConfig,
    ): FormattedReport {
        val output = JsonOutput(
            passed = report.passed,
            findings = report.findings.map { it.toJsonFinding() },
            summary = report.summary.toReporterSummary(),
            appliedPolicies = report.appliedPolicies.map { it.toJsonPolicy() }.ifEmpty { null },
            skippedRules = report.skippedRules
                .map {
                    JsonSkippedRule(
                        ruleId = it.ruleId,
                        reason = it.reason.ifEmpty { null },
                        category = it.category.name,
                    )
                }.ifEmpty { null },
        )
        val body = KarandashJson.PrettyWithDefaults.encodeToString(output)
        return FormattedReport(formatId = FORMAT_ID, body = body)
    }

    private fun Finding.toJsonFinding(): JsonFinding {
        val flat = location?.toFlatLocation()
        return JsonFinding(
            severity = io.karandash.models.finding.SeverityFormat
                .toJson(severity),
            message = message,
            rule = rule,
            location = flat?.let { JsonLocation(file = it.file, line = it.line, endLine = (location as? Location.FileRef)?.endLine) },
            suggestion = suggestion,
            explanation = explanation,
            metadata = metadata.ifEmpty { null },
            attachments = attachments.ifEmpty { null }?.map { att ->
                JsonAttachment(
                    filename = att.filename,
                    content = att.content,
                    contentType = att.contentType,
                )
            },
            expectedFormat = expectedFormat(),
            mismatch = mismatch(),
            errorCode = errorCode,
            fingerprint = fingerprint,
            priority = priority.takeIf { it != Finding.DEFAULT_PRIORITY },
            confidence = confidence,
            category = category,
            relatedFindings = relatedFindings.ifEmpty { null },
            fixSuggestions = fixSuggestions.ifEmpty { null },
            codeChange = codeChange(),
        )
    }

    private fun ActivePolicy.toJsonPolicy(): JsonPolicy =
        JsonPolicy(
            condition = condition,
            matched = matched,
            affectedModules = affectedModules,
        )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class JsonOutput(
        val schemaVersion: Int = SCHEMA_VERSION,
        val passed: Boolean,
        val findings: List<JsonFinding>,
        val summary: ReporterSummary,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val appliedPolicies: List<JsonPolicy>? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val skippedRules: List<JsonSkippedRule>? = null,
    )

    @Serializable
    private data class JsonSkippedRule(
        val ruleId: String,
        val reason: String? = null,
        val category: String? = null,
    )

    @Serializable
    private data class JsonPolicy(
        val condition: String,
        val matched: Boolean,
        val affectedModules: List<String>? = null,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class JsonFinding(
        val severity: String,
        val message: String,
        val rule: String,
        val location: JsonLocation? = null,
        val suggestion: String? = null,
        val explanation: String? = null,
        @PreserveKeys
        val metadata: Map<String, String>? = null,
        val attachments: List<JsonAttachment>? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val expectedFormat: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val mismatch: Mismatch? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val errorCode: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val fingerprint: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val priority: Int? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val confidence: Float? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val category: FindingCategory? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val relatedFindings: List<String>? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val fixSuggestions: List<FixSuggestion>? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val codeChange: CodeChange? = null,
    )

    @Serializable
    private data class JsonAttachment(
        val filename: String,
        val content: String,
        val contentType: String,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class JsonLocation(
        val file: String? = null,
        val line: Int? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val endLine: Int? = null,
    )
}
