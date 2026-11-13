package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistry
import io.karandash.api.context.EnrichmentContext
import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.core.AiCostEstimator
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.ChunkResult
import io.karandash.plugins.ai.core.ContextBudget
import io.karandash.plugins.ai.core.DiffChunk
import io.karandash.plugins.ai.core.DiffChunker
import io.karandash.plugins.ai.core.DiffContextBuilder
import io.karandash.plugins.ai.core.FilePriorityScorer
import io.karandash.plugins.ai.core.PromptTierPlanner
import io.karandash.plugins.ai.core.PromptTokenCalibration
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.TokenUnit
import io.karandash.plugins.ai.core.config.AiCostBudgetResolver
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.PerRuleContextResolver
import io.karandash.plugins.ai.init.AiRuleInitializer
import io.karandash.plugins.ai.plannedSystemPrompt
import io.karandash.plugins.ai.rules.CustomAiRule
import io.karandash.plugins.ai.rules.CustomAiRuleExecutor
import io.karandash.plugins.ai.rules.PreMergeChecksExecutor

/**
 * `ai.large-mr.scale-budget`: one budget plan per MR, made before any rule calls the model, so the split never depends
 * on which rule starts first.
 *
 * A chunked rule sizes every call once ([callSize]): `large-mr.max-call-tokens` ([chunkCallCap]), at most the model's context,
 * at least its prompt, the output reserve and [MIN_DIFF_TOKENS] of diff. It wants that call once per chunk the diff
 * takes; a grown share pays for more calls, never a bigger one. The checklist, MR-level analyzers
 * and rules with chunking off make one call: prompt, reserve and the MR's diff, at most the model's context. Rules are
 * funded in priority order, highest first: `ai.<rule>.priority` or a custom rule's `priority` when set, otherwise
 * review-comments 2, the whole-MR rules 1, the rest 0; ties go by rule ID. With `cost-budget` its token cap for the
 * MR's branch and the tokens `max-cost-per-run-usd` buys at the model's input price bound the run (a run budget above
 * them is cut to them); without it every rule is funded in full and one INFO line names the run total. The run budget
 * grows by every grant, so the ceiling holds inside the running review too.
 */
internal class ChunkBudget(
    private val registry: ModuleRegistry,
) {
    /** What the plan funds: the rules' own shares, the run budget they also draw on, and the MR they review. */
    class Input(
        val shares: Map<String, TokenBudget>,
        val run: TokenBudget,
        val mr: MergeRequest,
        val pluginCfg: AiPluginConfig,
        val aiRuleConfig: AiRuleConfig,
        val model: String,
        val customRules: List<CustomAiRule> = emptyList(),
        val perRuleModels: Map<String, String> = emptyMap(),
        val calibration: PromptTokenCalibration = PromptTokenCalibration(),
    )

    /** @return Rule ID to the tokens granted on top of its share; empty when off. */
    fun plan(
        input: Input,
        logger: Logger,
    ): Map<String, Int> = ledger(input, logger).granted

    /**
     * Sizes every rule and, with `scale-budget` on, grants each its need in priority order; logs one INFO line per
     * rule and returns the whole plan, every input's source and every clamp warned, for `ai/budget-plan.json`.
     */
    fun ledger(
        planned: Input,
        logger: Logger,
    ): BudgetPlanDump {
        val input = sentOnly(planned, logger)
        val notes = mutableListOf<String>()
        val warn: (String) -> Unit = {
            logger.warn(it)
            notes += it
        }
        val runBefore = input.run.maxTokens
        val sized = input.shares.mapValues { (rule, share) -> withPriority(sizing(rule, share, input, warn), input) }
        val scale = input.pluginCfg.largeMr.scaleBudget
        val ceiling = if (scale) ceilingOf(input, warn) ?: safetyCap(input, sized.values, warn) else null
        var left = ceiling?.let { it.toLong() - input.run.maxTokens }
            ?: sized.values.sumOf { (it.need - it.share).coerceAtLeast(0) }
        val order = compareByDescending<RuleSizing> { it.priority }.thenBy { it.rule }
        val rules = sized.values.sortedWith(order).map { rule ->
            val grant = if (scale) minOf(rule.need - rule.share, left).toInt().coerceAtLeast(0) else 0
            if (grant > 0) {
                input.shares.getValue(rule.rule).grow(grant)
                input.run.grow(grant)
                left -= grant
            }
            rule.copy(grant = grant).also {
                logger.info("[ai] rule=${it.rule} calls=${it.calls} chunkChars=${it.chunkChars} call=${it.call} grant=$grant")
            }
        }
        var shortfall: String? = null
        if (scale) {
            val wanted = runBefore + sized.values.sumOf { (it.need - it.share).coerceAtLeast(0) }
            val knob = budgetKnob(input.pluginCfg)
            val summary = planSummary(rules, input.run.maxTokens, wanted, knob)
            if (rules.any { it.need > it.share + it.grant }) {
                warn(summary)
                shortfall = summary.removePrefix("[ai] ").replaceFirstChar { it.uppercase() }
            } else {
                logger.info(summary)
            }
        }
        return BudgetPlanDump(
            scaleBudget = scale,
            runBudgetBefore = runBefore,
            runBudget = input.run.maxTokens,
            ceiling = ceiling,
            ceilingSource = when {
                input.pluginCfg.costBudget != null -> "config:ai.cost-budget"
                ceiling != null -> "ai.large-mr.max-run-tokens"
                else -> "derived: sum of needs"
            },
            rules = rules,
            notes = notes,
            shortfall = shortfall,
        )
    }

    /**
     * [input] with its MR cut to the files a rule sends: the exclude policy (svg, lockfiles) and the file-level diff
     * filters (binaries, pure renames) run once here, so the plan never sizes a diff no call carries.
     */
    private fun sentOnly(
        input: Input,
        logger: Logger,
    ): Input {
        val pre = AiMrPreprocessor(logger)
        val mr = pre.applyDiffPreprocessorFileFilters(pre.applyExcludePolicy(input.mr, input.aiRuleConfig), input.aiRuleConfig)
        if (mr === input.mr) return input
        return Input(
            input.shares,
            input.run,
            mr,
            input.pluginCfg,
            input.aiRuleConfig,
            input.model,
            input.customRules,
            input.perRuleModels,
            input.calibration,
        )
    }

    /** Whether [ruleId] is one call over the whole MR, never split into chunks. */
    fun wholeMr(ruleId: String): Boolean = ruleId == AiRuleIds.CHECKS || registry.findAiAnalyzer(ruleId)?.mrLevel == true

    private fun defaultPriority(ruleId: String): Int =
        when {
            ruleId == AiRuleIds.REVIEW_COMMENTS -> 2
            wholeMr(ruleId) -> 1
            else -> 0
        }

    private fun withPriority(
        sizing: RuleSizing,
        input: Input,
    ): RuleSizing {
        val known = input.shares.keys.toList()
        val custom = input.customRules.firstOrNull { it.id == sizing.rule }?.priority
        val configured = input.pluginCfg.priorities.entries
            .firstOrNull { AiRuleInitializer.toRuleId(it.key, known) == sizing.rule }
        val (priority, source) = when {
            custom != null -> custom to "config:ai.custom-rules.priority"
            configured != null -> configured.value to "config:ai.${configured.key}.priority"
            else -> defaultPriority(sizing.rule) to DERIVED
        }
        return sizing.copy(priority = priority, sources = sizing.sources + ("priority" to source))
    }

    /** The tokens [ruleId] wants for this MR: one call per chunk for a chunked rule, its one call otherwise. */
    internal fun need(
        ruleId: String,
        share: TokenBudget,
        input: Input,
        logger: Logger,
    ): Long = sizing(ruleId, share, input) { logger.warn(it) }.need

    /** How [ruleId] is sized for this MR (need, call, chunk), each input named with its source; clamps go to [warn]. */
    private fun sizing(
        ruleId: String,
        share: TokenBudget,
        input: Input,
        warn: (String) -> Unit,
    ): RuleSizing {
        val cfg = input.pluginCfg
        val reserve = cfg.budget.outputReserveTokens
        val t0 = t0Of(ruleId, input)
        val context = contextOf(ruleId, input)
        val diffTokens = minOf(
            input.mr.changes.sumOf { TokenUnit.tokensOf(it.diffContent.orEmpty()).toLong() },
            TokenUnit.charsToTokens(input.aiRuleConfig.maxDiffCharsTotal).toLong(),
        ).toInt()
        val base = RuleSizing(
            rule = ruleId,
            model = input.perRuleModels[ruleId] ?: input.model,
            chunked = chunked(ruleId, cfg),
            context = context,
            share = share.maxTokens,
            t0 = t0,
            reserve = reserve,
            diffTokens = diffTokens,
            sources = sourcesOf(ruleId, input),
        )
        val overContext = t0.toLong() + reserve + MIN_DIFF_TOKENS > context
        val skip = "T0 $t0 + reserve $reserve + $MIN_DIFF_TOKENS diff tokens over the $context-token context"
        if (!base.chunked) {
            if (overContext) return base.copy(need = share.maxTokens.toLong(), skipReason = skip)
            val call = minOf(t0.toLong() + reserve + diffTokens, context.toLong()).toInt()
            if (call < t0.toLong() + reserve + diffTokens) {
                warn(
                    "[ai] rule=$ruleId: one $context-token call holds ${call - t0 - reserve} of $diffTokens diff tokens; raise ai.context.budget"
                )
            }
            val held = maxOf(call, estimateOf(registry, ruleId, input.customRules)).toLong()
            return base.copy(
                call = call,
                room = call - t0 - reserve,
                chunkChars = input.aiRuleConfig.maxDiffCharsTotal,
                need = held,
            )
        }
        val size = callSize(chunkCallCap(share.callTokens, cfg), context, t0, reserve, cfg.largeMr.chunkContext)
            ?: return base.copy(need = share.maxTokens.toLong(), skipReason = skip)
        val room = minOf(charsOf(size.diffTokens, input.mr), input.aiRuleConfig.maxDiffCharsTotal)
        val configured = cfg.chunking?.chunkSize
        if (configured != null && configured > room) {
            warn(
                "[ai] rule=$ruleId: ai.chunking.chunk-size $configured is over the $room characters one call holds; chunks capped at $room"
            )
        }
        val split = split(input.mr, cfg, input.aiRuleConfig.copy(maxDiffCharsTotal = room)).chunks
        val chunks = split.size
        val bySize = (diffTokens + size.diffTokens - 1) / size.diffTokens
        val wantedCalls = maxOf(chunks, bySize)
        val calls = wantedCalls.coerceIn(1, maxOf(1, cfg.chunking?.maxChunks ?: input.aiRuleConfig.maxChunks))
        if (wantedCalls > calls) {
            warn(
                "[ai] rule=$ruleId: the diff needs $wantedCalls chunks, max-chunks keeps $calls; raise ai.chunking.max-chunks to $wantedCalls"
            )
        }
        val chunkSource = if (configured != null && configured <= room) "config:ai.chunking.chunk-size" else DERIVED
        val held = needOf(
            split.take(calls),
            calls,
            size,
            t0 + reserve,
            if (cfg.largeMr.chunkContext) diffTokens else null,
            estimateOf(registry, ruleId, input.customRules),
        )
        return base.copy(
            call = size.tokens,
            room = size.tokens - t0 - reserve,
            chunkChars = minOf(configured ?: Int.MAX_VALUE, room),
            calls = calls,
            need = held,
            sources = base.sources + ("chunkChars" to chunkSource),
        )
    }

    /**
     * What [calls] chunked calls hold at run time: each its prompt, reserve and chunk, plus with chunk-context
     * ([diffTotal] set, the rule's whole diff) the context the tier planner fills: what the call's room leaves the
     * whole diff when it fits one call, else the context half. At most the call, at least the analyzer's [estimate];
     * a call past the split chunks is a full one.
     */
    private fun needOf(
        chunks: List<DiffChunk>,
        calls: Int,
        size: CallSize,
        fixed: Int,
        diffTotal: Int?,
        estimate: Int,
    ): Long {
        val room = (size.tokens - fixed).coerceAtLeast(0)
        val context = when {
            diffTotal == null -> 0
            diffTotal > room -> size.diffTokens
            else -> room - diffTotal
        }
        val held = chunks.sumOf { chunk ->
            val diff = chunk.changes.sumOf { TokenUnit.tokensOf(it.diffContent.orEmpty()).toLong() }
            maxOf(estimate.toLong(), minOf(size.tokens.toLong(), fixed + diff + context))
        }
        return held + (calls - chunks.size).coerceAtLeast(0).toLong() * size.tokens
    }

    /** Where each sizing input of [ruleId] came from: `config:<key>` when set, `derived` otherwise. */
    private fun sourcesOf(
        ruleId: String,
        input: Input,
    ): Map<String, String> {
        val cfg = input.pluginCfg
        val known = input.shares.keys.toList()
        val shareKey = cfg.analyzerMaxTokens.keys.firstOrNull { AiRuleInitializer.toRuleId(it, known) == ruleId }
        return mapOf(
            "context" to when {
                cfg.contextBudget != null -> "config:ai.context.budget"
                cfg.budget.maxInputTokens != null -> "config:ai.budget.max-input-tokens"
                else -> DERIVED
            },
            "share" to (shareKey?.let { "config:ai.$it.max-tokens" } ?: "derived: ai.max-token-budget split"),
            "reserve" to if (cfg.budget.reserveOutputTokens > 0) "config:ai.budget.reserve-output-tokens" else DERIVED,
            "calls" to if (cfg.chunking?.maxChunks != null) "config:ai.chunking.max-chunks" else DERIVED,
        )
    }

    /** Whether [ruleId] sends the diff in chunks, one call each. */
    fun chunked(
        ruleId: String,
        pluginCfg: AiPluginConfig,
    ): Boolean = !wholeMr(ruleId) && pluginCfg.chunking?.enabled != false

    // limit: plan-time T0 omits the extras (ticket body, prior findings); each call's own plan measures them.
    private fun t0Of(
        ruleId: String,
        input: Input,
    ): Int {
        val prompt = input.customRules.firstOrNull { it.id == ruleId }?.prompt
            ?: plannedSystemPrompt(null, registry.findAiAnalyzer(ruleId))
        val map = MrMap
            .block(
                emptyList(),
                input.mr.changes,
                input.pluginCfg.largeMr.mrMapMaxFiles
            )?.takeIf { chunked(ruleId, input.pluginCfg) }
        return TokenUnit.tokensOf(prompt) + TokenUnit.tokensOf(DiffContextBuilder.mrMetadata(input.mr)) +
            (map?.let { TokenUnit.tokensOf(it.heading + it.content) } ?: 0)
    }

    private fun contextOf(
        ruleId: String,
        input: Input,
    ): Int {
        val model = input.perRuleModels[ruleId] ?: input.model
        return input.calibration.adjust(model, contextBudgetOf(input.pluginCfg, model))
    }

    /** The run ceiling `cost-budget` sets, a run budget above it cut down to it; null without `cost-budget`. */
    private fun ceilingOf(
        input: Input,
        warn: (String) -> Unit,
    ): Int? {
        // limit: USD ceiling priced at input tokens only; output tokens cost more, price them if the USD cap overshoots.
        val cost = input.pluginCfg.costBudget ?: return null
        val tokenCap = AiCostBudgetResolver.resolveTokenBudget(input.mr.sourceBranch, cost)
        val inputPrice = AiCostEstimator
            .fromConfig(input.pluginCfg.pricing, input.pluginCfg.promptCacheTtl)
            .pricingFor(input.model)
            ?.first
        val ceiling = minOf(tokenCap, ContextBudget.costCapTokens(cost.maxCostPerRunUsd, inputPrice) ?: Int.MAX_VALUE)
        if (input.run.maxTokens > ceiling) {
            warn("ai.cost-budget: run budget ${input.run.maxTokens} tokens cut to the $ceiling tokens cost-budget allows for this MR")
            input.run.grow(ceiling - input.run.maxTokens)
        }
        return ceiling
    }

    /**
     * Without `cost-budget`: `large-mr.max-run-tokens` when the needs would grow the run past it (never below the run
     * budget already set), warned once; null when they fit or the cap is 0 (off).
     */
    private fun safetyCap(
        input: Input,
        sized: Collection<RuleSizing>,
        warn: (String) -> Unit,
    ): Int? {
        val cap = input.pluginCfg.largeMr.maxRunTokens
        val wanted = input.run.maxTokens + sized.sumOf { (it.need - it.share).coerceAtLeast(0) }
        if (cap <= 0 || wanted <= cap) return null
        val ceiling = maxOf(cap, input.run.maxTokens)
        warn(
            "ai.large-mr.max-run-tokens: this MR wants $wanted tokens, run capped at $ceiling; " +
                "lowest-priority rules are short, raise max-run-tokens or set cost-budget",
        )
        return ceiling
    }

    /** One chunked call: [tokens] in all, [diffTokens] of them the diff it is sized to carry. */
    class CallSize(
        val tokens: Int,
        val diffTokens: Int,
    )

    companion object {
        /**
         * Most tokens one chunked call plans with: `large-mr.max-call-tokens` under `scale-budget`, which funds every
         * call, so a small [share] never shrinks the call to a sliver of diff under the prompt; [share] at most that when off.
         */
        fun chunkCallCap(
            share: Int,
            pluginCfg: AiPluginConfig,
        ): Int = pluginCfg.largeMr.let { if (it.scaleBudget) it.maxCallTokens else minOf(share, it.maxCallTokens) }

        /** Least diff a call is worth sending: a call is floored to carry it, or skipped when the context cannot. */
        const val MIN_DIFF_TOKENS = 500

        /** Source of a sizing input nothing configured. */
        const val DERIVED = "derived"

        /**
         * [share] ([chunkCallCap]) at most [context], at least [t0] + [reserve] + [MIN_DIFF_TOKENS]; null when
         * that floor is over [context]. The diff gets what T0 and the reserve leave, half of it when [halve]
         * (`ai.large-mr.chunk-context`), as [PromptTierPlanner.diffRoom] splits it.
         */
        fun callSize(
            share: Int,
            context: Int,
            t0: Int,
            reserve: Int,
            halve: Boolean,
        ): CallSize? {
            val floor = t0 + reserve + MIN_DIFF_TOKENS
            if (floor > context) return null
            val call = maxOf(minOf(share, context), floor)
            return CallSize(call, PromptTierPlanner.diffRoom(call - t0 - reserve, halve).coerceAtLeast(1))
        }

        /** [tokens] of [mr]'s diff in characters at the diff's own rate, so a non-ASCII diff gets fewer per token. */
        fun charsOf(
            tokens: Int,
            mr: MergeRequest,
        ): Int {
            val diffs = mr.changes.mapNotNull { it.diffContent }
            val diffTokens = diffs.sumOf { TokenUnit.tokensOf(it) }
            if (diffTokens == 0) return TokenUnit.tokensToChars(tokens)
            return (tokens.toLong() * diffs.sumOf { it.length } / diffTokens).toInt().coerceAtLeast(1)
        }

        /** The tokens the dispatcher holds for a call of [ruleId] at least: the analyzer's (or executor's) estimate. */
        fun estimateOf(
            registry: ModuleRegistry,
            ruleId: String,
            customRules: List<CustomAiRule>,
        ): Int =
            registry.findAiAnalyzer(ruleId)?.estimatedTokens
                ?: CustomAiRuleExecutor.ESTIMATED_TOKENS.takeIf { customRules.any { it.id == ruleId } }
                ?: PreMergeChecksExecutor.ESTIMATED_TOKENS.takeIf { ruleId == AiRuleIds.CHECKS }
                ?: 0

        /** The characters one chunk holds: `ai.chunking.chunk-size` at most the diff cap, which the call's plan sets. */
        fun chunkChars(
            pluginCfg: AiPluginConfig,
            aiRuleConfig: AiRuleConfig,
        ): Int = minOf(pluginCfg.chunking?.chunkSize ?: Int.MAX_VALUE, aiRuleConfig.maxDiffCharsTotal)

        /** `ai.context.budget` for [model]: the setting, else an explicit `max-input-tokens`, else auto under the cost cap. */
        fun contextBudgetOf(
            pluginCfg: AiPluginConfig,
            model: String,
        ): Int {
            val inputPrice = AiCostEstimator.fromConfig(pluginCfg.pricing, pluginCfg.promptCacheTtl).pricingFor(model)?.first
            val costCap = ContextBudget.costCapTokens(pluginCfg.costBudget?.maxCostPerRunUsd ?: 0.0, inputPrice)
            return ContextBudget.resolve(pluginCfg.contextBudget, pluginCfg.budget, model, costCap)
        }

        /**
         * Chunk indices, hottest first: by the top [FilePriorityScorer] score of a file in the chunk (change volume,
         * file type, commit-frequency hotspot, path), so a short budget holds the riskiest chunks. Ties keep chunk order.
         */
        fun hotFirst(
            chunks: List<DiffChunk>,
            mr: MergeRequest,
            ruleId: String,
            aiRuleConfig: AiRuleConfig,
            enrichment: EnrichmentContext?,
        ): List<Int> {
            val weights = PerRuleContextResolver.resolveForRule(ruleId, aiRuleConfig.contextQuality).truncation.priority
            val scores = FilePriorityScorer.score(mr.changes, weights, enrichment).associate { it.change.path to it.score }
            return chunks.indices.sortedByDescending { i -> chunks[i].changes.maxOfOrNull { scores[it.path] ?: 0.0 } ?: 0.0 }
        }

        /** The chunks a chunked rule sends [mr] in: `ai.chunking` (its size capped by the diff cap) when set, the `ai.limits` chunking otherwise. */
        fun split(
            mr: MergeRequest,
            pluginCfg: AiPluginConfig,
            aiRuleConfig: AiRuleConfig,
        ): ChunkResult {
            val chunking = pluginCfg.chunking
            return when {
                chunking?.enabled == false -> ChunkResult(emptyList())

                chunking != null -> chunkChars(pluginCfg, aiRuleConfig).let { size ->
                    DiffChunker(
                        size,
                        chunking.maxChunks,
                        chunking.overlapSize.coerceAtMost(size - 1),
                        aiRuleConfig.chunkOverlapLines
                    ).split(mr)
                }

                else -> DiffChunker(
                    maxCharsPerChunk = aiRuleConfig.maxDiffCharsTotal,
                    maxChunks = aiRuleConfig.maxChunks,
                    overlapLines = aiRuleConfig.chunkOverlapLines,
                ).split(mr)
            }
        }
    }
}
