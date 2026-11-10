package io.karandash.models.report

import kotlinx.serialization.Serializable

/**
 * One validation run as every human-facing format shows it, and the data a report template renders
 * (`karandash format schema` prints its fields). Built once per format from the validation report; every text is
 * already localized and plain (templates escape it for their medium).
 *
 * @property verdict `Failed` or `Passed`.
 * @property headline The verdict line: `Failed · 3 errors · 1 warning — 2 new, 1 fixed since last run`.
 * @property findings The findings to list, most severe first, merge-request-level ones before file ones.
 * @property more How many findings were left out of [findings] to fit the medium.
 * @property files [findings] grouped by where they are: the merge request first, then each file.
 * @property runNumber Which run of this merge request this is, when known.
 * @property footer `karandash 1.0.0`, then `· run 3 of this MR` when [runNumber] is known.
 */
@Serializable
data class ReportView(
    val passed: Boolean,
    val verdict: String,
    val headline: String,
    val counts: CountsView,
    val delta: DeltaView? = null,
    val findings: List<FindingView> = emptyList(),
    val more: Int = 0,
    val files: List<FileGroupView> = emptyList(),
    val details: DetailsView = DetailsView(),
    val mr: MrView? = null,
    val version: String = "",
    val runNumber: Int? = null,
    val footer: String = "",
    val labels: LabelsView = LabelsView(),
)

/** Finding counts, with [text] such as `3 errors · 1 warning · 1 info` (only the non-zero ones). */
@Serializable
data class CountsView(
    val errors: Int = 0,
    val warnings: Int = 0,
    val infos: Int = 0,
    val total: Int = 0,
    val text: String = "",
)

/** What changed since the previous run, with [text] such as `2 new, 1 fixed since last run`. */
@Serializable
data class DeltaView(
    val new: Int = 0,
    val fixed: Int = 0,
    val unchanged: Int = 0,
    val text: String = "",
)

/**
 * One finding as a report row.
 *
 * @property severity `error`, `warning` or `info`; [error], [warning] and [info] are the same as flags for templates.
 * @property icon ❌, ⚠️ or ℹ️.
 * @property tag A short fixed-width severity word for terminals: `error`, `warn`, `info`.
 * @property where `path:line`, or `MR` (localized) for a merge-request-level finding.
 * @property link A deep link to the line (or else the changes page) in the merge request, when its URL is known.
 * @property fix One line on how to fix it, when the finding says.
 * @property why Why it matters, when the finding says.
 * @property shortWhere [where] for narrow media: the file name with its line (`Login.kt:79`), with as many parent
 *   directories as it takes to tell apart two shown files of the same name; [path] keeps the full path.
 * @property fingerprint The finding's semantic fingerprint (the `fp` of its inline marker) for a finding on a file
 *   line, so an output can tell which row an inline thread already shows.
 */
@Serializable
data class FindingView(
    val severity: String,
    val icon: String,
    val tag: String,
    val error: Boolean = false,
    val warning: Boolean = false,
    val info: Boolean = false,
    val rule: String,
    val message: String,
    val where: String,
    val path: String? = null,
    val line: Int? = null,
    val link: String? = null,
    val fix: String? = null,
    val why: String? = null,
    val shortWhere: String = "",
    val fingerprint: String? = null,
)

/** The findings in one place: [name] is a file path, or the localized merge-request label for MR-level findings. */
@Serializable
data class FileGroupView(
    val name: String,
    val findings: List<FindingView>,
)

/**
 * The collapsed part of a summary: risk, skipped rules, AI coverage, passed rules and what was fixed since the last
 * run. [lines] holds each present item as one localized line; [any] is true when there is at least one.
 */
@Serializable
data class DetailsView(
    val risk: Int? = null,
    val skipped: List<SkippedView> = emptyList(),
    val passedRules: List<String> = emptyList(),
    val aiCoverage: String? = null,
    val fixed: String? = null,
    val lines: List<String> = emptyList(),
    val any: Boolean = false,
)

/** A rule that did not run, and why. */
@Serializable
data class SkippedView(
    val rule: String,
    val reason: String = "",
)

/**
 * The merge request a report is about: [ref] such as `!42` or `#42`. [lineLinks] maps `path:line` of a finding to
 * the URL of that line on the changes page, when the platform's anchor is known.
 */
@Serializable
data class MrView(
    val ref: String = "",
    val title: String = "",
    val url: String? = null,
    val lineLinks: Map<String, String> = emptyMap(),
)

/** Localized fixed labels a template needs (column headers, section titles). */
@Serializable
data class LabelsView(
    val where: String = "Where",
    val finding: String = "Finding",
    val fix: String = "How to fix",
    val why: String = "Why",
    val details: String = "Details",
    val more: String = "more",
    val rule: String = "Rule",
)
