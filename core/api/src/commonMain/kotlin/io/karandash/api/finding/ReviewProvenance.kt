package io.karandash.api.finding

import io.karandash.models.finding.PluralRule
import io.karandash.models.finding.Translations
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a run produced its review, carried on [ValidationReport.provenance]: one type for coverage, the enrichers,
 * analyzers and filters behind it, and the tamper-evident finding hash chain.
 *
 * @property coverage How much of the MR the AI review covered; null when no AI rule ran. Its `dropped` and
 *   `contextDropped` are the T1 (diff) and T2 (context) cuts.
 * @property enrichers Each enrichment provider of the run, ok, skipped (with why) or failed.
 * @property analyzers Each AI analyzer of the run: model, tokens and cost.
 * @property filters Findings each filter stage removed, feedback suppressions included; stages that removed
 *   nothing are left out.
 * @property chain The finding hash chain, when `ai.architecture.provenance-chain` is on.
 * @property aiStatus One line on what the AI review did this run (`AI: ok — 4 rules, 23.1k tokens`,
 *   `AI: unavailable — 12/12 calls failed`, `AI: off by policy 'docs-only'`); printed under the summary heading.
 *   Null when AI is not configured.
 * @property funnel The review-comments funnel by stage (`sampled`, `agreed`, `grounded`, `posted`, `singletons`,
 *   `refuted`); empty when the run had no review comments.
 * @property aiStatusTemplate The English template of [aiStatus] (`AI: unavailable — %1/%2 calls failed`), the key a
 *   translation pack translates it by; null when [aiStatus] has none (a plugin that sets only the line).
 * @property aiStatusArgs The values of `%1`, `%2`, ... in [aiStatusTemplate].
 * @property notes One-line notes on how the review was made (incremental scope, an underfunded budget plan); English,
 *   never translated.
 */
@Serializable
public data class ReviewProvenance(
    val coverage: ReviewCoverage? = null,
    val enrichers: List<EnricherProvenance> = emptyList(),
    val analyzers: List<AnalyzerProvenance> = emptyList(),
    val filters: List<FilterProvenance> = emptyList(),
    val chain: FindingHashChain? = null,
    val aiStatus: String? = null,
    val funnel: Map<String, Int> = emptyMap(),
    val aiStatusTemplate: String? = null,
    val aiStatusArgs: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    /** The 2.0.1 constructor without [notes], kept for binary compatibility with 2.0.0 callers. */
    public constructor(
        coverage: ReviewCoverage?,
        enrichers: List<EnricherProvenance>,
        analyzers: List<AnalyzerProvenance>,
        filters: List<FilterProvenance>,
        chain: FindingHashChain?,
        aiStatus: String?,
        funnel: Map<String, Int>,
        aiStatusTemplate: String?,
        aiStatusArgs: List<String>,
    ) : this(coverage, enrichers, analyzers, filters, chain, aiStatus, funnel, aiStatusTemplate, aiStatusArgs, emptyList())

    /**
     * [aiStatus] in [language]: its [aiStatusTemplate] translated by a pack and filled with its values (a skip reason
     * translated by [skipReasonIn]), else [aiStatus] as set.
     */
    public fun aiStatusIn(language: String?): String? =
        aiStatusTemplate?.let { template ->
            aiStatusArgs.foldIndexed(Translations.text(template, language)) { i, acc, v ->
                acc.replace("%${i + 1}", skipReasonIn(v, language))
            }
        } ?: aiStatus

    /** True when there is more to show than the [coverage] line. */
    public val hasDetails: Boolean
        get() = enrichers.isNotEmpty() || analyzers.isNotEmpty() || filters.isNotEmpty() || notes.isNotEmpty()
}

/** Outcome of one enricher or analyzer step. */
@Serializable
public enum class ProvenanceStatus {
    @SerialName("ok")
    OK,

    @SerialName("skipped")
    SKIPPED,

    @SerialName("failed")
    FAILED,
}

/**
 * One enrichment provider of the run.
 *
 * @property id Provider id.
 * @property status Ok, skipped (switched off, e.g. a missing token or tool) or failed (threw or timed out).
 * @property reason Why it was skipped or failed; null when ok.
 * @property tokens Estimated tokens of the data it delivered (4 chars per token).
 * @property items Enrichment ids it delivered this run; 0 when it ran and found nothing.
 * @property cached True when its outputs were already in the context, so it did not run.
 */
@Serializable
public data class EnricherProvenance(
    val id: String,
    val status: ProvenanceStatus,
    val reason: String? = null,
    val tokens: Int = 0,
    val items: Int = 0,
    val cached: Boolean = false,
) {
    /** The 2.0.1 constructor without [items] and [cached], kept for binary compatibility with 2.0.0 callers. */
    public constructor(id: String, status: ProvenanceStatus, reason: String?, tokens: Int) :
        this(id, status, reason, tokens, 0, false)
}

/**
 * One AI analyzer (rule) of the run.
 *
 * @property ruleId The analyzer's rule id.
 * @property status Ok when it reached the model, skipped otherwise.
 * @property reason Why it was skipped; null when ok.
 * @property model Model it ran on; null when skipped.
 * @property inputTokens Prompt tokens.
 * @property outputTokens Completion tokens.
 * @property costUsd Estimated cost in USD; 0 for an unpriced model.
 * @property findings Findings it returned after its own filters.
 * @property context How its last request used `ai.context.budget` (budget, used %, diff and per-tier tokens kept,
 *   cut and dropped, diff hunks cut); null when it planned no request.
 */
@Serializable
public data class AnalyzerProvenance(
    val ruleId: String,
    val status: ProvenanceStatus = ProvenanceStatus.OK,
    val reason: String? = null,
    val model: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val costUsd: Double = 0.0,
    val findings: Int = 0,
    val context: String? = null,
)

/**
 * Findings one filter stage removed.
 *
 * @property stage Stage name: `feedback` (dismissed before), `grounding`, `actionability`, ...
 * @property removed Findings the stage removed.
 */
@Serializable
public data class FilterProvenance(
    val stage: String,
    val removed: Int,
) {
    public companion object {
        /** Stage name of findings suppressed because the same finding was dismissed before. */
        public const val FEEDBACK: String = "feedback"
    }
}

/**
 * Tamper-evident hash chain over a run's findings, for audit-grade evidence (SOC2, ISO27001).
 *
 * @property entries Per-finding entries in order.
 * @property chainHash Hash of all entry hashes combined.
 * @property configHash Hash of the config snapshot used.
 * @property mrSnapshotHash Hash of the MR data snapshot.
 * @property toolVersion Version of the tool that produced this chain.
 * @property findingCount Total number of findings in the chain.
 */
@Serializable
public data class FindingHashChain(
    val entries: List<FindingHashChainEntry>,
    val chainHash: String,
    val configHash: String,
    val mrSnapshotHash: String,
    val toolVersion: String,
    val findingCount: Int,
)

/**
 * One finding in a [FindingHashChain].
 *
 * @property findingRule The rule ID that produced the finding.
 * @property findingSeverity The severity name of the finding.
 * @property findingHash Hash of this entry's content (chained from [previousHash]).
 * @property previousHash Hash of the preceding entry (or the config hash for the first entry).
 * @property configHash Hash of the config snapshot for this run.
 * @property mrSnapshotHash Hash of the MR data snapshot for this run.
 * @property toolVersion Version of the tool that produced this entry.
 */
@Serializable
public data class FindingHashChainEntry(
    val findingRule: String,
    val findingSeverity: String,
    val findingHash: String,
    val previousHash: String,
    val configHash: String,
    val mrSnapshotHash: String,
    val toolVersion: String,
)

/**
 * File and chunk coverage of the AI review, merged over all AI rules of the run.
 *
 * @property totalFiles Changed files with a diff.
 * @property reviewedFiles Files at least one AI rule sent to the model.
 * @property chunksSkipped Diff chunks dropped because the token budget ran out.
 * @property truncatedFiles Reviewed files whose diff was larger than one chunk and was cut.
 * @property dropped Diff the budget or the chunk limit left out and no AI rule reviewed: a bare path for a whole
 *   file, `path (N of M hunks)` for a file whose other hunks were reviewed.
 * @property contextDropped Prompt context sections an AI rule left out or cut to fit its budget.
 * @property excluded Files the diff preprocessor dropped whole, by design rather than for the budget, each named
 *   `path (reason)` (`deleted`, `pure rename`, `binary`).
 */
@Serializable
public data class ReviewCoverage(
    val totalFiles: Int,
    val reviewedFiles: Int,
    val chunksSkipped: Int = 0,
    val truncatedFiles: Int = 0,
    val dropped: List<String> = emptyList(),
    val contextDropped: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
) {
    /** `reviewed X/Y files, N chunks skipped, M truncated`, then what was left out, when anything was. */
    public fun describe(): String = describe(null)

    /** [describe] in [language] (English when unset or without a pack). */
    public fun describe(language: String?): String {
        val rule = Translations.pack(language)?.pluralRule ?: PluralRule.ONE_OTHER
        return listOf(
            CoverageText.REVIEWED.of(language, "$reviewedFiles/$totalFiles"),
            rule.count(Translations.text(CoverageText.CHUNKS_SKIPPED.en, language), chunksSkipped),
            CoverageText.TRUNCATED.of(language, truncatedFiles.toString()),
        ).joinToString(", ") +
            named(CoverageText.LEFT_OUT, dropped, language) +
            named(CoverageText.CONTEXT_LEFT_OUT, contextDropped, language) +
            named(CoverageText.EXCLUDED, excluded, language)
    }
}

/**
 * The [ReviewCoverage.describe] texts in English; other languages from a pack. `%1`, `%2` are values; a counted text
 * has three forms `one|few|many` with `%d` for the number.
 */
internal enum class CoverageText(
    val en: String,
) {
    REVIEWED("reviewed %1 files"),
    CHUNKS_SKIPPED("%d chunk skipped|%d chunks skipped|%d chunks skipped"),
    TRUNCATED("%1 truncated"),
    LEFT_OUT("left out: %1"),
    CONTEXT_LEFT_OUT("context left out: %1"),
    EXCLUDED("excluded: %1"),
    AND_MORE("%1 and %2 more"),
    DROPPED_HUNKS("%1 (%2 of %d hunks)|%1 (%2 of %d hunks)|%1 (%2 of %d hunks)"),
    ;

    fun of(
        language: String?,
        vararg values: String,
    ): String = values.foldIndexed(Translations.text(en, language)) { i, acc, v -> acc.replace("%${i + 1}", v) }
}

/** `, left out: a, b`, at most [MAX_NAMED] names; empty when there are no [names]. */
private fun named(
    label: CoverageText,
    names: List<String>,
    language: String?,
): String =
    when {
        names.isEmpty() -> {
            ""
        }

        names.size <= MAX_NAMED -> {
            ", " + label.of(language, names.joinToString(", ") { droppedName(it, language) })
        }

        else -> {
            val shown = names.take(MAX_NAMED).joinToString(", ") { droppedName(it, language) }
            ", " + label.of(language, CoverageText.AND_MORE.of(language, shown, "${names.size - MAX_NAMED}"))
        }
    }

/** A `path (N of M hunks)` name in [language], counted by M; any other name, and any name in English, as given. */
private fun droppedName(
    name: String,
    language: String?,
): String {
    val rule = Translations.pack(language)?.pluralRule ?: return name
    val open = name.lastIndexOf(" (")
    if (open < 0 || !name.endsWith(HUNKS_SUFFIX)) return name
    val counts = name.substring(open + 2, name.length - HUNKS_SUFFIX.length).split(" of ")
    val total = counts.getOrNull(1)?.toIntOrNull()
    if (counts.size != 2 || counts[0].toIntOrNull() == null || total == null) return name
    return rule
        .count(Translations.text(CoverageText.DROPPED_HUNKS.en, language), total)
        .replace("%1", name.substring(0, open))
        .replace("%2", counts[0])
}

private const val HUNKS_SUFFIX = " hunks)"

private const val MAX_NAMED = 10
