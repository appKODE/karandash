package io.karandash.plugins.ai.core

import io.karandash.api.ai.AiTokenBudget
import io.karandash.plugins.ai.core.diagnostics.ContextQualityDiagnostics
import io.karandash.plugins.ai.core.rules.BudgetAllocation
import io.karandash.plugins.ai.statistics.StatisticsStore
import kotlinx.atomicfu.atomic
import kotlinx.serialization.Serializable

/**
 * Tracks token consumption across all AI calls in a single validation run.
 * Prevents runaway costs from large MRs or repeated invocations.
 *
 * The one budget owner: [perRule] splits the run budget across rules and [splitAcrossFiles] splits a rule's diff
 * budget across its files.
 *
 * Thread-safe: all reads and writes of consumed tokens use [atomic].
 */
class TokenBudget(
    maxTokens: Int
) : AiTokenBudget {
    private val consumed = atomic(0)
    private val limit = atomic(maxTokens)

    /** The tokens this budget allows, [grow]n included. */
    val maxTokens: Int get() = limit.value

    /** The budget as configured, before any [grow]: the most one chunk's call of a chunked rule may take. */
    val callTokens: Int = maxTokens

    /** Raises the limit by [tokens]; a chunked rule's share grows per chunk (`ai.large-mr.scale-budget`). */
    fun grow(tokens: Int) {
        limit.addAndGet(tokens)
    }

    override fun canSpend(estimatedTokens: Int): Boolean = consumed.value + estimatedTokens <= maxTokens

    /**
     * Atomically checks whether [amount] tokens can be spent and deducts them if so.
     *
     * Uses a CAS loop to prevent TOCTOU races between [canSpend] and [recordSpend]
     * callers running concurrently on parallel analyzer coroutines.
     *
     * @return true if the budget had enough tokens and they were deducted; false otherwise.
     */
    fun trySpend(amount: Int): Boolean {
        while (true) {
            val current = consumed.value
            if (current + amount > maxTokens) return false
            if (consumed.compareAndSet(current, current + amount)) return true
        }
    }

    override fun recordSpend(tokens: Int) {
        consumed.addAndGet(tokens)
    }

    override fun isExhausted(): Boolean = consumed.value >= maxTokens

    override fun remaining(): Int = (maxTokens - consumed.value).coerceAtLeast(0)

    fun totalConsumed(): Int = consumed.value

    /**
     * Per-file split of a diff character budget.
     *
     * @property budget Characters this file's diff may take.
     */
    @Serializable
    data class FileAllocation(
        val path: String,
        val score: Double,
        val budget: Int,
    )

    companion object {
        /** Minimum number of characters guaranteed per file under normal operation. */
        const val MIN_FILE_BUDGET: Int = 200

        /** Smallest allocation used when underflow forces an equal fallback. */
        const val UNDERFLOW_FLOOR: Int = 50

        private const val DEFAULT_MIN_SAMPLES = 10

        /**
         * Splits [totalBudget] tokens across [rules]: evenly without history, otherwise a greedy knapsack.
         *
         * With at least [minSamples] runs in [statisticsStore], each rule's value is its historical hit rate times its
         * [estimatedCosts] entry (its weight); rules are funded best value-per-token first, the top rule takes what is
         * left, and a rule the budget could not fund gets a 500-token floor.
         *
         * @return rule ID to allocated tokens.
         */
        fun perRule(
            rules: Set<String>,
            totalBudget: Int,
            estimatedCosts: Map<String, Int>,
            statisticsStore: StatisticsStore? = null,
            minSamples: Int = DEFAULT_MIN_SAMPLES,
        ): Map<String, Int> {
            if (rules.isEmpty()) return emptyMap()
            val hitRates = loadHitRates(statisticsStore, rules, minSamples)
            if (hitRates.isEmpty()) return rules.associateWith { totalBudget / rules.size }
            return knapsackAllocation(rules, totalBudget, estimatedCosts, hitRates)
        }

        /**
         * Divides a total character budget across files ranked by [FilePriorityScorer]: by score
         * ([BudgetAllocation.PROPORTIONAL]) or flat ([BudgetAllocation.EQUAL]).
         *
         * Both enforce a [MIN_FILE_BUDGET] floor and a [maxPerFile] cap; surplus left after clamping goes greedily to
         * the highest-priority file with headroom. No file gets more than [needOf] (its diff size), so a file that
         * needs less gives the rest to the others, and files that fit [totalBudget] together all go in whole. Underflow (`totalBudget < MIN_FILE_BUDGET * files`) falls back to an
         * even split, at least [UNDERFLOW_FLOOR], and emits one [ContextQualityDiagnostics.budgetUnderflow] warning.
         *
         * @param scoredFiles Output of [FilePriorityScorer.score], sorted descending.
         */
        fun splitAcrossFiles(
            scoredFiles: List<FilePriorityScorer.ScoredFile>,
            totalBudget: Int,
            maxPerFile: Int,
            allocation: BudgetAllocation,
            diagnostics: ContextQualityDiagnostics? = null,
            needOf: (FilePriorityScorer.ScoredFile) -> Int = { Int.MAX_VALUE },
        ): List<FileAllocation> {
            if (scoredFiles.isEmpty()) return emptyList()
            require(totalBudget >= 0) { "totalBudget must be >= 0, got $totalBudget" }
            require(maxPerFile >= 0) { "maxPerFile must be >= 0, got $maxPerFile" }

            if (totalBudget < MIN_FILE_BUDGET * scoredFiles.size) {
                diagnostics?.budgetUnderflow(totalBudget = totalBudget, fileCount = scoredFiles.size, minFloor = MIN_FILE_BUDGET)
                val perFile = (totalBudget / scoredFiles.size).coerceAtLeast(UNDERFLOW_FLOOR)
                return scoredFiles.map { FileAllocation(it.change.path, it.score, perFile) }
            }

            val raw = when (allocation) {
                BudgetAllocation.PROPORTIONAL -> allocateProportional(scoredFiles, totalBudget)
                BudgetAllocation.EQUAL -> allocateEqual(scoredFiles, totalBudget)
            }
            val caps = scoredFiles.map { minOf(maxPerFile, needOf(it)) }
            val clamped = raw.zip(caps) { budget, cap -> minOf(budget.coerceAtLeast(MIN_FILE_BUDGET), cap) }
            val redistributed = redistributeSurplus(clamped, totalBudget, caps)
            return scoredFiles.zip(redistributed) { sf, budget -> FileAllocation(sf.change.path, sf.score, budget) }
        }
    }
}

private const val DEFAULT_ESTIMATED_COST = 1500
private const val DEFAULT_HIT_RATE = 0.5
private const val MIN_RULE_ALLOCATION = 500
private const val HISTORY_WINDOW = 100

/** A rule in the knapsack: [weight] is its estimated token cost, [value] its hit rate times that cost. */
@Serializable
private data class RuleItem(
    val ruleId: String,
    val weight: Int,
    val value: Double,
)

private fun knapsackAllocation(
    rules: Set<String>,
    totalBudget: Int,
    estimatedCosts: Map<String, Int>,
    hitRates: Map<String, Double>,
): Map<String, Int> {
    val items = rules
        .map { ruleId ->
            val weight = estimatedCosts[ruleId] ?: DEFAULT_ESTIMATED_COST
            RuleItem(ruleId, weight, (hitRates[ruleId] ?: DEFAULT_HIT_RATE) * weight)
        }.sortedByDescending { it.value / it.weight.coerceAtLeast(1) }

    val allocation = mutableMapOf<String, Int>()
    var remaining = totalBudget
    for (item in items) {
        if (remaining <= 0) break
        val allocated = item.weight.coerceAtMost(remaining)
        allocation[item.ruleId] = allocated
        remaining -= allocated
    }
    if (remaining > 0 && allocation.isNotEmpty()) {
        val topRule = items.first().ruleId
        allocation[topRule] = (allocation[topRule] ?: 0) + remaining
    }
    for (ruleId in rules) {
        if (ruleId !in allocation) allocation[ruleId] = MIN_RULE_ALLOCATION
    }
    return allocation
}

private fun loadHitRates(
    store: StatisticsStore?,
    rules: Set<String>,
    minSamples: Int,
): Map<String, Double> {
    if (store == null) return emptyMap()
    val records = store.recentRecords(HISTORY_WINDOW)
    if (records.size < minSamples) return emptyMap()
    return rules.associateWith { ruleId ->
        records.count { record -> (record.findingsByRule[ruleId] ?: 0) > 0 }.toDouble() / records.size
    }
}

private fun allocateProportional(
    files: List<FilePriorityScorer.ScoredFile>,
    total: Int,
): List<Int> {
    val sumScore = files.sumOf { it.score }
    if (sumScore <= 0.0) return allocateEqual(files, total)
    return files.map { sf -> (sf.score / sumScore * total).toInt() }
}

private fun allocateEqual(
    files: List<FilePriorityScorer.ScoredFile>,
    total: Int,
): List<Int> = List(files.size) { total / files.size }

/** Distribute leftover chars to files ranked by score until cap is hit. */
private fun redistributeSurplus(
    clamped: List<Int>,
    totalBudget: Int,
    caps: List<Int>,
): List<Int> {
    val out = clamped.toMutableList()
    var surplus = totalBudget - out.sum()
    if (surplus <= 0) return out
    for (i in out.indices) {
        if (surplus <= 0) break
        val headroom = caps[i] - out[i]
        if (headroom > 0) {
            val bonus = minOf(surplus, headroom)
            out[i] += bonus
            surplus -= bonus
        }
    }
    return out
}
