package io.karandash.plugins.ai.dispatch

import io.karandash.logger.Logger
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.plugins.ai.core.AiCostEstimator
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.FeedbackStore
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.serialization.Serializable

/**
 * Token usage and estimated cost delta for a set of findings.
 *
 * Sums the provider-reported usage of every call of the dispatch. Without it, falls back to the max
 * token count in finding metadata (all findings of one call carry the same total), then the budget delta.
 */
@Serializable
internal data class CostDelta(
    val tokensUsed: Int,
    val estimatedCostUsd: Double,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val model: String = "",
    /** False when [model] has no pricing row, so [estimatedCostUsd] is a placeholder 0. */
    val priced: Boolean = true,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
)

/** Result of post-processing a set of raw findings for one rule dispatch. */
@Serializable
internal data class PostProcessResult(
    val findings: List<Finding>,
    val tokensUsed: Int,
    val durationMs: Long,
    val estimatedCostUsd: Double,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** Findings dropped because the same finding was dismissed before. */
    val feedbackSuppressed: Int = 0,
)

/**
 * Takes input/output from the provider-reported sums in [usage], or splits evenly
 * when the provider reported none (e.g. a custom rule path that bypasses the metered provider).
 */
internal fun computeCostDelta(
    findings: List<Finding>,
    usage: DispatchUsage = DispatchUsage(budgetTokens = 0),
    defaultModel: String,
    estimator: AiCostEstimator = AiCostEstimator(),
): CostDelta {
    val reported = usage.promptTokens + usage.completionTokens
    // the metered sum covers every call of the dispatch (all chunks, samples, retries); finding metadata only the call that produced it
    val tokensUsed = if (reported > 0) {
        reported
    } else {
        findings.maxOfOrNull { it.metadata[AiFindingMetadataKeys.AI_TOKENS_USED]?.toIntOrNull() ?: 0 } ?: 0
    }
    val effectiveTokens = if (tokensUsed > 0) tokensUsed else usage.budgetTokens
    if (effectiveTokens <= 0) return CostDelta(tokensUsed = 0, estimatedCostUsd = 0.0)
    val modelUsed = findings.firstNotNullOfOrNull { it.metadata[AiFindingMetadataKeys.AI_MODEL] } ?: defaultModel
    val input = if (reported > 0) usage.promptTokens else effectiveTokens / 2
    val output = if (reported > 0) usage.completionTokens else effectiveTokens / 2
    val cost = estimator.estimateCost(modelUsed, input, output, usage.cacheReadTokens, usage.cacheWriteTokens) ?: 0.0
    return CostDelta(
        tokensUsed = effectiveTokens,
        estimatedCostUsd = cost,
        inputTokens = input,
        outputTokens = output,
        model = modelUsed,
        priced = estimator.pricingFor(modelUsed) != null,
        cacheReadTokens = usage.cacheReadTokens,
        cacheWriteTokens = usage.cacheWriteTokens,
    )
}

/**
 * Handles post-dispatch steps: timing metadata, feedback filtering, post-validation hooks and
 * the per-rule cost line. [costEstimator] carries `ai.pricing`.
 */
internal class AiFeedbackCostFilter(
    private val logger: Logger,
    private val costEstimator: AiCostEstimator = AiCostEstimator(),
) {
    private val unpricedModels = atomic(emptySet<String>())

    fun applyFeedbackFiltering(
        ruleId: String,
        findings: List<Finding>,
        feedbackEnabled: Boolean,
        feedbackStore: FeedbackStore,
    ): List<Finding> {
        if (!feedbackEnabled) return findings
        val feedbackResult = feedbackStore.filterFindings(findings)
        if (feedbackResult.filteredCount > 0) {
            logger.info("AI plugin: Filtered ${feedbackResult.filteredCount} previously dismissed findings for $ruleId")
            feedbackResult.filtered.forEach { f ->
                val loc = (f.location as? Location.FileRef)?.let { " @ ${it.path}:${it.line}" } ?: ""
                logger.debug("AI plugin [$ruleId]: dismissed (feedback): ${f.message.take(100)}$loc")
            }
        }
        return feedbackResult.kept
    }

    suspend fun postProcess(
        ruleId: String,
        rawFindings: List<Finding>,
        durationMs: Long,
        usage: DispatchUsage,
        feedbackEnabled: Boolean,
        feedbackStore: FeedbackStore,
        defaultModel: String,
    ): PostProcessResult {
        logger.debug(
            "AI plugin [$ruleId]: completed in ${durationMs}ms — ${rawFindings.size} finding(s) before filtering"
        )

        val timedFindings = rawFindings.map { finding ->
            finding.copy(metadata = finding.metadata + ("aiDurationMs" to durationMs.toString()))
        }
        val filtered = applyFeedbackFiltering(ruleId, timedFindings, feedbackEnabled, feedbackStore)
        val feedbackSuppressed = timedFindings.size - filtered.size
        // config hooks run once per run in AiPipelineExtension, not here per rule.
        val allFindings = filtered

        val cost = computeCostDelta(allFindings, usage, defaultModel, costEstimator)
        logCost(ruleId, cost)
        return PostProcessResult(
            findings = allFindings,
            tokensUsed = cost.tokensUsed,
            durationMs = durationMs,
            estimatedCostUsd = cost.estimatedCostUsd,
            inputTokens = cost.inputTokens,
            outputTokens = cost.outputTokens,
            feedbackSuppressed = feedbackSuppressed,
        )
    }

    private fun cacheSuffix(cost: CostDelta): String =
        if (cost.cacheReadTokens + cost.cacheWriteTokens == 0) "" else "cache=read:${cost.cacheReadTokens}/write:${cost.cacheWriteTokens} "

    private fun logCost(
        ruleId: String,
        cost: CostDelta,
    ) {
        if (cost.tokensUsed <= 0) return
        logger.info(
            "[ai] rule=$ruleId model=${cost.model} tokens=${cost.inputTokens}+${cost.outputTokens} " +
                cacheSuffix(cost) + "cost=${AiCostEstimator.formatCost(cost.estimatedCostUsd)}",
        )
        if (!cost.priced && cost.model !in unpricedModels.getAndUpdate { it + cost.model }) {
            logger.warn("[ai] no price known for model '${cost.model}': cost reported as \$0; add it under ai.pricing")
        }
    }
}
