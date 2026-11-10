package io.karandash.plugins.formats

import io.karandash.Version
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.helpers.reporter.PROJECT_URL
import io.karandash.helpers.reporter.RdDiagnostic
import io.karandash.helpers.reporter.ReviewdogFormatConfig
import io.karandash.helpers.reporter.toRdDiagnostics
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.typed.AbstractTypedFormatModule
import kotlinx.serialization.Serializable

/**
 * Pure formatter that converts a [ValidationReport] into Reviewdog JSON (rdjson) format.
 *
 * Extracts the Reviewdog JSON generation from `RdjsonReporterModule`. No I/O is performed --
 * the resulting [FormattedReport.body] is delivered by an output module.
 */
class RdjsonFormatModule : AbstractTypedFormatModule<ReviewdogFormatConfig>(ReviewdogFormatConfig.serializer()) {
    override val id: String = ID
    override val name: String = "RDJSON Format"
    override val version: String = Version.VERSION
    override val formatId: String = FORMAT_ID
    override val description: String = "Formats validation results in Reviewdog JSON format (rdjson)."

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.format.rdjson"

        /** Format identifier used in [FormattedReport.formatId]. */
        const val FORMAT_ID = "rdjson"
    }

    override suspend fun formatTyped(
        report: ValidationReport,
        config: ReviewdogFormatConfig,
    ): FormattedReport {
        val output = RdjsonOutput(
            source = RdSource(name = "karandash", url = PROJECT_URL),
            diagnostics = report.toRdDiagnostics(config.language),
        )
        val body = KarandashJson.Plain.encodeToString(output)
        return FormattedReport(formatId = FORMAT_ID, body = body)
    }
}

@Serializable
internal data class RdjsonOutput(
    val source: RdSource,
    val diagnostics: List<RdDiagnostic>,
)

@Serializable
internal data class RdSource(
    val name: String,
    val url: String,
)
