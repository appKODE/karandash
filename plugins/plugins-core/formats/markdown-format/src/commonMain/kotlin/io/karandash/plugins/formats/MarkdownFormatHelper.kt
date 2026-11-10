package io.karandash.plugins.formats

import io.karandash.api.finding.ComplianceItem
import io.karandash.api.finding.ComplianceStatus
import io.karandash.api.finding.InlineCommentData
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.skipReasonIn
import io.karandash.api.finding.summarize
import io.karandash.helpers.reporter.facts
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FindingField
import io.karandash.models.finding.FixHintText
import io.karandash.models.finding.Location
import io.karandash.models.finding.PresentationKeys
import io.karandash.models.finding.Severity
import io.karandash.models.finding.codeSpan
import io.karandash.models.finding.expectedFormat
import io.karandash.models.finding.mismatch
import io.karandash.models.support.WellKnownMetadataKeys
import kotlinx.serialization.Serializable

/**
 * Pure formatting helpers for [MarkdownFormatModule].
 *
 * All functions are package-private or internal and stateless -- no I/O is performed.
 */

private val SUGGESTED_CODE_KEY = WellKnownMetadataKeys.SUGGESTED_CODE
private const val DETAILS_CLOSE_TAG = "</details>"
private const val FENCE = "```"
private const val FINDINGS_TABLE_SEPARATOR = "|----------|------|---------|------------|"

/** Summary comments larger than this (UTF-8 bytes) collapse their long sections. */
internal const val SUMMARY_MAX_BYTES = 10_000
private const val COMPACT_ROW_BUDGET_BYTES = SUMMARY_MAX_BYTES / 2
private const val COMPACT_LIST_ENTRIES = 15
private const val PROJECT_LINK = "[karandash](https://github.com/karandash/karandash)"

private val SummaryLocale.footer: String
    get() = "\n---\n_${t(SummaryText.GENERATED_BY)} ${PROJECT_LINK} \u00B7 ${t(SummaryText.FEEDBACK_HINT)}_\n"
private val SummaryLocale.truncatedNote: String get() = "\n_${t(SummaryText.TRUNCATED)}_\n"

/**
 * Metadata keys shown as `key: value` chips although another part of the report reads them; see [chips].
 */
internal val METADATA_DISPLAY_KEYS = setOf(
    WellKnownMetadataKeys.DIMENSION,
    WellKnownMetadataKeys.BUG_INTRODUCED_BY,
    WellKnownMetadataKeys.FIXED_BY,
    WellKnownMetadataKeys.DRIFT_SCORE,
    WellKnownMetadataKeys.OVERALL_SCORE,
    WellKnownMetadataKeys.ERROR,
)

/**
 * The `key: value` chips of [finding]: its [METADATA_DISPLAY_KEYS] and the metadata keys no other part of the report
 * shows ([FindingField.RENDERED_METADATA_KEYS]), then its [facts] and error code.
 */
private fun chips(finding: Finding): List<Pair<String, String>> =
    finding.metadata
        .filter { it.key in METADATA_DISPLAY_KEYS || it.key !in FindingField.RENDERED_METADATA_KEYS }
        .toList() + finding.facts() + listOfNotNull(finding.errorCode?.let { "errorCode" to it })

// ---------------------------------------------------------------------------
// Inline comment formatting
// ---------------------------------------------------------------------------

/** Most lines a terse inline note carries under its header. */
internal const val INLINE_MAX_BODY_LINES = 3

/**
 * Formats one finding as a terse inline comment body: a header with the severity badge, the rule and
 * a one-line title, at most [INLINE_MAX_BODY_LINES] one-line body paragraphs (rest of the message, then the
 * labelled `Where` / `How to fix` / `Format` lines of the fix hint, explanation, [chips], in that order), the
 * suggestion block only for real code, attachments, then its [InlineFindingMarker] line. Over the cap, `Format` is
 * dropped first, then `Where`. Fenced blocks in any paragraph are dropped (see [oneLine]).
 * With [inlineWhy], a plain `Why: ...` footer from [WellKnownMetadataKeys.WHY] follows the body, outside the line cap;
 * no italics, which a `_` in a rule id or path would break; its label is in [locale]. A collapsed `Format` block from
 * [WellKnownMetadataKeys.FORMAT_EXPLAIN] / [WellKnownMetadataKeys.FORMAT_DIAGRAM] follows, also outside the cap.
 */
internal fun formatInlineCommentBody(
    finding: Finding,
    inlineWhy: Boolean = false,
    locale: SummaryLocale = SummaryLocale.EN,
): String {
    val message = inlineText(finding.message).trim()
    val title = message.substringBefore('\n').trim()
    val rest = message.substringAfter('\n', "").trim()
    val suggestedCode = finding.metadata[SUGGESTED_CODE_KEY]
    val codeBlock = suggestedCode?.takeIf { it.isNotBlank() && isRenderableCode(it) }?.let(::defaultSuggestionBlock)
    val chips = chips(finding).joinToString(" | ") { (k, v) -> "`$k: $v`" }
    val where = finding.mismatch()?.let { "**${locale.t(FixHintText.WHERE)}:** ${it.describe(locale.tag, ::inlineCode)}" }
    val format = finding.expectedFormat()?.let { "**${locale.t(FixHintText.FORMAT)}:** ${inlineCode(it)}" }
    val lines = listOfNotNull(
        oneLine(rest),
        where,
        finding.suggestion?.let {
            oneLine(inlineText(it))?.let { s -> "**${locale.t(FixHintText.HOW_TO_FIX)}:** $s" }
        },
        format,
        finding.explanation?.let { oneLine(inlineText(it))?.let { e -> "\u2139\uFE0F $e" } },
        chips.ifEmpty { null },
    )
    val body = listOfNotNull(format, where)
        .fold(lines) { acc, line -> if (acc.size > INLINE_MAX_BODY_LINES) acc - line else acc }
        .take(INLINE_MAX_BODY_LINES)

    val sb = StringBuilder("**${severityEmoji(finding.severity)} ${finding.severity}** \u2014 `${finding.rule}`")
    if (title.isNotEmpty()) sb.append(" \u00B7 ").append(title)
    body.forEach { sb.append("\n\n").append(it) }
    val why = finding.metadata[WellKnownMetadataKeys.WHY]?.takeIf { inlineWhy }?.let { oneLine(inlineText(it)) }
    why?.let { sb.append("\n\n${locale.t(SummaryText.WHY)}: $it") }
    formatDetails(finding, locale)?.let { sb.append("\n\n").append(it) }
    codeBlock?.let { sb.append("\n\n").append(it) }
    for (attachment in finding.attachments) {
        sb.append("\n\n<details><summary>${attachment.filename}</summary>\n\n${attachment.content}\n</details>")
    }
    sb.append("\n").append(InlineFindingMarker.of(finding))
    return sb.toString()
}

/**
 * The compact inline note of the standard and quiet styles: `❌ **`rule`** · title`, the rest of the message, the fix, the
 * suggestion block, then the `Why` (where, explanation or why) and `Format` blocks collapsed, attachments and the
 * [InlineFindingMarker] line. [finding] is already trimmed by the style; the marker is taken from [original], whose
 * fingerprint a style may hide.
 */
internal fun formatCompactInlineBody(
    finding: Finding,
    original: Finding,
    locale: SummaryLocale = SummaryLocale.EN,
): String {
    val message = inlineText(finding.message).trim()
    val title = message.substringBefore('\n').trim()
    val sb = StringBuilder("${severityEmoji(finding.severity)} **`${finding.rule}`**")
    if (title.isNotEmpty()) sb.append(" \u00B7 ").append(title)
    oneLine(message.substringAfter('\n', "").trim())?.let { sb.append("\n\n").append(it) }
    finding.suggestion?.let { oneLine(inlineText(it)) }?.let { sb.append("\n\n**${locale.t(FixHintText.HOW_TO_FIX)}:** $it") }
    finding.metadata[SUGGESTED_CODE_KEY]
        ?.takeIf { it.isNotBlank() && isRenderableCode(it) }
        ?.let { sb.append("\n\n").append(defaultSuggestionBlock(it)) }
    val why = listOfNotNull(
        finding.mismatch()?.let { "**${locale.t(FixHintText.WHERE)}:** ${it.describe(locale.tag, ::inlineCode)}" },
        (finding.explanation ?: finding.metadata[WellKnownMetadataKeys.WHY])?.let { oneLine(inlineText(it)) },
    )
    if (why.isNotEmpty()) {
        sb.append("\n\n<details><summary>${locale.t(SummaryText.WHY)}</summary>\n\n${why.joinToString("\n\n")}\n$DETAILS_CLOSE_TAG")
    }
    formatDetails(finding, locale)?.let { sb.append("\n\n").append(it) }
    for (attachment in finding.attachments) {
        sb.append("\n\n<details><summary>${attachment.filename}</summary>\n\n${attachment.content}\n</details>")
    }
    sb.append("\n").append(InlineFindingMarker.of(original))
    return sb.toString()
}

/**
 * The AI sections of the summary for the compact layout: the changes summary (and walkthrough) and the supplementary
 * sections, without the risk block (the compact layout lists the risk score in its details) and without findings.
 */
internal fun formatAiSections(
    report: ValidationReport,
    walkthrough: Boolean,
    locale: SummaryLocale,
): String =
    with(locale) {
        val partitioned = partitionFindings(report.findings)
        val sb = StringBuilder()
        appendExecutiveOverview(sb, partitioned, walkthrough, withRisk = false)
        appendSupplementarySections(sb, partitioned)
        sb.toString().trim()
    }

/** A code span of [text] on one line, for an inline comment. */
private fun inlineCode(text: String): String = codeSpan(text.lines().joinToString(" ") { it.trim() }.trim())

/** A code span of [text] on one line that cannot end a table cell. */
private fun tableCode(text: String): String = inlineCode(text).replace("|", "\\|")

private val CODE_SPAN = Regex("`` (.+?) ``|`([^`\n]+)`")

/** [text] for a table cell: its code spans stay code, the prose between them is escaped. */
private fun tableText(text: String): String {
    val codes = CODE_SPAN.findAll(text).map { tableCode(it.groupValues[1].ifEmpty { it.groupValues[2] }) }.toList()
    return text.split(CODE_SPAN).mapIndexed { i, prose -> sanitizeMarkdown(prose) + codes.getOrElse(i) { "" } }.joinToString("")
}

/**
 * The collapsed `Format` block of [finding]: [WellKnownMetadataKeys.FORMAT_EXPLAIN] (escaped, line by line) then
 * [WellKnownMetadataKeys.FORMAT_DIAGRAM] in a fence longer than any backtick run in it; null when it has neither.
 * [title] follows the label in the summary. [withExplain] / [withDiagram] `false` leave that part out.
 */
private fun formatDetails(
    finding: Finding,
    locale: SummaryLocale,
    title: String = "",
    withExplain: Boolean = true,
    withDiagram: Boolean = true,
): String? {
    val explain = finding.metadata[WellKnownMetadataKeys.FORMAT_EXPLAIN]?.trim()?.takeIf { withExplain && it.isNotEmpty() }
    val diagram = finding.metadata[WellKnownMetadataKeys.FORMAT_DIAGRAM]?.trimEnd()?.takeIf { withDiagram && it.isNotBlank() }
    if (explain == null && diagram == null) return null
    val parts = listOfNotNull(explain?.lines()?.joinToString("\n") { sanitizeMarkdown(it) }, diagram?.let(::fenced))
    return "<details><summary>${locale.t(FixHintText.FORMAT)}$title</summary>\n\n${parts.joinToString("\n\n")}\n$DETAILS_CLOSE_TAG"
}

/** [text] in a code fence longer than its longest backtick run (at least [FENCE]). */
private fun fenced(text: String): String {
    val longestRun = text
        .fold(0 to 0) { (run, longest), c -> if (c == '`') (run + 1) to maxOf(longest, run + 1) else 0 to longest }
        .second
    val fence = "`".repeat(maxOf(FENCE.length, longestRun + 1))
    return "$fence\n$text\n$fence"
}

/**
 * Collapses [text] to one line: fenced blocks are dropped, lines are joined with spaces. Null when nothing is
 * left or only a lead-in to the dropped block remains (`Suggested change (BUG):`).
 */
private fun oneLine(text: String): String? {
    val outsideFences = text.split(FENCE).filterIndexed { i, _ -> i % 2 == 0 }
    val kept = outsideFences.flatMap { it.lines() }.filter { it.isNotBlank() }
    return kept.joinToString(" ") { it.trim() }.takeIf { it.isNotEmpty() && !it.endsWith(':') }
}

private val COMMENT_PREFIXES = listOf("//", "/*", "*/", "* ", "# ", "-- ", "<!--")
private const val CODE_CHARS = "(){}[]=;<>\"'`:$/"

/**
 * False for a suggested-code block holding only comments or prose: every non-blank line is a
 * comment, or it has no code signal and reads as a sentence (capitalised first word, sentence punctuation last).
 */
internal fun isRenderableCode(code: String): Boolean {
    val lines = code.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.all { line -> line == "#" || line == "*" || COMMENT_PREFIXES.any { line.startsWith(it) } }) return false
    val indented = code.lines().any { it.isNotBlank() && it.first().isWhitespace() }
    if (indented || lines.any(::hasCodeSignal)) return true
    val text = lines.joinToString(" ")
    val sentenceShaped = text.first().isUpperCase() && text.last() in ".!?"
    return !sentenceShaped
}

// limit: "-" counts only as a leading flag (`rm -rf`), so hyphenated prose stays prose; a "." counts only
// inside a token (`a.b`), so a sentence-ending dot does not. Prose with a path or URL reads as code.
private fun hasCodeSignal(line: String): Boolean =
    line.any { it in CODE_CHARS } ||
        line.split(' ').any { it.length > 1 && it.startsWith('-') } ||
        line.indices.any { i ->
            line[i] == '.' && i > 0 && i < line.lastIndex && !line[i - 1].isWhitespace() && !line[i + 1].isWhitespace()
        }

/** Default diff block, wrapped in suggestion markers so the platform output can render it natively. */
private fun defaultSuggestionBlock(suggestedCode: String): String =
    buildString {
        appendLine(InlineCommentData.SUGGESTION_START)
        appendLine("```diff")
        suggestedCode.lines().forEach { appendLine("+ $it") }
        appendLine("```")
        append(InlineCommentData.SUGGESTION_END)
    }

// ---------------------------------------------------------------------------
// Summary markdown formatting
// ---------------------------------------------------------------------------

/**
 * Formats the full summary comment markdown from a [ValidationReport].
 *
 * A summary over [SUMMARY_MAX_BYTES] is re-rendered compact (findings table collapsed and cut to a byte
 * budget, long rule lists shortened) and, if still too large, truncated at a line boundary. The coverage line and
 * the [transparency] provenance block (bounded by [boundedProvenanceBlock]) are kept whole in every case.
 */
internal fun formatSummaryMarkdown(
    report: ValidationReport,
    summaryFindings: List<Finding>,
    inlineCount: Int,
    transparency: MarkdownTransparencyConfig = MarkdownTransparencyConfig(),
    walkthrough: Boolean = false,
    locale: SummaryLocale = SummaryLocale.EN,
    contributorLine: String? = null,
): String =
    with(locale) {
        val coverageLine = report.provenance
            ?.coverage
            ?.let { "\n_${t(SummaryText.AI_COVERAGE)}: ${it.describe(tag)}_\n" }
            .orEmpty() + boundedProvenanceBlock(report.provenance, transparency, locale) +
            contributorLine?.let { "\n_${it}_\n" }.orEmpty()
        val full = renderSummary(report, summaryFindings, inlineCount, compact = false, walkthrough) + coverageLine + footer
        if (full.utf8Size() <= SUMMARY_MAX_BYTES) return full
        val compact = renderSummary(report, summaryFindings, inlineCount, compact = true, walkthrough)
        if ((compact + coverageLine + footer).utf8Size() <= SUMMARY_MAX_BYTES) return compact + coverageLine + footer
        val skippedLine = if (report.skippedRules.isEmpty()) "" else "_${t(SummaryText.RULES_SKIPPED, report.skippedRules.size)}_\n"
        fitToLimit(compact, coverageLine + skippedLine)
    }

private fun SummaryLocale.renderSummary(
    report: ValidationReport,
    summaryFindings: List<Finding>,
    inlineCount: Int,
    compact: Boolean,
    walkthrough: Boolean,
): String {
    val sb = StringBuilder()
    sb.appendLine(t(if (report.passed) SummaryText.HEADING_PASSED else SummaryText.HEADING_FAILED))
    sb.appendLine()
    // What the AI review did, so "no AI findings" never hides an outage, a policy or a cache-only run.
    report.provenance?.aiStatusIn(tag)?.let {
        sb.appendLine("_${sanitizeMarkdown(it)}_")
        sb.appendLine()
    }

    val listLimit = if (compact) COMPACT_LIST_ENTRIES else Int.MAX_VALUE
    val (policySkips, ruleSkips) = report.skippedRules.partition { it.category == SkipCategory.POLICY_OVERRIDE_NOT_APPLIED }
    if (report.findings.isEmpty()) {
        sb.appendLine(t(SummaryText.ALL_PASSED))
        // A pass with skipped rules is not a full review; say what was not run.
        appendSkippedRules(sb, ruleSkips, listLimit)
    } else {
        val partitioned = partitionFindings(report.findings)
        val summaryPartitioned = partitionFindings(summaryFindings)

        appendExecutiveOverview(sb, partitioned, walkthrough)
        appendSeverityTable(sb, partitioned.regular, inlineCount)
        appendFindingsTable(sb, summaryPartitioned.regular, inlineCount, rowBudgetBytes = if (compact) COMPACT_ROW_BUDGET_BYTES else null)
        appendSupplementarySections(sb, partitioned)
        appendComplianceChecklist(sb, report.complianceChecklist, ruleSkips)
        appendSkippedRules(sb, ruleSkips, listLimit)
        appendPoliciesNotApplied(sb, policySkips, listLimit)
    }
    return sb.toString()
}

/**
 * Last resort: keeps whole lines up to the limit, closes any open `<details>` or code fence, then always
 * appends the reserved [tail] (AI coverage, skipped-rule count), the truncation note and the footer.
 */
private fun SummaryLocale.fitToLimit(
    body: String,
    tail: String,
): String {
    val lines = body.lines()
    val openTags = lines.sumOf { it.split("<details>").size - 1 }
    val reserve = tail.utf8Size() + truncatedNote.utf8Size() + footer.utf8Size() +
        openTags * (DETAILS_CLOSE_TAG.length + 2) + FENCE.length + 1
    val keptCount = lines
        .runningFold(0) { acc, line -> acc + line.utf8Size() + 1 }
        .drop(1)
        .takeWhile { it <= SUMMARY_MAX_BYTES - reserve }
        .size
    val kept = lines.take(keptCount)
    val stillOpen = kept.sumOf { (it.split("<details>").size - 1) - (it.split(DETAILS_CLOSE_TAG).size - 1) }
    val fenceOpen = kept.count { it.trimStart().startsWith(FENCE) } % 2 == 1
    return buildString {
        kept.forEach { appendLine(it) }
        if (fenceOpen) appendLine(FENCE)
        repeat(stillOpen) { appendLine().appendLine(DETAILS_CLOSE_TAG) }
        append(tail)
        append(truncatedNote)
        append(footer)
    }
}

private fun String.utf8Size(): Int = encodeToByteArray().size

// ---------------------------------------------------------------------------
// Partitioning
// ---------------------------------------------------------------------------

@Serializable
internal data class PartitionedFindings(
    val riskAdvisory: List<Finding>,
    val mrSummary: List<Finding>,
    val fixSuggestions: List<Finding>,
    val categorization: List<Finding>,
    val descriptionQuality: List<Finding>,
    val prSplit: List<Finding>,
    val ticketCompliance: List<Finding>,
    val generatedDescription: List<Finding>,
    val regular: List<Finding>,
    /** Findings routed by [PresentationKeys.SECTION] (and no legacy `is*` flag), keyed by section name. */
    val sections: Map<String, List<Finding>> = emptyMap(),
)

internal fun partitionFindings(findings: List<Finding>): PartitionedFindings {
    val risk = mutableListOf<Finding>()
    val summary = mutableListOf<Finding>()
    val fix = mutableListOf<Finding>()
    val cat = mutableListOf<Finding>()
    val descQuality = mutableListOf<Finding>()
    val split = mutableListOf<Finding>()
    val ticket = mutableListOf<Finding>()
    val genDesc = mutableListOf<Finding>()
    val regular = mutableListOf<Finding>()
    val sections = linkedMapOf<String, MutableList<Finding>>()

    for (f in findings) {
        when {
            f.metadata[WellKnownMetadataKeys.IS_RISK_ADVISORY] == "true" -> {
                risk.add(f)
            }

            f.metadata[WellKnownMetadataKeys.IS_MR_SUMMARY] == "true" -> {
                summary.add(f)
            }

            f.metadata[WellKnownMetadataKeys.IS_FIX_SUGGESTION] == "true" -> {
                fix.add(f)
            }

            f.metadata[WellKnownMetadataKeys.IS_CATEGORIZATION] == "true" -> {
                cat.add(f)
            }

            f.metadata[WellKnownMetadataKeys.IS_DESCRIPTION_QUALITY] == "true" -> {
                descQuality.add(f)
            }

            f.metadata[WellKnownMetadataKeys.IS_PR_SPLIT] == "true" -> {
                split.add(f)
            }

            f.metadata[WellKnownMetadataKeys.IS_TICKET_COMPLIANCE] == "true" -> {
                ticket.add(f)
            }

            f.metadata[WellKnownMetadataKeys.IS_GENERATED_DESCRIPTION] == "true" -> {
                genDesc.add(f)
            }

            f.metadata[PresentationKeys.SECTION] != null -> {
                sections.getOrPut(checkNotNull(f.metadata[PresentationKeys.SECTION])) { mutableListOf() }.add(f)
            }

            else -> {
                regular.add(f)
            }
        }
    }

    return PartitionedFindings(risk, summary, fix, cat, descQuality, split, ticket, genDesc, regular, sections)
}

// ---------------------------------------------------------------------------
// Section renderers
// ---------------------------------------------------------------------------

private fun SummaryLocale.appendExecutiveOverview(
    sb: StringBuilder,
    partitioned: PartitionedFindings,
    walkthrough: Boolean,
    withRisk: Boolean = true,
) {
    // Chunked AI runs can emit one risk finding per chunk; the summary shows only the riskiest.
    partitioned.riskAdvisory
        .takeIf { withRisk }
        ?.maxByOrNull { it.metadata[WellKnownMetadataKeys.RISK_SCORE]?.toDoubleOrNull() ?: -1.0 }
        ?.let { appendRiskAdvisoryBlockquote(sb, listOf(it)) }
    if (partitioned.mrSummary.isNotEmpty()) {
        sb.appendLine(t(SummaryText.CHANGES_SUMMARY))
        sb.appendLine()
        partitioned.mrSummary.forEach { sb.appendLine(it.message) }
        sb.appendLine()
        partitioned.mrSummary
            .firstNotNullOfOrNull { it.metadata[WellKnownMetadataKeys.WALKTHROUGH] }
            ?.takeIf { walkthrough }
            ?.let {
                sb
                    .appendLine(
                        "<details><summary>${t(SummaryText.WALKTHROUGH)}</summary>"
                    ).appendLine()
                    .append(it)
                    .appendLine()
                    .appendLine(DETAILS_CLOSE_TAG)
                    .appendLine()
            }
    }
    if (partitioned.descriptionQuality.isNotEmpty()) {
        appendDescriptionQualitySection(sb, partitioned.descriptionQuality)
    }
    if (partitioned.categorization.isNotEmpty()) {
        appendSuggestedLabelsSection(sb, partitioned.categorization)
    }
    if (partitioned.ticketCompliance.isNotEmpty()) {
        appendTicketComplianceSection(sb, partitioned.ticketCompliance)
    }
    if (partitioned.fixSuggestions.isNotEmpty()) {
        appendFixSuggestionsSection(sb, partitioned.fixSuggestions)
    }
}

private fun SummaryLocale.appendRiskAdvisoryBlockquote(
    sb: StringBuilder,
    riskFindings: List<Finding>,
) {
    for (finding in riskFindings) {
        val emoji = severityEmoji(finding.severity)
        val score = finding.metadata[WellKnownMetadataKeys.RISK_SCORE]
        val scoreLabel = if (score != null) " (${t(SummaryText.SCORE)}: $score)" else ""
        sb.appendLine("> **$emoji ${t(SummaryText.RISK_ASSESSMENT)}**$scoreLabel")
        sb.appendLine(">")
        sb.appendQuoted(finding.message)
        val factors = finding.metadata[WellKnownMetadataKeys.RISK_FACTORS]
        if (!factors.isNullOrBlank()) {
            sb.appendLine(">")
            sb.appendLine("> **${t(SummaryText.RISK_FACTORS)}:** ${sanitizeBlockquote(factors)}")
        }
        sb.appendLine()
    }
}

private fun SummaryLocale.appendDescriptionQualitySection(
    sb: StringBuilder,
    findings: List<Finding>,
) {
    for (finding in findings) {
        val emoji = severityEmoji(finding.severity)
        val score = finding.metadata[WellKnownMetadataKeys.QUALITY_SCORE]
        val scoreLabel = if (score != null) " (${t(SummaryText.SCORE)}: $score)" else ""
        sb.appendLine("> **$emoji ${t(SummaryText.DESCRIPTION_QUALITY)}**$scoreLabel")
        sb.appendLine(">")
        sb.appendQuoted(finding.message)
        finding.suggestion?.let { suggestion ->
            sb.appendLine(">")
            suggestion.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach { issue ->
                sb.appendLine("> - $issue")
            }
        }
        sb.appendLine()
    }
}

private fun SummaryLocale.appendSuggestedLabelsSection(
    sb: StringBuilder,
    findings: List<Finding>,
) {
    for (finding in findings) {
        val labels = finding.metadata[WellKnownMetadataKeys.SUGGESTED_LABELS]
        val labelDisplay = labels?.split(",")?.joinToString(", ") { "`${it.trim()}`" } ?: finding.message
        sb.appendLine("> \uD83C\uDFF7\uFE0F **${t(SummaryText.SUGGESTED_LABELS)}:** $labelDisplay")
        finding.explanation?.let { reasoning ->
            sb.appendLine(">")
            sb.appendLine("> *${sanitizeBlockquote(reasoning)}*")
        }
        sb.appendLine()
    }
}

private fun SummaryLocale.appendTicketComplianceSection(
    sb: StringBuilder,
    findings: List<Finding>,
) {
    for (finding in findings) {
        val emoji = severityEmoji(finding.severity)
        val score = finding.metadata[WellKnownMetadataKeys.COMPLIANCE_SCORE]
        val scoreLabel = when {
            score == null || score == "null" -> {
                ""
            }

            else -> {
                val pct = score.toDoubleOrNull()?.let { (it * 100).toInt() }
                if (pct != null) " ($pct%)" else ""
            }
        }
        sb.appendLine("> **$emoji ${t(SummaryText.TICKET_COMPLIANCE)}**$scoreLabel")
        sb.appendLine(">")
        sb.appendQuoted(finding.message)
        appendRequirementsList(sb, finding)
        sb.appendLine()
    }
}

private fun appendRequirementsList(
    sb: StringBuilder,
    finding: Finding,
) {
    val fulfilled = finding.metadata[WellKnownMetadataKeys.FULFILLED_REQUIREMENTS]
    val missing = finding.metadata[WellKnownMetadataKeys.MISSING_REQUIREMENTS]
    val partial = finding.metadata[WellKnownMetadataKeys.PARTIAL_REQUIREMENTS]
    if (fulfilled.isNullOrBlank() && missing.isNullOrBlank() && partial.isNullOrBlank()) return

    sb.appendLine(">")
    fulfilled?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() }?.forEach {
        sb.appendLine("> - \u2705 $it")
    }
    partial?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() }?.forEach {
        sb.appendLine("> - \u25D0 $it")
    }
    missing?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() }?.forEach {
        sb.appendLine("> - \u274C $it")
    }
}

private fun SummaryLocale.appendFixSuggestionsSection(
    sb: StringBuilder,
    findings: List<Finding>,
) {
    sb.appendLine(t(SummaryText.AI_FIX_SUGGESTIONS))
    sb.appendLine()
    for (finding in findings) {
        val originalRule = finding.metadata[WellKnownMetadataKeys.ORIGINAL_RULE] ?: finding.rule
        val suggestedFix = finding.metadata[WellKnownMetadataKeys.SUGGESTED_FIX] ?: finding.message
        sb.appendLine("> \uD83D\uDCA1 **${t(SummaryText.FIX_FOR, originalRule)}** $suggestedFix")
        sb.appendLine()
    }
}

private fun SummaryLocale.appendSeverityTable(
    sb: StringBuilder,
    countable: List<Finding>,
    inlineCount: Int,
) {
    val counts = countable.summarize()

    sb.appendLine(t(SummaryText.SEVERITY_TABLE_HEADER))
    sb.appendLine("|----------|-------|")
    sb.appendLine("| ${t(SummaryText.ERRORS)} | ${counts.errors} |")
    sb.appendLine("| ${t(SummaryText.WARNINGS)} | ${counts.warnings} |")
    sb.appendLine("| ${t(SummaryText.INFOS)} | ${counts.info} |")
    if (inlineCount > 0) {
        sb.appendLine("| ${t(SummaryText.INLINE_COMMENTS)} | $inlineCount |")
    }
    sb.appendLine()
}

private fun SummaryLocale.appendFindingsTable(
    sb: StringBuilder,
    regularFindings: List<Finding>,
    inlineCount: Int,
    rowBudgetBytes: Int? = null,
) {
    if (regularFindings.isNotEmpty()) {
        sb.appendLine("### ${t(SummaryText.FINDINGS)}")
        sb.appendLine()
        if (rowBudgetBytes == null) {
            appendFindingsTableBody(sb, regularFindings)
            return
        }
        val shownHints = mutableSetOf<String>()
        val rows = regularFindings.map { StringBuilder().also { row -> appendFindingRow(row, it, shownHints) }.toString() }
        val shown = rows
            .runningFold(0) { acc, row -> acc + row.utf8Size() }
            .drop(1)
            .takeWhile { it <= rowBudgetBytes }
            .size
        sb.appendLine("<details><summary>${t(SummaryText.FINDINGS)} (${regularFindings.size})</summary>")
        sb.appendLine()
        sb.appendLine(t(SummaryText.FINDINGS_TABLE_HEADER))
        sb.appendLine(FINDINGS_TABLE_SEPARATOR)
        rows.take(shown).forEach { sb.append(it) }
        appendFormatDetails(sb, regularFindings.take(shown), budgetBytes = rowBudgetBytes - rows.take(shown).sumOf { it.utf8Size() })
        if (shown < rows.size) {
            sb.appendLine()
            sb.appendLine(t(SummaryText.FINDINGS_NOT_SHOWN, rows.size - shown))
        }
        sb.appendLine()
        sb.appendLine(DETAILS_CLOSE_TAG)
    } else if (inlineCount > 0) {
        sb.appendLine(t(SummaryText.ALL_INLINE))
    }
}

private fun SummaryLocale.appendFindingsTableBody(
    sb: StringBuilder,
    findings: List<Finding>,
) {
    sb.appendLine(t(SummaryText.FINDINGS_TABLE_HEADER))
    sb.appendLine(FINDINGS_TABLE_SEPARATOR)
    val shownHints = mutableSetOf<String>()
    findings.forEach { finding -> appendFindingRow(sb, finding, shownHints) }
    appendFormatDetails(sb, findings)
}

/**
 * The collapsed `Format` blocks of [findings], after the table so they do not split it. Over [budgetBytes] the
 * diagrams are left out, then the explanations too.
 */
private fun SummaryLocale.appendFormatDetails(
    sb: StringBuilder,
    findings: List<Finding>,
    budgetBytes: Int? = null,
) {
    val blocks = listOf(true to true, true to false, false to false)
        .map { (withExplain, withDiagram) ->
            findings
                .mapNotNull {
                    formatDetails(it, this, " \u2014 <code>${sanitizeMarkdown(it.rule)}</code>", withExplain, withDiagram)
                }.distinct()
        }.first { candidate -> budgetBytes == null || candidate.sumOf { it.utf8Size() + 2 } <= budgetBytes }
    for (details in blocks) {
        sb.appendLine()
        sb.appendLine(details)
    }
}

/**
 * One table row. A hint (explanation, suggestion, expected format) already in [shownHints] for the same rule is left
 * out, so N findings of one rule do not repeat the same text N times.
 */
private fun SummaryLocale.appendFindingRow(
    sb: StringBuilder,
    finding: Finding,
    shownHints: MutableSet<String>,
) {
    fun String.once(kind: String) = takeIf { shownHints.add("$kind\u0000${finding.rule}\u0000$it") }
    val emoji = severityEmoji(finding.severity)
    val sanitizedRule = sanitizeMarkdown(finding.rule)
    // In a table cell: a literal backslash-n becomes a newline, which sanitizeMarkdown folds into a space.
    var cell = sanitizeMarkdown(unescapeNewlines(finding.message))
    if (finding.metadata[WellKnownMetadataKeys.CACHED] == "true") cell += " ${t(SummaryText.CACHED)}"
    finding.mismatch()?.let { cell += " <br>**${t(FixHintText.WHERE)}:** ${it.describe(tag, ::tableCode)}" }
    finding.explanation?.once("e")?.let { cell += " <br>\u2139\uFE0F ${sanitizeMarkdown(unescapeNewlines(it))}" }
    val demotedPath = (finding.location as? Location.FileRef)?.path?.takeIf {
        finding.metadata[WellKnownMetadataKeys.LINE_DEMOTED] == "true"
    }
    demotedPath?.let { cell += " <br><small>${t(SummaryText.UNVERIFIED_ANCHOR)} `${sanitizeMarkdown(it)}`</small>" }
    val filteredMeta = chips(finding)
    if (filteredMeta.isNotEmpty()) {
        val meta = filteredMeta.joinToString(", ") { (k, v) ->
            "`${sanitizeMarkdown(k)}: ${sanitizeMarkdown(v)}`"
        }
        cell += " <br><small>$meta</small>"
    }
    val fix = listOfNotNull(
        finding.suggestion?.once("s")?.let { tableText(unescapeNewlines(it)) },
        finding.expectedFormat()?.once("f")?.let { "**${t(FixHintText.FORMAT)}:** ${tableCode(it)}" },
    ).joinToString(" <br>")
    sb.appendLine("| $emoji ${finding.severity} | `$sanitizedRule` | $cell | $fix |")
    for (attachment in finding.attachments) {
        sb.appendLine()
        sb.appendLine("<details><summary>${sanitizeMarkdown(attachment.filename)}</summary>")
        sb.appendLine()
        sb.appendLine(attachment.content)
        sb.appendLine(DETAILS_CLOSE_TAG)
    }
}

private fun SummaryLocale.appendSupplementarySections(
    sb: StringBuilder,
    partitioned: PartitionedFindings,
) {
    for ((section, sectionFindings) in PresentationKeys.sections(partitioned.sections.values.flatten())) {
        sb.appendLine()
        sb.appendLine("### $section")
        sb.appendLine()
        sectionFindings.forEach { sb.appendLine("- ${it.message}") }
    }
    if (partitioned.prSplit.isNotEmpty()) {
        for (finding in partitioned.prSplit) {
            sb.appendLine()
            sb.appendLine("<details><summary>\uD83D\uDD00 ${t(SummaryText.PR_SPLIT)}</summary>")
            sb.appendLine()
            finding.explanation?.let { sb.appendLine(it) }
                ?: sb.appendLine(finding.message)
            sb.appendLine()
            sb.appendLine(DETAILS_CLOSE_TAG)
        }
    }
    if (partitioned.generatedDescription.isNotEmpty()) {
        // mr-summary and generate-description can both suggest one; show the first only.
        for (finding in partitioned.generatedDescription.take(1)) {
            val title = finding.metadata[WellKnownMetadataKeys.SUGGESTED_TITLE]
            val desc = finding.metadata[WellKnownMetadataKeys.SUGGESTED_DESCRIPTION]
            sb.appendLine()
            sb.appendLine("<details><summary>\uD83D\uDCDD ${t(SummaryText.SUGGESTED_DESCRIPTION)}</summary>")
            sb.appendLine()
            if (title != null) {
                sb.appendLine("**${t(SummaryText.SUGGESTED_TITLE)}:** $title")
                sb.appendLine()
            }
            if (desc != null) {
                sb.appendLine(desc)
            } else {
                finding.explanation?.let { sb.appendLine(it) }
                    ?: sb.appendLine(finding.message)
            }
            sb.appendLine()
            sb.appendLine(DETAILS_CLOSE_TAG)
        }
    }
}

private fun SummaryLocale.appendComplianceChecklist(
    sb: StringBuilder,
    checklist: List<ComplianceItem>,
    skippedRules: List<SkippedRuleInfo>,
) {
    sb.appendLine()
    sb.appendLine(t(SummaryText.CHECKLIST))
    sb.appendLine()
    val skippedIds = skippedRules.map { it.ruleId }.toSet()
    for (item in checklist) {
        val line = when {
            item.status == ComplianceStatus.FAILED -> "- ❌ ${item.ruleId}"
            item.status == ComplianceStatus.WARNING -> "- ⚠️ ${item.ruleId} ${t(SummaryText.WARNINGS_MARK)}"
            item.ruleId in skippedIds -> "- ⏭️ ${item.ruleId} ${t(SummaryText.SKIPPED_MARK)}"
            else -> "- ✅ ${item.ruleId}"
        }
        sb.appendLine(line)
    }
    val listed = checklist.map { it.ruleId }.toSet()
    skippedIds.filterNot { it in listed }.forEach { sb.appendLine("- ⏭️ $it ${t(SummaryText.SKIPPED_MARK)}") }
}

private fun SummaryLocale.appendSkippedRules(
    sb: StringBuilder,
    skippedRules: List<SkippedRuleInfo>,
    maxEntries: Int,
) {
    if (skippedRules.isEmpty()) return
    sb.appendLine()
    sb.appendLine("<details><summary>${t(SummaryText.SKIPPED_RULES, skippedRules.size)}</summary>")
    sb.appendLine()
    for (info in skippedRules.take(maxEntries)) {
        val reason = if (info.reason.isNotEmpty()) ": ${skipReasonIn(info.reason, tag)}" else ""
        sb.appendLine("- `${info.ruleId}`$reason")
    }
    appendMoreLine(sb, skippedRules.size - maxEntries)
    sb.appendLine()
    sb.appendLine(DETAILS_CLOSE_TAG)
}

/** Policy overrides whose `when:` did not match, grouped per policy; the raw condition is not shown. */
private fun SummaryLocale.appendPoliciesNotApplied(
    sb: StringBuilder,
    policySkips: List<SkippedRuleInfo>,
    maxEntries: Int,
) {
    if (policySkips.isEmpty()) return
    val byPolicy = policySkips.groupBy({ it.policy ?: t(SummaryText.UNNAMED_POLICY) }, { it.ruleId })
    sb.appendLine()
    sb.appendLine("<details><summary>${t(SummaryText.POLICIES_NOT_APPLIED, byPolicy.size)}</summary>")
    sb.appendLine()
    byPolicy.entries.take(maxEntries).forEach { (policy, rules) ->
        sb.appendLine("- $policy: ${rules.distinct().joinToString(", ") { "`$it`" }}")
    }
    appendMoreLine(sb, byPolicy.size - maxEntries)
    sb.appendLine()
    sb.appendLine(DETAILS_CLOSE_TAG)
}

private fun SummaryLocale.appendMoreLine(
    sb: StringBuilder,
    hidden: Int,
) {
    if (hidden > 0) sb.appendLine("- ${t(SummaryText.MORE, hidden)}")
}

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------

private fun inlineText(text: String): String = escapeKaTeX(unescapeNewlines(text))

/**
 * Turns a literal backslash-n (as LLMs often emit) into a newline, leaving code spans and fenced
 * blocks untouched; an unclosed span or fence keeps the rest of the text as is. A backtick run closes
 * on a run of the same length, so a double-backtick span may hold a single backtick.
 *
 * limit: a `\n` is also kept when preceded by a backslash or inside a token that looks like a Windows path
 * or regex (`C:\new`, `\d+\n`). A relative path such as `src\new\notes` is not recognised and is rewritten.
 */
internal fun unescapeNewlines(text: String): String {
    if ("\\n" !in text) return text
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val codeEnd = if (text[i] == '`') backtickSpanEnd(text, i) else -1
        when {
            codeEnd >= 0 -> {
                sb.append(text, i, codeEnd)
                i = codeEnd
            }

            text.startsWith("\\n", i) && (i == 0 || text[i - 1] != '\\') && !inPathLikeToken(text, i) -> {
                sb.append('\n')
                i += 2
            }

            else -> {
                sb.append(text[i])
                i++
            }
        }
    }
    return sb.toString()
}

/** End (exclusive) of the code span opened by the backtick run at [start]; the text length when unclosed. */
private fun backtickSpanEnd(
    text: String,
    start: Int,
): Int {
    val runLength = backtickRun(text, start)
    var j = start + runLength
    while (j < text.length) {
        if (text[j] != '`') {
            j++
            continue
        }
        val closeLength = backtickRun(text, j)
        if (closeLength == runLength) return j + closeLength
        j += closeLength
    }
    return text.length
}

private fun backtickRun(
    text: String,
    from: Int,
): Int {
    var end = from
    while (end < text.length && text[end] == '`') end++
    return end - from
}

/** True when the whitespace-delimited token around [at] holds a drive letter, a leading backslash or a backslash escape other than `\n`. */
private fun inPathLikeToken(
    text: String,
    at: Int,
): Boolean {
    var from = at
    while (from > 0 && !text[from - 1].isWhitespace()) from--
    var to = at
    while (to < text.length && !text[to].isWhitespace()) to++
    val token = text.substring(from, to)
    return ":\\" in token || token.startsWith("\\") ||
        token.indices.any { i -> token[i] == '\\' && token.getOrNull(i + 1).let { it != null && it != 'n' && it != '\\' } }
}

/**
 * Escapes `$` signs in text to prevent GitLab KaTeX math rendering.
 * GitLab treats `$...$` as inline math blocks which garbles finding text
 * containing env var references or regex anchors.
 */
internal fun escapeKaTeX(text: String): String = text.replace("$", "\\$")

internal fun severityEmoji(severity: Severity): String =
    when (severity) {
        Severity.ERROR -> "\u274C"
        Severity.WARNING -> "\u26A0\uFE0F"
        Severity.INFO -> "\u2139\uFE0F"
    }

internal fun sanitizeMarkdown(text: String): String =
    text
        .replace("\r\n", " ")
        .replace("\n", " ")
        .replace("\r", " ")
        .replace("\\", "\\\\")
        .replace("@", "@\u200b")
        .replace("#", "\\#")
        .replace("`", "\\`")
        .replace("|", "\\|")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("$", "\\$")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

private fun StringBuilder.appendQuoted(text: String) {
    appendLine("> ${sanitizeBlockquote(text)}")
}

internal fun sanitizeBlockquote(text: String): String = text.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
