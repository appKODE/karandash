package io.karandash.plugins.ai.dispatch

import io.karandash.plugins.ai.core.AiPluginConfig
import kotlinx.serialization.Serializable

/**
 * `--dump-dir` `ai/budget-plan.json`: the per-MR budget plan [ChunkBudget.ledger] made before any rule called the model.
 *
 * @property runBudgetBefore The run budget as configured; [runBudget] after the ceiling clamp and every grant.
 * @property ceiling The tokens `cost-budget` caps the run at; null without it (every need funded).
 * @property notes Every clamp and warning the plan emitted, as logged.
 * @property refusals Calls the budget gate refused while the rules ran; the plan is dumped again when there are any.
 * @property sampleTokens Rule ID to the tokens its extra samples spent on their own budget, outside [runBudget].
 * @property shortfall The plan line naming each underfunded rule's share and the knob to raise; null when every rule
 *   is funded in full or `scale-budget` is off.
 */
@Serializable
internal data class BudgetPlanDump(
    val scaleBudget: Boolean,
    val runBudgetBefore: Int,
    val runBudget: Int,
    val ceiling: Int?,
    val ceilingSource: String,
    val rules: List<RuleSizing>,
    val notes: List<String>,
    val refusals: List<BudgetRefusal> = emptyList(),
    val sampleTokens: Map<String, Int> = emptyMap(),
    val shortfall: String? = null,
) {
    /** Rule ID to the tokens granted on top of its share; only rules granted any. */
    val granted: Map<String, Int> get() = rules.filter { it.grant > 0 }.associate { it.rule to it.grant }
}

/**
 * One rule's sizing in tokens unless named chars: [context] C, [share] S, [t0] T0, [reserve] R, [call] one call's size,
 * [room] what T0 and R leave the diff and context, [chunkChars] the diff one call carries, [calls] how many, [need]
 * all of them, [grant] what the plan added to [share]. [sources] names each input's origin (`derived` | `config:<key>`).
 */
@Serializable
internal data class RuleSizing(
    val rule: String,
    val model: String,
    val chunked: Boolean,
    val context: Int,
    val share: Int,
    val t0: Int,
    val reserve: Int,
    val diffTokens: Int,
    val call: Int = 0,
    val room: Int = 0,
    val chunkChars: Int = 0,
    val calls: Int = 1,
    val need: Long = 0,
    val priority: Int = 0,
    val grant: Int = 0,
    val skipReason: String? = null,
    val sources: Map<String, String> = emptyMap(),
)

/** One call the budget gate refused: [need] tokens [rule] asked for, the [knob] that raises the budget, and why. */
@Serializable
internal data class BudgetRefusal(
    val rule: String,
    val need: Int,
    val knob: String,
    val reason: String,
)

/** The config key that raises the run budget: `ai.cost-budget` when set, else `ai.large-mr.max-run-tokens`. */
internal fun budgetKnob(cfg: AiPluginConfig): String = if (cfg.costBudget != null) "ai.cost-budget" else "ai.large-mr.max-run-tokens"
