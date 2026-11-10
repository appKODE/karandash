@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.finding.AnalyzerProvenance
import io.karandash.api.finding.FilterProvenance
import io.karandash.api.finding.ProvenanceStatus
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.SkipCategory
import io.karandash.api.run.dumpJson
import io.karandash.models.finding.Finding
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.dispatch.PostProcessResult
import io.karandash.plugins.ai.dispatch.RuleCoverage

/** Records [ruleId]'s model, cost, finding count and feedback suppressions after one dispatch. */
internal fun AiRuleState.withAnalyzerRun(
    ruleId: String,
    model: String,
    result: PostProcessResult,
): AiRuleState =
    copy(
        ruleModels = ruleModels + (ruleId to model),
        ruleCostUsd = ruleCostUsd + (ruleId to (ruleCostUsd[ruleId] ?: 0.0) + result.estimatedCostUsd),
        ruleFindingCounts = ruleFindingCounts + (ruleId to (ruleFindingCounts[ruleId] ?: 0) + result.findings.size),
        feedbackSuppressed = feedbackSuppressed + result.feedbackSuppressed,
    )

/**
 * The run's [ReviewProvenance]: coverage, one row per AI rule (ran or skipped, sorted by id so the report does not
 * depend on scheduling), feedback suppressions then the post-validation filter stages, and the hash chain.
 * Null when no AI rule ran or skipped and there is no [aiStatus] line.
 */
internal fun AiRuleState.provenance(aiStatus: Pair<AiStatusText, List<String>>? = null): ReviewProvenance? {
    val coverage = RuleCoverage.merge(ruleCoverage.values)
    val analyzers = (ruleModels.keys + ruleSkipReasons.keys).sorted().map { id ->
        val model = ruleModels[id]
        val skip = ruleSkipReasons[id]
        AnalyzerProvenance(
            ruleId = id,
            // a dispatch that completed is OK even when some chunks timed out or failed; the reason keeps that
            status = when {
                model != null -> ProvenanceStatus.OK
                skip?.category == SkipCategory.AI_PROVIDER_ERROR -> ProvenanceStatus.FAILED
                else -> ProvenanceStatus.SKIPPED
            },
            reason = skip?.reason,
            model = model,
            inputTokens = ruleInputTokens[id] ?: 0,
            outputTokens = ruleOutputTokens[id] ?: 0,
            costUsd = ruleCostUsd[id] ?: 0.0,
            findings = ruleFindingCounts[id] ?: 0,
            context = ruleContext[id],
        )
    }
    if (coverage == null && analyzers.isEmpty() && aiStatus == null) return null
    val feedback = listOfNotNull(feedbackSuppressed.takeIf { it > 0 }?.let { FilterProvenance(FilterProvenance.FEEDBACK, it) })
    return ReviewProvenance(
        coverage = coverage,
        analyzers = analyzers,
        filters = feedback + postFilters,
        chain = chain,
        aiStatus = aiStatus?.let { (text, args) -> text.of(null, args) },
        funnel = reviewFunnel,
        aiStatusTemplate = aiStatus?.first?.en,
        aiStatusArgs = aiStatus?.second.orEmpty(),
    )
}

/** This finding with its [WellKnownMetadataKeys.WHY]: the rule, the model and the enrichment it was given. */
internal fun Finding.withWhy(
    ruleId: String,
    model: String,
    enrichmentIds: Set<String>,
): Finding {
    val context = if (enrichmentIds.isEmpty()) "diff only" else "context: " + enrichmentIds.sorted().joinToString(", ")
    return copy(metadata = metadata + (WellKnownMetadataKeys.WHY to "`$ruleId` on $model; $context"))
}

/**
 * Runs one post-validation filter stage on [input] and records how many findings it removed, when any; with
 * `--dump-dir` the stage output goes to `ai/stages/<stage>.json`.
 * limit: a stage that removes and adds findings in one pass records only the net drop.
 */
internal suspend inline fun MutableList<FilterProvenance>.track(
    stage: String,
    input: List<Finding>,
    apply: (List<Finding>) -> List<Finding>,
): List<Finding> {
    val output = apply(input)
    if (output.size < input.size) add(FilterProvenance(stage, input.size - output.size))
    dumpJson("ai/stages/$stage.json") { output }
    return output
}
