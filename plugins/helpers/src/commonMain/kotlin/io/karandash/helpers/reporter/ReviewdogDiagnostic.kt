package io.karandash.helpers.reporter

import io.karandash.api.errors.KarandashError
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.toFlatLocation
import io.karandash.models.finding.FIX_HINT_METADATA_KEYS
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.SeverityFormat
import io.karandash.models.finding.codeChange
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Project URL reported as the reviewdog `source.url` and the SARIF `informationUri`. */
const val PROJECT_URL: String = "https://github.com/appKODE/karandash"

/** Config of the `rdjson` and `rdjsonl` formats: the fix-hint [language] the pipeline takes from `report.language`. */
@Serializable
data class ReviewdogFormatConfig(
    val language: String? = null,
)

/** The reviewdog `Diagnostic` shared by `rdjson` (inside an envelope) and `rdjsonl` (one per line). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RdDiagnostic(
    val message: String,
    val location: RdLocation? = null,
    val severity: String? = null,
    val code: RdCode? = null,
    @SerialName("original_output")
    val originalOutput: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val suggestions: List<RdSuggestion>? = null,
)

/** A reviewdog `Suggestion`: replace [range] by [text]. */
@Serializable
data class RdSuggestion(
    val range: RdRange,
    val text: String,
)

@Serializable
data class RdLocation(
    val path: String,
    val range: RdRange? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RdRange(
    val start: RdPosition,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val end: RdPosition? = null,
)

@Serializable
data class RdPosition(
    val line: Int? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RdCode(
    val value: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val url: String? = null,
)

/** The diagnostics of [report], one per finding; a finding without its own explanation gets the rule's. */
fun ValidationReport.toRdDiagnostics(language: String? = null): List<RdDiagnostic> =
    findings.map { finding ->
        val diagnostic = finding.toRdDiagnostic(language)
        if (finding.explanation == null) diagnostic.copy(originalOutput = explanations[finding.rule]) else diagnostic
    }

/**
 * A [Finding] as a reviewdog [RdDiagnostic]; `location` is omitted when the finding has no file or commit. The
 * message is [plainTextBody] with metadata and [facts] as one `key: value, ...` paragraph; a [codeChange] becomes a
 * suggestion (its lines are a hint for reviewdog, which checks them against the diff).
 */
fun Finding.toRdDiagnostic(language: String? = null): RdDiagnostic {
    val flat = location?.toFlatLocation()
    val shown = (metadata - FIX_HINT_METADATA_KEYS).toList() + facts()
    val messageText = plainTextBody(language, shown.joinToString(", ") { (k, v) -> "$k: $v" })
    val endLine = (location as? Location.FileRef)?.endLine
    val range = flat?.line?.let { line -> RdRange(RdPosition(line), endLine?.takeIf { it > line }?.let(::RdPosition)) }
    val suggestion = codeChange()?.let { RdSuggestion(RdRange(RdPosition(it.startLine), RdPosition(it.endLine)), it.suggestedCode) }
    return RdDiagnostic(
        message = messageText,
        location = flat?.file?.let { RdLocation(it, range) },
        severity = SeverityFormat.toReviewdog(severity),
        code = RdCode(rule, errorCode?.let { KarandashError.fromCode(it)?.docsUrl }),
        originalOutput = explanation,
        suggestions = suggestion?.let(::listOf),
    )
}
