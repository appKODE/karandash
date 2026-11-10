package io.karandash.plugins.formats

import io.karandash.api.finding.ProvenanceStatus
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.skipReasonIn
import io.karandash.utils.takeWhole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How the summary shows the provenance block (`report.transparency.summary`). */
@Serializable
enum class TransparencySummary {
    /** No block: the summary is exactly what it was before transparency. */
    @SerialName("off")
    OFF,

    /** A collapsed `<details>` block. */
    @SerialName("collapsed")
    COLLAPSED,

    /** The same block, open. */
    @SerialName("expanded")
    EXPANDED,
}

/** Whether the summary shows the `mr-summary` walkthrough (`report.walkthrough`). */
@Serializable
enum class WalkthroughDisplay {
    @SerialName("off")
    OFF,

    @SerialName("collapsed")
    COLLAPSED,
}

/**
 * The `report.transparency` keys, injected into the markdown format config.
 *
 * @property summary Off, collapsed (default) or expanded provenance block in the summary comment.
 * @property inlineWhy When true, each inline note ends with a one-line "why" footer.
 * @property flowDiagram When true, the provenance block carries a Mermaid flow diagram of the run.
 */
@Serializable
data class MarkdownTransparencyConfig(
    val summary: TransparencySummary = TransparencySummary.COLLAPSED,
    val inlineWhy: Boolean = false,
    val flowDiagram: Boolean = false,
)

/** Longest reason, in chars, an enricher or analyzer row shows; longer ones end in an ellipsis. */
internal const val PROVENANCE_REASON_MAX = 200

/** Largest provenance block (UTF-8 bytes) the summary carries; past it the diagram goes, then the whole block. */
internal const val PROVENANCE_MAX_BYTES = 4_000

/**
 * [provenanceBlock] within [PROVENANCE_MAX_BYTES]: as configured, else without the flow diagram, else a one-line
 * note, so the summary's reserved tail stays bounded.
 */
internal fun boundedProvenanceBlock(
    provenance: ReviewProvenance?,
    config: MarkdownTransparencyConfig,
    locale: SummaryLocale = SummaryLocale.EN,
): String =
    sequenceOf(config, config.copy(flowDiagram = false))
        .map { provenanceBlock(provenance, it, locale) }
        .firstOrNull { it.encodeToByteArray().size <= PROVENANCE_MAX_BYTES }
        ?: "\n_${locale.t(SummaryText.PROVENANCE_OMITTED)}_\n"

/**
 * The provenance `<details>` block: coverage with its T1/T2 cuts, then enricher, analyzer and filter tables.
 * Empty when [config] is off or the run has nothing beyond the coverage line (non-AI runs stay unchanged).
 * Headings, table headers and status words are in [locale]; ids, models and reasons are never translated.
 */
internal fun provenanceBlock(
    provenance: ReviewProvenance?,
    config: MarkdownTransparencyConfig,
    locale: SummaryLocale = SummaryLocale.EN,
): String {
    if (provenance == null || !provenance.hasDetails || config.summary == TransparencySummary.OFF) return ""
    val open = if (config.summary == TransparencySummary.EXPANDED) " open" else ""
    return buildString {
        append("\n<details$open><summary>${locale.t(SummaryText.PROVENANCE_TITLE)}</summary>\n\n")
        provenance.coverage?.let {
            append(
                "**${locale.t(SummaryText.PROVENANCE_COVERAGE)}:** ${sanitizeMarkdown(it.describe(locale.tag))}\n\n"
            )
        }
        appendEnrichers(provenance, locale)
        appendAnalyzers(provenance, locale)
        appendFilters(provenance, locale)
        if (config.flowDiagram) append(flowDiagram(provenance, locale)).append('\n')
        append("</details>\n")
    }
}

private fun StringBuilder.appendEnrichers(
    p: ReviewProvenance,
    locale: SummaryLocale,
) {
    if (p.enrichers.isEmpty()) return
    append("${locale.t(SummaryText.ENRICHER_TABLE_HEADER)}\n|---|---|---|\n")
    p.enrichers.forEach { e ->
        val details =
            e.reason?.let { sanitizeMarkdown(cutReason(skipReasonIn(it, locale.tag))) }
                ?: if (e.tokens > 0) locale.t(SummaryText.TOKENS, e.tokens) else ""
        append("| `${sanitizeMarkdown(e.id)}` | ${e.status.label(locale)} | $details |\n")
    }
    append('\n')
}

private fun StringBuilder.appendAnalyzers(
    p: ReviewProvenance,
    locale: SummaryLocale,
) {
    if (p.analyzers.isEmpty()) return
    append("${locale.t(SummaryText.ANALYZER_TABLE_HEADER)}\n|---|---|---|---|---|\n")
    p.analyzers.forEach { a ->
        val model =
            a.model?.let { "`${sanitizeMarkdown(it)}`" }
                ?: "${a.status.label(locale)}: ${sanitizeMarkdown(cutReason(skipReasonIn(a.reason.orEmpty(), locale.tag)))}"
        append("| `${sanitizeMarkdown(a.ruleId)}` | $model | ${a.inputTokens}/${a.outputTokens} | ${usd(a.costUsd)} | ${a.findings} |\n")
    }
    append('\n')
}

private fun StringBuilder.appendFilters(
    p: ReviewProvenance,
    locale: SummaryLocale,
) {
    if (p.filters.isEmpty()) return
    append("${locale.t(SummaryText.FILTER_TABLE_HEADER)}\n|---|---|\n")
    p.filters.forEach { append("| ${sanitizeMarkdown(it.stage)} | ${it.removed} |\n") }
    append('\n')
}

private fun cutReason(reason: String): String =
    if (reason.length <= PROVENANCE_REASON_MAX) reason else reason.takeWhole(PROVENANCE_REASON_MAX - 1) + "\u2026"

internal fun ProvenanceStatus.label(locale: SummaryLocale = SummaryLocale.EN): String =
    when (this) {
        ProvenanceStatus.OK -> locale.t(SummaryText.STATUS_OK)
        ProvenanceStatus.SKIPPED -> locale.t(SummaryText.STATUS_SKIPPED)
        ProvenanceStatus.FAILED -> locale.t(SummaryText.STATUS_FAILED)
    }

private const val USD_SCALE = 10_000

/** `$0.0123`: four decimals without platform formatting (commonMain has no `String.format`). */
private fun usd(cost: Double): String {
    // a negative, NaN or infinite estimate is a provider bug, not a credit
    val scaled = if (cost.isFinite() && cost > 0) kotlin.math.round(cost * USD_SCALE).toLong() else 0L
    return "$" + (scaled / USD_SCALE) + "." + (scaled % USD_SCALE).toString().padStart(4, '0')
}
