package io.karandash.plugins.ai.dispatch

import io.karandash.plugins.ai.core.ContextCoverage
import kotlinx.serialization.Serializable

/** `--dump-dir` `ai/<rule>/plan.json`: how the token budget split between diff and context, and the model picked. */
@Serializable
internal data class DispatchPlanDump(
    val diffTokensWanted: Int,
    val diffTokens: Int,
    val contextTokens: Int,
    val skipReason: String?,
    val contextDropped: List<String>,
    val keptBlocks: List<KeptBlock>,
    val routedModel: String?,
    val model: String,
    /** How the request used `ai.context.budget`; null when T0 alone did not fit. */
    val coverage: ContextCoverage? = null,
    /** The rule's diff in and out of the calls; filled after dispatch. */
    val diff: DiffTotals? = null,
    /** Files sent short or not at all, and how. */
    val cuts: List<DiffCut> = emptyList(),
    /** Chunks no call reviewed, and why. */
    val skippedChunks: List<SkippedChunk> = emptyList(),
) {
    /** One context block that made it into the prompt. */
    @Serializable
    data class KeptBlock(
        val heading: String,
        val source: String,
        val chars: Int,
    )

    companion object {
        fun of(
            tiered: TieredExtras,
            diffTokensWanted: Int,
            routedModel: String?,
            model: String,
        ) = DispatchPlanDump(
            diffTokensWanted = diffTokensWanted,
            diffTokens = tiered.diffTokens,
            contextTokens = tiered.contextTokens,
            skipReason = tiered.skipReason,
            contextDropped = tiered.contextDropped,
            keptBlocks = tiered.contextBlocks.map { KeptBlock(it.heading, it.source, it.content.length) },
            routedModel = routedModel,
            model = model,
            coverage = tiered.coverage,
        )
    }
}
