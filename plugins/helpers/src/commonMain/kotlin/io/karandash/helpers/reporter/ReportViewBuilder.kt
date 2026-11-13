package io.karandash.helpers.reporter

import io.karandash.api.finding.ComplianceStatus
import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.skipReasonIn
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FindingField
import io.karandash.models.finding.Location
import io.karandash.models.finding.PresentationKeys
import io.karandash.models.finding.Severity
import io.karandash.models.finding.SeverityFormat
import io.karandash.models.report.CountsView
import io.karandash.models.report.DeltaView
import io.karandash.models.report.DetailsView
import io.karandash.models.report.FileGroupView
import io.karandash.models.report.FindingView
import io.karandash.models.report.LabelsView
import io.karandash.models.report.MrView
import io.karandash.models.report.ReportStyle
import io.karandash.models.report.ReportView
import io.karandash.models.report.SkippedView
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.utils.finding.FindingFingerprint
import kotlinx.serialization.Serializable

/**
 * How to build a [ReportView]: the report [language], the merge request it is about, how many findings the medium
 * holds ([maxFindings]), the [style] whose hidden fields apply, and the run number when known.
 */
@Serializable
data class ReportViewOptions(
    val language: String? = null,
    val mr: MrView? = null,
    val maxFindings: Int = Int.MAX_VALUE,
    val style: ReportStyle = ReportStyle.DEFAULT,
    val runNumber: Int? = null,
)

/** Metadata flags of the AI summary findings a report shows in sections of their own, never as finding rows. */
private val ADVISORY_KEYS =
    listOf(
        WellKnownMetadataKeys.IS_MR_SUMMARY,
        WellKnownMetadataKeys.IS_RISK_ADVISORY,
        WellKnownMetadataKeys.IS_FIX_SUGGESTION,
        WellKnownMetadataKeys.IS_CATEGORIZATION,
        WellKnownMetadataKeys.IS_DESCRIPTION_QUALITY,
        WellKnownMetadataKeys.IS_PR_SPLIT,
        WellKnownMetadataKeys.IS_TICKET_COMPLIANCE,
        WellKnownMetadataKeys.IS_GENERATED_DESCRIPTION,
    )

/** True for a finding a report lists as a row: not an AI summary item and not placed in a section of its own. */
fun Finding.isFindingRow(): Boolean = ADVISORY_KEYS.none { metadata[it] == "true" } && PresentationKeys.SECTION !in metadata

/** Most severe first; within a severity, merge-request-level findings first, then by file and line. */
val FINDING_ROW_ORDER: Comparator<Finding> =
    compareBy<Finding> { it.severity.ordinal }
        .thenBy { if (it.location is Location.FileRef) 1 else 0 }
        .thenBy { (it.location as? Location.FileRef)?.path.orEmpty() }
        .thenBy { (it.location as? Location.FileRef)?.line ?: 0 }

/** True when [where] is the `MR` a finding with no file or commit shows in the `Where` column, in any language. */
fun isMrWhere(where: String): Boolean = ViewLanguage.all().any { ViewText.MR.text(it) == where.trim() }

/** True when a row of this view names a file or a commit: with none, the `Where` column says only `MR` and is left out. */
fun ReportView.hasWhere(): Boolean = findings.any { !isMrWhere(it.where) }

/** This report as a [ReportView]; see [ReportViewOptions]. */
fun ValidationReport.toView(options: ReportViewOptions = ReportViewOptions()): ReportView {
    val lang = ViewLanguage.of(options.language)
    val rows = findings.filter { it.isFindingRow() }.map(options.style::apply).sortedWith(FINDING_ROW_ORDER)
    val taken = rows.take(options.maxFindings.coerceAtLeast(0))
    val names = shortNames(taken.mapNotNull { (it.location as? Location.FileRef)?.path })
    val hideWhy = FindingField.EXPLANATION in options.style.hiddenFields
    val shown = taken.map { it.toFindingView(lang, options.mr, names, hideWhy) }
    val counts = countsView(rows, lang)
    val delta = deltaView(lang)
    val verdict = (if (passed) ViewText.PASSED else ViewText.FAILED).text(lang)
    return ReportView(
        passed = passed,
        verdict = verdict,
        headline = listOfNotNull(verdict, counts.text.ifEmpty { null }).joinToString(" · ") +
            (delta?.text?.let { " — $it" } ?: ""),
        counts = counts,
        delta = delta,
        findings = shown,
        more = rows.size - shown.size,
        files = shown.groupBy { it.path ?: ViewText.MR.text(lang) }.map { (name, items) -> FileGroupView(name, items) },
        details = detailsView(delta, lang),
        mr = options.mr,
        version = metadata.version,
        runNumber = options.runNumber,
        footer = listOfNotNull(
            "karandash ${metadata.version}".trim(),
            options.runNumber?.let { ViewText.RUN_OF_MR.format(lang, it.toString()) },
        ).joinToString(" · "),
        labels = LabelsView(
            where = ViewText.WHERE.text(lang),
            finding = ViewText.FINDING.text(lang),
            fix = ViewText.FIX.text(lang),
            why = ViewText.WHY.text(lang),
            details = ViewText.DETAILS.text(lang),
            more = ViewText.MORE.text(lang),
            rule = ViewText.RULE.text(lang),
        ),
    )
}

private fun countsView(
    rows: List<Finding>,
    lang: ViewLanguage,
): CountsView {
    val counts = rows.summarize()
    val text = listOf(ViewText.ERRORS to counts.errors, ViewText.WARNINGS to counts.warnings, ViewText.INFOS to counts.info)
        .filter { it.second > 0 }
        .joinToString(" · ") { (label, n) -> label.count(lang, n) }
    return CountsView(counts.errors, counts.warnings, counts.info, rows.size, text)
}

private fun ValidationReport.deltaView(lang: ViewLanguage): DeltaView? =
    baselineComparison?.let { c ->
        val parts = listOfNotNull(
            c.newFindings.takeIf { it > 0 }?.let { ViewText.NEW.count(lang, it) },
            c.resolvedFindings.takeIf { it > 0 }?.let { ViewText.FIXED.count(lang, it) },
        )
        val text = if (parts.isEmpty()) "" else parts.joinToString(", ") + " " + ViewText.SINCE_LAST_RUN.text(lang)
        DeltaView(c.newFindings, c.resolvedFindings, c.unchangedFindings, text)
    }

private fun ValidationReport.detailsView(
    delta: DeltaView?,
    lang: ViewLanguage,
): DetailsView {
    val risk = findings
        .filter { it.metadata[WellKnownMetadataKeys.IS_RISK_ADVISORY] == "true" }
        .mapNotNull { it.metadata[WellKnownMetadataKeys.RISK_SCORE]?.toDoubleOrNull()?.toInt() }
        .maxOrNull()
    // An AI provider failure is an outage to retry, not a rule the engine chose to skip: it gets its own line.
    val (aiFailures, realSkips) = skippedRules.partition { it.category == SkipCategory.AI_PROVIDER_ERROR }
    val skipped = realSkips.map { SkippedView(it.ruleId, skipReasonIn(it.reason, lang.tag)) }
    val passedRules = complianceChecklist.filter { it.status == ComplianceStatus.PASSED }.map { it.ruleId }
    val coverage = provenance?.coverage?.let { "${it.reviewedFiles}/${it.totalFiles}" }
    val fixed = delta?.fixed?.takeIf { it > 0 }?.let { ViewText.FIXED.count(lang, it) + " " + ViewText.SINCE_LAST_RUN.text(lang) }
    val lines = listOfNotNull(
        risk?.let { ViewText.RISK.format(lang, it.toString()) },
        aiFailures.takeIf { it.isNotEmpty() }?.let { list ->
            ViewText.AI_FAILED.count(lang, list.size) + ": " + list
                .groupBy { skipReasonIn(it.reason, lang.tag) }
                .entries
                .joinToString("; ") { (reason, rules) ->
                    rules.joinToString(", ") { it.ruleId } + if (reason.isBlank()) "" else " ($reason)"
                }
        },
        skipped.takeIf { it.isNotEmpty() }?.let { list ->
            ViewText.SKIPPED.count(lang, list.size) + ": " +
                list.joinToString(", ") { s -> s.rule + s.reason.let { r -> if (r.isBlank()) "" else " ($r)" } }
        },
        coverage?.let { ViewText.AI_COVERAGE.format(lang, it) },
        passedRules.takeIf { it.isNotEmpty() }?.let { ViewText.PASSED_RULES.format(lang, it.joinToString(", ")) },
        fixed,
    )
    return DetailsView(
        risk,
        skipped,
        passedRules,
        coverage?.let { ViewText.AI_COVERAGE.format(lang, it) },
        fixed,
        lines,
        lines.isNotEmpty()
    )
}

private fun Finding.toFindingView(
    lang: ViewLanguage,
    mr: MrView?,
    shortNames: Map<String, String>,
    hideWhy: Boolean,
): FindingView {
    val ref = location as? Location.FileRef
    val where = location?.toRangeDisplayString() ?: ViewText.MR.text(lang)
    val line = ref?.line
    return FindingView(
        severity = SeverityFormat.toJson(severity),
        icon = SeverityFormat.toEmoji(severity),
        tag = SeverityFormat.toTag(severity),
        error = severity == Severity.ERROR,
        warning = severity == Severity.WARNING,
        info = severity == Severity.INFO,
        rule = rule,
        message = message.firstLine(),
        where = where,
        path = ref?.path,
        line = line,
        link = ref?.let { mr?.lineLinks["${it.path}:$line"] ?: mr?.url?.let(::changesUrl) },
        fix = suggestion?.takeIf { it.isNotBlank() }?.firstLine() ?: fixSuggestions.firstOrNull()?.describe(lang.tag),
        why = if (hideWhy) null else (explanation?.takeIf { it.isNotBlank() } ?: metadata[WellKnownMetadataKeys.WHY])?.firstLine(),
        shortWhere = ref?.let { (shortNames[it.path] ?: it.path) + where.removePrefix(it.path) } ?: where,
        fingerprint = line?.let { FindingFingerprint.generate(rule, location, message) },
    )
}

/**
 * Each of [paths] by its file name, or by as many trailing path segments as it takes to tell it apart from the
 * other paths: `a/x/Util.kt` and `b/x/Util.kt` are `a/x/Util.kt` and `b/x/Util.kt`, `a/Login.kt` alone is `Login.kt`.
 */
private fun shortNames(paths: List<String>): Map<String, String> {
    val distinct = paths.distinct()
    return distinct.associateWith { path ->
        val segments = path.split('/')
        (1..segments.size)
            .map { segments.takeLast(it).joinToString("/") }
            .firstOrNull { suffix -> distinct.none { it != path && (it == suffix || it.endsWith("/$suffix")) } }
            ?: path
    }
}

/**
 * The changes page of the merge request at [mrUrl]: GitLab `…/merge_requests/N/diffs`, GitHub `…/pull/N/files`. The
 * fallback for a line [MrView.lineLinks] has no anchor for (the CLI computes those from the merge request's diff).
 */
private fun changesUrl(mrUrl: String): String {
    val base = mrUrl.trimEnd('/')
    return if ("/pull/" in base) "$base/files" else "$base/diffs"
}

private fun String.firstLine(): String = lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
