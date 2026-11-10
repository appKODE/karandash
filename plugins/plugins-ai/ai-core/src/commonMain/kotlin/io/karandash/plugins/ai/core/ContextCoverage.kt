package io.karandash.plugins.ai.core

import kotlinx.serialization.Serializable

/**
 * How one AI request used `ai.context.budget`: the [budget] in estimated tokens, the tokens [used] by T0, the diff
 * and the kept context, the diff wanted and given, the diff hunks that did not fit, and kept/cut/dropped tokens
 * per context tier. Logged at INFO, written to the `--dump-dir` plan and carried in the diagnostic JSON.
 */
@Serializable
data class ContextCoverage(
    val budget: Int,
    val used: Int,
    val usedPercent: Int,
    val diffTokensWanted: Int,
    val diffTokens: Int,
    val diffHunksCut: Int = 0,
    val tiers: List<TierUsage> = emptyList(),
) {
    /** One line for the log: `budget B tokens, used U (P%), diff D of W tokens, H hunks cut; tier kept/cut/dropped ...`. */
    fun describe(): String {
        val tierText = tiers.joinToString("; ") { "${it.tier.name.lowercase()} kept ${it.kept}, cut ${it.cut}, dropped ${it.dropped}" }
        val head = "budget $budget tokens, used $used ($usedPercent%), " +
            "diff $diffTokens of $diffTokensWanted tokens, $diffHunksCut hunks cut"
        return if (tierText.isEmpty()) head else "$head; $tierText"
    }

    companion object {
        private const val PERCENT = 100

        /** The coverage of [plan] under [budget], [diffHunksCut] counted by the caller from the diff it cut. */
        fun of(
            plan: TierPlan.Fits,
            budget: Int,
            diffTokensWanted: Int,
            diffHunksCut: Int = 0,
        ): ContextCoverage {
            val used = PromptTierPlanner.tokensOf(plan.t0) + PromptTierPlanner.tokensOf(plan.kept) + plan.diffTokens
            return ContextCoverage(
                budget = budget,
                used = used,
                usedPercent = if (budget > 0) (used.toLong() * PERCENT / budget).toInt() else 0,
                diffTokensWanted = diffTokensWanted,
                diffTokens = plan.diffTokens,
                diffHunksCut = diffHunksCut,
                tiers = plan.tiers,
            )
        }
    }
}
