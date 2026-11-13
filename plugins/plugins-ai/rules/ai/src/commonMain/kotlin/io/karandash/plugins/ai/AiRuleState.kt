package io.karandash.plugins.ai

import io.karandash.api.finding.FilterProvenance
import io.karandash.api.finding.FindingHashChain
import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.dispatch.RuleCoverage
import kotlinx.serialization.Serializable

@Serializable
internal data class AiRuleState(
    val accumulatedFindings: List<Finding> = emptyList(),
    /** the non-AI rules' findings fix-suggestions was handed in pass 2; kept apart so the run reset keeps them. */
    val deterministicFindings: List<Finding> = emptyList(),
    val ruleTokenCounts: Map<String, Int> = emptyMap(),
    val ruleTimings: Map<String, Long> = emptyMap(),
    /** Why each AI rule did not run (or failed), keyed by rule id; what [AiRuleModule.skippedRuleReasons] reports. */
    val ruleSkipReasons: Map<String, SkippedRuleInfo> = emptyMap(),
    /** AI rules the engine skipped before they reached this module (bail, policy); reported by the engine itself. */
    val engineSkips: Map<String, SkippedRuleInfo> = emptyMap(),
    val totalEstimatedCostUsd: Double = 0.0,
    val ruleInputTokens: Map<String, Int> = emptyMap(),
    val ruleOutputTokens: Map<String, Int> = emptyMap(),
    val costSummaryLogged: Boolean = false,
    val ruleCoverage: Map<String, RuleCoverage> = emptyMap(),
    /** Each rule's `ai.context.budget` use, the [io.karandash.plugins.ai.core.ContextCoverage] line. */
    val ruleContext: Map<String, String> = emptyMap(),
    val ruleModels: Map<String, String> = emptyMap(),
    val ruleCostUsd: Map<String, Double> = emptyMap(),
    val ruleFindingCounts: Map<String, Int> = emptyMap(),
    val feedbackSuppressed: Int = 0,
    /** Post-validation filter stages of the AI pipeline extension, recorded after the rules ran. */
    val postFilters: List<FilterProvenance> = emptyList(),
    /** The review-comments funnel counts by stage ([ReviewFunnel.counts]). */
    val reviewFunnel: Map<String, Int> = emptyMap(),
    val chain: FindingHashChain? = null,
    /** auxiliary AI call tokens per triggering analyzer (or stage), kept apart from [ruleTokenCounts]. */
    val auxiliaryTokens: Map<String, Int> = emptyMap(),
    /** Tokens of the extra review-comments samples per rule: part of [ruleTokenCounts], outside the run budget. */
    val sampleTokens: Map<String, Int> = emptyMap(),
    /** AI rule findings the pipeline extension served from the incremental cache this run; null when none were. */
    val cachedFindings: Int? = null,
    /** The pipeline extension's incremental-review line ([IncrementalSummary.note]); null when not incremental. */
    val incrementalNote: String? = null,
)

/** This state with [ruleId] skipped for [reason]; the category is read from the reason unless given. */
internal fun AiRuleState.withSkip(
    ruleId: String,
    reason: String,
    category: SkipCategory = AiRuleErrorHandlers.categorizeSkipReason(reason),
): AiRuleState = copy(ruleSkipReasons = ruleSkipReasons + (ruleId to SkippedRuleInfo(ruleId, reason, category)))
