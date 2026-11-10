package io.karandash.plugins.ai.core.config

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.platform.readText
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString

/**
 * Violation budget with decay ratchet: gradual enforcement for legacy codebases.
 *
 * Each rule is assigned a baseline allowance of pre-existing violations. The allowance
 * decreases automatically at a configurable rate so teams are nudged toward compliance
 * over time without a hard cutover.
 */
object ViolationBudgetEvaluator {
    private const val MONTHS_PER_YEAR = 12
    private const val PERCENT_DIVISOR = 100.0

    /**
     * `ai.violationBudgets`, like the per-file [applyBaseline] but per rule: when a rule's ERROR/WARNING findings
     * number no more than its current allowance they are known debt and become INFO (metadata `violationBudgetFrom`);
     * a rule over its allowance keeps its findings. Findings of rules without a budget pass through unchanged.
     *
     * @param findings All findings to evaluate.
     * @param budgets Per-rule budget configurations; a budget matches every rule ID that contains its [ViolationBudgetConfig.rule].
     * @param currentDateIso The current date in ISO-8601 format (YYYY-MM-DD), used to compute decay.
     * @return A [BudgetResult] with every finding (within-budget ones as INFO) and per-rule totals summed once.
     */
    fun evaluate(
        findings: List<Finding>,
        budgets: List<ViolationBudgetConfig>,
        currentDateIso: String
    ): BudgetResult {
        if (budgets.isEmpty()) return BudgetResult(findings, withinBudget = true, 0, 0)

        val counts = findings
            .filter { it.severity != Severity.INFO }
            .groupingBy { it.rule }
            .eachCount()
        val allowances = counts.keys
            .mapNotNull { rule -> budgets.find { rule.contains(it.rule) }?.let { rule to computeAllowance(it, currentDateIso) } }
            .toMap()
        val within = allowances.filter { (rule, allowance) -> counts.getValue(rule) <= allowance }.keys
        val result = findings.map { finding ->
            if (finding.severity == Severity.INFO || finding.rule !in within) return@map finding
            finding.copy(severity = Severity.INFO, metadata = finding.metadata + (BUDGET_METADATA to finding.severity.name))
        }
        val totalAllowance = allowances.values.sum()
        val totalViolations = allowances.keys.sumOf { counts.getValue(it) }
        return BudgetResult(
            findings = result,
            withinBudget = within.size == allowances.size,
            totalAllowance = totalAllowance,
            totalViolations = totalViolations,
            overBudget = (allowances.keys - within).associateWith { counts.getValue(it) to allowances.getValue(it) },
        )
    }

    /**
     * Computes the current allowance for a budget, applying decay periods elapsed since the baseline date.
     *
     * @param budget The budget configuration.
     * @param currentDateIso The current date in ISO-8601 format.
     * @return The current allowance, never less than [ViolationBudgetConfig.minimum].
     */
    internal fun computeAllowance(
        budget: ViolationBudgetConfig,
        currentDateIso: String
    ): Int {
        val baseline = budget.baseline
        if (budget.decayRatePercent <= 0) return baseline

        val monthsElapsed = estimateMonthsBetween(budget.baselineDate ?: currentDateIso, currentDateIso)
        val periodsElapsed = monthsElapsed / budget.decayPeriodMonths
        val retentionFactor = 1.0 - budget.decayRatePercent / PERCENT_DIVISOR
        var decayed = baseline.toDouble()
        repeat(periodsElapsed) { decayed *= retentionFactor }
        return decayed.toInt().coerceAtLeast(budget.minimum)
    }

    /**
     * `ai.violationBudget`: per file, when the ERROR/WARNING findings number no more than the file's baseline
     * plus [ViolationBaselineConfig.incrementPerMonth] for each month since `baselineDate`, they are known debt
     * and become INFO; files over that keep their findings and are listed in [BaselineResult.overBudget].
     * Findings without a file location are left alone.
     */
    fun applyBaseline(
        findings: List<Finding>,
        config: ViolationBaselineConfig,
        baseline: Map<String, Int>,
        currentDateIso: String,
    ): BaselineResult {
        val months = config.baselineDate?.let { estimateMonthsBetween(it, currentDateIso) }?.coerceAtLeast(0) ?: 0
        val counts = findings.mapNotNull(::budgetedPath).groupingBy { it }.eachCount()
        val allowances = counts.mapValues { (path, _) -> (baseline[path] ?: 0) + config.incrementPerMonth * months }
        val within = counts.filter { (path, count) -> count <= allowances.getValue(path) }.keys
        val result = findings.map { finding ->
            if (budgetedPath(finding) !in within) return@map finding
            finding.copy(severity = Severity.INFO, metadata = finding.metadata + (BUDGET_METADATA to finding.severity.name))
        }
        val over = (counts.keys - within).associateWith { counts.getValue(it) to allowances.getValue(it) }
        return BaselineResult(result, within.size, over)
    }

    private fun budgetedPath(finding: Finding): String? =
        (finding.location as? Location.FileRef)?.path?.takeIf { finding.severity != Severity.INFO }

    /** Reads the baseline file (JSON object of path to violation count); a missing file is an empty baseline. */
    fun loadBaseline(path: String): Result<Map<String, Int>> {
        val file = Path(path)
        if (!SystemFileSystem.exists(file)) return Result.success(emptyMap())
        return try {
            val text = file.readText()
            Result.success(KarandashJson.Default.decodeFromString<Map<String, Int>>(text))
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: SerializationException) {
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }

    private const val BUDGET_METADATA = "violationBudgetFrom"

    private fun estimateMonthsBetween(
        from: String,
        to: String
    ): Int {
        val fromParts = from.split("-").mapNotNull { it.toIntOrNull() }
        val toParts = to.split("-").mapNotNull { it.toIntOrNull() }
        if (fromParts.size < 2 || toParts.size < 2) return 0
        return (toParts[0] - fromParts[0]) * MONTHS_PER_YEAR + (toParts[1] - fromParts[1])
    }
}

/** Per-rule violation budget with an optional decay schedule. */
@Serializable
data class ViolationBudgetConfig(
    /** Substring matched against the finding's rule ID. */
    val rule: String,
    /** Maximum number of violations allowed at the baseline date. */
    val baseline: Int = 0,
    /** ISO-8601 date when the baseline was recorded. Defaults to the current date when absent. */
    val baselineDate: String? = null,
    /** Percentage of the remaining allowance removed each decay period. */
    val decayRatePercent: Int = 10,
    /** Number of months between each decay step. */
    val decayPeriodMonths: Int = 1,
    /** Floor value - the allowance will never decay below this. */
    val minimum: Int = 0,
)

/** The outcome of evaluating findings against violation budgets. */
@Serializable
data class BudgetResult(
    /** Every finding; those of a rule within its budget are INFO. */
    val findings: List<Finding>,
    /** True when every budgeted rule is within its allowance. */
    val withinBudget: Boolean,
    /** Sum of the current allowances of the budgeted rules that have findings, once per rule. */
    val totalAllowance: Int,
    /** Sum of the ERROR/WARNING findings of those rules. */
    val totalViolations: Int,
    /** Rule ID to (violations, allowance) for each rule over its budget. */
    val overBudget: Map<String, Pair<Int, Int>> = emptyMap(),
)

/** `ai.violationBudget`: a per-file baseline of tolerated ERROR/WARNING findings, read from [baselineFile]. */
@Serializable
data class ViolationBaselineConfig(
    val enabled: Boolean = true,
    val baselineFile: String = ".karandash/violation-baseline.json",
    /** Extra violations tolerated per file for each month since [baselineDate]. */
    val incrementPerMonth: Int = 0,
    /** ISO-8601 date the baseline was taken; without it no monthly increment accrues. */
    val baselineDate: String? = null,
)

/** Outcome of [ViolationBudgetEvaluator.applyBaseline]; [overBudget] maps a path to (violations, allowance). */
@Serializable
data class BaselineResult(
    val findings: List<Finding>,
    val withinBudgetFiles: Int,
    val overBudget: Map<String, Pair<Int, Int>>,
)
