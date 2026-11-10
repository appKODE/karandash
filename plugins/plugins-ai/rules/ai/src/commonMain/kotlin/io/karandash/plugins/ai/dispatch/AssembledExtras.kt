package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiContextBlock
import io.karandash.plugins.ai.core.ContextCoverage
import io.karandash.plugins.ai.core.ContextTier
import io.karandash.plugins.ai.core.PromptPart
import io.karandash.plugins.ai.core.PromptTierPlanner
import io.karandash.plugins.ai.core.TierPlan
import io.karandash.plugins.ai.core.context.ConventionContextEnricher
import io.karandash.plugins.ai.core.context.DesignDocEnricher
import io.karandash.plugins.ai.core.context.DiffAnnotationsEnricher
import io.karandash.plugins.ai.core.context.FullFileContextEnricher
import io.karandash.plugins.ai.core.context.RelatedFileContextEnricher
import io.karandash.plugins.ai.core.context.SymbolContextEnricher
import io.karandash.plugins.ai.core.context.SymbolDefinitionsEnricher
import io.karandash.plugins.ai.core.context.TicketContentEnricher
import kotlinx.serialization.Serializable

/**
 * A rule's extra-instruction [parts] in prompt order; the ones named in [t0Names] are T0, the rest T2 context.
 * [cut] names, by part, the T2 parts cut to their own character cap, as the ledger reports them.
 */
@Serializable
internal data class AssembledExtras(
    val parts: List<PromptPart>,
    val t0Names: Set<String>,
    val cut: Map<String, String> = emptyMap(),
) {
    val t0: List<PromptPart> get() = parts.filter { it.name in t0Names }
    val t2: List<PromptPart> get() = parts.filterNot { it.name in t0Names }

    /** All parts joined in prompt order; `null` when there are none. */
    fun render(): String? = parts.joinToString("\n\n") { it.text }.ifEmpty { null }

    /**
     * The kept parts split for the cacheable prefix: instructions ([INSTRUCTION_PARTS]) stay in the extra
     * instructions, everything else is MR context and becomes a shared context block, in prompt order.
     */
    private fun tiered(
        keep: Set<String>,
        diffTokens: Int,
        contextDropped: List<String>,
        skipReason: String?,
        contextTokens: Int,
        planned: List<PromptPart> = emptyList(),
        coverage: ContextCoverage? = null,
    ): TieredExtras {
        val cutText = planned.associateBy { it.name }
        val kept = parts.filter { it.name in keep }.map { cutText[it.name] ?: it }
        val (instructions, context) = kept.partition { it.name in INSTRUCTION_PARTS }
        return TieredExtras(
            combinedExtra = instructions.joinToString("\n\n") { it.text }.ifEmpty { null },
            diffTokens = diffTokens,
            contextDropped = contextDropped,
            skipReason = skipReason,
            contextTokens = contextTokens,
            contextBlocks = context.map { AiContextBlock(heading = "", content = it.text, priority = it.priority, source = it.name) },
            coverage = coverage,
        )
    }

    /**
     * Fits T0 (with [systemPrompt] and the MR [metadata]), the diff and T2 context into [maxInputTokens]
     * (the resolved `ai.context.budget`) less [reserveOutputTokens] through [PromptTierPlanner], and never into more than [remainingTokens] of the
     * token budget less the same reserve. A T0 over the prompt cap is a skip naming the item; a T0 over only the
     * remaining budget is left to the dispatcher's budget gate, which records it as budget exhausted. With
     * [minDiffTokens] the budget never leaves the diff less than that, and a prompt cap that cannot is a skip too.
     */
    fun plan(
        systemPrompt: String,
        metadata: String,
        maxInputTokens: Int,
        diffTokensWanted: Int,
        reserveOutputTokens: Int = 0,
        remainingTokens: Int = Int.MAX_VALUE,
        splittableDiff: Boolean = false,
        minDiffTokens: Int = 0,
    ): TieredExtras {
        val t0Parts = listOf(PromptPart(SYSTEM_PROMPT, systemPrompt), PromptPart(MR_METADATA, metadata)) + t0
        val promptCap = (maxInputTokens - reserveOutputTokens).coerceAtLeast(0)
        val minDiff = minDiffTokens.coerceIn(0, diffTokensWanted.coerceAtLeast(0))
        val floor = if (minDiff > 0) PromptTierPlanner.tokensOf(t0Parts) + minDiff else 0
        val budgetCap = (remainingTokens - reserveOutputTokens).coerceAtLeast(floor)
        // A call floored to carry the least diff worth sending: a prompt cap below that skips it, never a stub diff.
        val cap = if (floor > promptCap) floor - minDiff else minOf(promptCap, budgetCap)
        return when (val plan = PromptTierPlanner.plan(cap, t0Parts, t2, diffTokensWanted, splittableDiff)) {
            is TierPlan.T0OverBudget -> {
                val reserve = if (reserveOutputTokens > 0) " less reserve-output-tokens $reserveOutputTokens" else ""
                val withDiff = if (minDiff > 0) " with $minDiff diff tokens" else ""
                val reason = "T0 over budget: ${plan.item} is ${plan.itemTokens} tokens, " +
                    "T0 is ${plan.t0Tokens}$withDiff of ai.context.budget $maxInputTokens$reserve"
                val overCap = plan.t0Tokens + minDiff > promptCap
                tiered(t0Names, 0, emptyList(), reason.takeIf { overCap }, PromptTierPlanner.tokensOf(t0Parts))
            }

            is TierPlan.Fits -> {
                val cutKept = cut.filterKeys { it !in plan.dropped && it !in plan.cut }.values
                val budgetCut = plan.cut.map { "$it ($CUT_TO_BUDGET)" }
                val contextTokens = PromptTierPlanner.tokensOf(plan.t0) + PromptTierPlanner.tokensOf(plan.kept)
                val coverage = ContextCoverage.of(plan, cap, diffTokensWanted)
                tiered(
                    t0Names + plan.kept.map { it.name },
                    plan.diffTokens,
                    plan.dropped + cutKept + budgetCut,
                    null,
                    contextTokens,
                    plan.kept,
                    coverage
                )
            }
        }
    }

    companion object {
        const val SYSTEM_PROMPT = "system-prompt"
        const val MR_METADATA = "mr-metadata"

        /** Matched `ai.review.instructions`, T0. */
        const val REVIEW_INSTRUCTIONS = "reviewInstructions"

        /**
         * Parts that instruct the model (and differ per rule); they ride in the system prompt. Every other part
         * (enricher blocks by enricher id, cross-file and dependency context) is MR context, sent as a shared
         * context block ahead of the analyzer's own prompt so the prefix is the same for every analyzer.
         */
        val INSTRUCTION_PARTS: Set<String> = setOf(
            "responseLanguage",
            "findingLocationConstraint",
            "extraInstructions",
            "bestPractices",
            "scopes",
            REVIEW_INSTRUCTIONS,
            "languageOverlay",
            "strictness",
            "feedback",
            "priorFindings",
        )

        /** Enricher blocks that are T0: ticket bodies are never cut. Conventions are T2, the lowest [ContextTier]. */
        val T0_ENRICHERS: Set<String> = setOf(TicketContentEnricher.MODULE_ID)

        /** How a part cut by the context budget is named in the ledger. */
        const val CUT_TO_BUDGET = "cut to fit ai.context.budget"

        /** Name of a deleted/moved-code context part, the most valuable T2 tier. */
        const val DELETED_CODE = "deletedCode"

        /** Name of the built-in cross-file context part (signatures of symbols the diff touches). */
        const val CROSS_FILE_CONTEXT = "crossFileContext"

        /** Name of the built-in dependency context part (resolved imports and their tests). */
        const val DEPENDENCY_CONTEXT = "dependencyContext"

        /** Name of the part that joins every non-T0 enricher block. */
        const val ENRICHER_CONTEXT = "enricherContext"

        /** The [ContextTier] of each known T2 part; any other part is [ContextTier.CALLERS_RELATED]. */
        private val TIERS: Map<String, ContextTier> = mapOf(
            DELETED_CODE to ContextTier.DELETED_CODE,
            DiffAnnotationsEnricher.MODULE_ID to ContextTier.DELETED_CODE,
            FullFileContextEnricher.MODULE_ID to ContextTier.ENCLOSING_FUNCTION,
            SymbolContextEnricher.MODULE_ID to ContextTier.SYMBOL_DEFINITIONS,
            SymbolDefinitionsEnricher.MODULE_ID to ContextTier.SYMBOL_DEFINITIONS,
            CROSS_FILE_CONTEXT to ContextTier.SYMBOL_DEFINITIONS,
            DEPENDENCY_CONTEXT to ContextTier.SYMBOL_DEFINITIONS,
            RelatedFileContextEnricher.MODULE_ID to ContextTier.RELATED_TESTS,
            ConventionContextEnricher.MODULE_ID to ContextTier.CONVENTIONS_DOCS,
            DesignDocEnricher.MODULE_ID to ContextTier.CONVENTIONS_DOCS,
        )

        /** [name]'s place in the `ai.context.budget` fill order. */
        fun tierOf(name: String): ContextTier = TIERS[name] ?: ContextTier.CALLERS_RELATED

        /**
         * Built-in extras that are T2 context, with their drop priority (highest kept first). `priorFindings`
         * is not here: the do-not-repeat list is T0, so a large diff never drops it.
         */
        val T2_PRIORITIES: Map<String, Int> = mapOf(
            ENRICHER_CONTEXT to 200,
            CROSS_FILE_CONTEXT to 110,
            DEPENDENCY_CONTEXT to 100,
        )
    }
}

/**
 * [AssembledExtras.plan]: the instructions that fit, the diff's token share, the dropped context, or why the rule
 * skips; [contextTokens] is the planned prompt besides the diff (T0 and kept T2); [contextBlocks] the kept MR context;
 * [coverage] how the request used `ai.context.budget` (null when T0 alone was over it).
 */
@Serializable
internal data class TieredExtras(
    val combinedExtra: String?,
    val diffTokens: Int,
    val contextDropped: List<String>,
    val skipReason: String?,
    val contextTokens: Int = 0,
    val contextBlocks: List<AiContextBlock> = emptyList(),
    val coverage: ContextCoverage? = null,
)
