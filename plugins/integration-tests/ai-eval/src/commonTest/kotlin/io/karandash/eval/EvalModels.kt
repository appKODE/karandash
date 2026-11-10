@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.eval

import io.karandash.api.finding.ReviewThread
import io.karandash.api.platform.MergeRequestSearchHit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One labelled MR in the eval corpus (`eval/corpus/<id>.json`).
 *
 * Optional replay keys: `cassette` maps analyzer id to the raw LLM response text that analyzer
 * gets (wins over `eval/recorded/<id>.json`); `minedFrom` records where a mined case came from
 * (e.g. `group/project!123`), informational only.
 */
@Serializable
data class EvalCase(
    val id: String,
    val title: String,
    val description: String? = null,
    val sourceBranch: String = "feature/eval",
    val labels: List<String> = emptyList(),
    val commits: List<String> = emptyList(),
    val files: List<EvalFile> = emptyList(),
    /** Design doc handed to analyzers as the design-doc enricher's context block (design-doc-drift). */
    val designDoc: String? = null,
    /** Findings from earlier rules, handed to analyzers as `accumulatedFindings` (fix-suggestions). */
    val priorFindings: List<EvalPriorFinding> = emptyList(),
    /** Repository files at the source SHA, path to content (outdated-docs reads the docs near the change). */
    val repositoryFiles: Map<String, String> = emptyMap(),
    /** What the platform's merge-request search answers (similar-issues takes its history from it). */
    val pastMergeRequests: List<MergeRequestSearchHit> = emptyList(),
    /** The MR's review threads (review-thread-analysis needs an open one people started to make its call). */
    val reviewThreads: List<ReviewThread> = emptyList(),
    /** Analyzer ids this case is scored for; every one must have a recorded response. */
    val analyzers: List<String>,
    /** Ground truth: every finding an ideal analyzer would emit. Empty for a known-clean MR. */
    val expected: List<EvalLabel> = emptyList(),
    val cassette: Map<String, String> = emptyMap(),
    val minedFrom: String? = null,
)

@Serializable
data class EvalFile(
    val path: String,
    val changeType: String = "MODIFIED",
    /** Unified diff lines, hunk headers included. */
    val diff: List<String> = emptyList(),
)

@Serializable
data class EvalPriorFinding(
    val rule: String,
    val severity: String = "WARNING",
    val message: String,
)

/** Expected finding. Null fields are not checked by the matcher. */
@Serializable
data class EvalLabel(
    val analyzer: String,
    val file: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val category: String? = null,
)

/** Recorded LLM responses for one case (`eval/recorded/<id>.json`): analyzer id to raw response. */
@Serializable
data class EvalRecording(
    val responses: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class EvalScore(
    val tp: Int = 0,
    val fp: Int = 0,
    val fn: Int = 0,
    /** Findings that re-hit an already matched label. */
    val duplicates: Int = 0,
    /** Hits whose label pins a line and whose finding carries one. */
    val anchorable: Int = 0,
    /** Anchorable hits whose line lies inside the label range, without the matcher's window. */
    val anchored: Int = 0,
) {
    val duplicateRate: Double get() = ratio(duplicates, tp + fp + duplicates, 0.0)
    val anchorAccuracy: Double get() = ratio(anchored, anchorable, 1.0)
    val precision: Double get() = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
    val recall: Double get() = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn)
    val f1: Double get() = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)

    operator fun plus(other: EvalScore): EvalScore =
        EvalScore(
            tp + other.tp,
            fp + other.fp,
            fn + other.fn,
            duplicates + other.duplicates,
            anchorable + other.anchorable,
            anchored + other.anchored,
        )
}

@Serializable
data class EvalMetrics(
    val precision: Double,
    val recall: Double,
    val f1: Double,
    val tp: Int = 0,
    val fp: Int = 0,
    val fn: Int = 0,
    val duplicates: Int = 0,
    val anchorable: Int = 0,
    val anchored: Int = 0,
) {
    val duplicateRate: Double get() = ratio(duplicates, tp + fp + duplicates, 0.0)
    val anchorAccuracy: Double get() = ratio(anchored, anchorable, 1.0)
}

private fun ratio(
    part: Int,
    whole: Int,
    empty: Double,
): Double = if (whole == 0) empty else part.toDouble() / whole

/** What the corpus covers: scored cases, distinct analyzers scored, labelled expected findings. */
@Serializable
data class EvalCoverage(
    val cases: Int = 0,
    val analyzers: Int = 0,
    val labels: Int = 0,
)

/**
 * Report written to `build/reports/ai-eval/report.json`; the baseline file uses the same shape.
 * [pipeline] holds the full-pipeline replay metrics per case id (absent in live runs).
 */
@Serializable
data class EvalReport(
    val mode: String = "replay",
    val overall: EvalMetrics,
    val analyzers: Map<String, EvalMetrics>,
    val misses: List<String> = emptyList(),
    val pipeline: Map<String, PipelineMetrics> = emptyMap(),
    val coverage: EvalCoverage = EvalCoverage(),
    /** Floors from the quality plan that the synthetic corpus cannot measure, with the reason. */
    val notMeasured: Map<String, String> = emptyMap(),
)

/**
 * Everything one case produced when replayed through the full pipeline: the exact prompts sent per
 * analyzer (after redaction), the findings left after AI post-processing, the rendered summary
 * markdown and inline note bodies, and the stage counters. Golden snapshot: `eval/replay/<id>.md`.
 */
@Serializable
data class CaseReplay(
    val caseId: String,
    val prompts: List<ReplayPrompt>,
    val findings: List<ReplayFinding>,
    val summary: String,
    val inlineNotes: List<ReplayNote>,
    val counters: ReplayCounters,
)

/** One outgoing LLM request as the provider would receive it. */
@Serializable
data class ReplayPrompt(
    val analyzer: String,
    val systemPrompt: String?,
    val prompt: String,
)

@Serializable
data class ReplayFinding(
    val rule: String,
    val severity: String,
    val file: String?,
    val line: Int?,
    val message: String,
)

/** One inline note posted on the diff; [startLine] is set for multi-line suggestions. */
@Serializable
data class ReplayNote(
    val file: String,
    val line: Int,
    val startLine: Int?,
    val body: String,
)

/**
 * Stage counters for one case: raw analyzer findings, findings the rule engine got back from the
 * rules, findings left after the AI pipeline extension (dedup, confidence, cost filters), and posted
 * inline notes. `skippedRules` are engine skips, `selfSkipped` analyzers that logged their own early
 * return (threshold, inline mode, no input; not in the engine's skip list). `loggedDrops` counts `finding-dropped` diagnostics by reason (only the context-quality
 * filter logs them). `silentDrops` names case analyzers that sent no prompt and got no skip reason,
 * cassette misses and calls that bypassed the interceptor chain; `estimatedTokens` is chars / 4.
 */
@Serializable
data class ReplayCounters(
    val analyzerFindings: Int = 0,
    val ruleFindings: Int = 0,
    val afterValidation: Int = 0,
    val notes: Int = 0,
    val skippedRules: Map<String, String> = emptyMap(),
    val selfSkipped: Map<String, String> = emptyMap(),
    val loggedDrops: Map<String, Int> = emptyMap(),
    val silentDrops: List<String> = emptyList(),
    val estimatedTokens: Int = 0,
)

/** Per-case full-pipeline metrics kept in `eval/baseline.json` under `pipeline`. */
@Serializable
data class PipelineMetrics(
    val notes: Int,
    val findingsKept: Int,
    val findingsDropped: Int,
    val silentDrops: Int,
    val estimatedTokens: Int,
)

fun ReplayCounters.toMetrics(): PipelineMetrics =
    PipelineMetrics(notes, afterValidation, (analyzerFindings - afterValidation).coerceAtLeast(0), silentDrops.size, estimatedTokens)

fun EvalScore.toMetrics(): EvalMetrics =
    EvalMetrics(round(precision), round(recall), round(f1), tp, fp, fn, duplicates, anchorable, anchored)

private const val ROUNDING = 10_000.0

private fun round(value: Double): Double = kotlin.math.round(value * ROUNDING) / ROUNDING
