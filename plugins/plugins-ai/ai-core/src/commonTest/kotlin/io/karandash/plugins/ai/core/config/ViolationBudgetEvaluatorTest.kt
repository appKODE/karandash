package io.karandash.plugins.ai.core.config

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViolationBudgetEvaluatorTest {
    private fun finding(
        rule: String,
        severity: Severity = Severity.WARNING
    ) = Finding(severity = severity, message = "violation", rule = rule)

    @Test
    fun `empty budgets returns all findings unchanged`() {
        val findings = listOf(finding("title-format"), finding("draft"))
        val result = ViolationBudgetEvaluator.evaluate(findings, emptyList(), "2025-01-01")
        assertEquals(findings, result.findings)
        assertTrue(result.withinBudget)
    }

    @Test
    fun `within budget the findings are kept as INFO known debt`() {
        val findings = listOf(finding("title-format"), finding("title-format", Severity.ERROR))
        val budgets = listOf(ViolationBudgetConfig(rule = "title-format", baseline = 5, decayRatePercent = 0))
        val result = ViolationBudgetEvaluator.evaluate(findings, budgets, "2025-01-01")
        assertEquals(listOf(Severity.INFO, Severity.INFO), result.findings.map { it.severity })
        assertEquals(listOf("WARNING", "ERROR"), result.findings.map { it.metadata["violationBudgetFrom"] })
        assertTrue(result.withinBudget)
    }

    @Test
    fun `over budget the findings are kept at their severity`() {
        val findings = (1..6).map { finding("title-format") }
        val budgets = listOf(ViolationBudgetConfig(rule = "title-format", baseline = 3, decayRatePercent = 0))
        val result = ViolationBudgetEvaluator.evaluate(findings, budgets, "2025-01-01")
        assertEquals(findings, result.findings)
        assertFalse(result.withinBudget)
        assertEquals(mapOf("title-format" to (6 to 3)), result.overBudget)
    }

    @Test
    fun `totals count each budgeted rule once`() {
        val findings = listOf(finding("title-format"), finding("title-format"), finding("draft"), finding("other"))
        val budgets = listOf(
            ViolationBudgetConfig(rule = "title-format", baseline = 1, decayRatePercent = 0),
            ViolationBudgetConfig(rule = "draft", baseline = 4, decayRatePercent = 0),
        )
        val result = ViolationBudgetEvaluator.evaluate(findings, budgets, "2025-01-01")
        assertEquals(5, result.totalAllowance)
        assertEquals(3, result.totalViolations)
        assertEquals(listOf(Severity.WARNING, Severity.WARNING, Severity.INFO, Severity.WARNING), result.findings.map { it.severity })
    }

    @Test
    fun `decay reduces allowance over time`() {
        val budget = ViolationBudgetConfig(
            rule = "commit-messages",
            baseline = 100,
            baselineDate = "2025-01-01",
            decayRatePercent = 10,
            decayPeriodMonths = 1,
            minimum = 0,
        )
        val allowanceNow = ViolationBudgetEvaluator.computeAllowance(budget, "2025-01-01")
        val allowanceLater = ViolationBudgetEvaluator.computeAllowance(budget, "2025-06-01")
        assertTrue(allowanceLater < allowanceNow, "Allowance should decrease over time: $allowanceLater < $allowanceNow")
    }

    @Test
    fun `decayRatePercent sets how much allowance each period removes`() {
        fun allowance(rate: Int) =
            ViolationBudgetEvaluator.computeAllowance(
                ViolationBudgetConfig(
                    rule = "r",
                    baseline = 100,
                    baselineDate = "2025-01-01",
                    decayRatePercent = rate,
                    decayPeriodMonths = 1
                ),
                "2025-04-01",
            )

        assertEquals(72, allowance(10))
        assertEquals(51, allowance(20))
        assertEquals(12, allowance(50))
    }

    @Test
    fun `decayPeriodMonths sets how many months make one decay period`() {
        fun allowance(period: Int) =
            ViolationBudgetEvaluator.computeAllowance(
                ViolationBudgetConfig(
                    rule = "r",
                    baseline = 100,
                    baselineDate = "2025-01-01",
                    decayRatePercent = 10,
                    decayPeriodMonths = period
                ),
                "2025-05-01",
            )

        assertEquals(65, allowance(1))
        assertEquals(81, allowance(2))
        assertEquals(100, allowance(6))
    }

    @Test
    fun `zero decay rate returns baseline unchanged`() {
        val budget = ViolationBudgetConfig(
            rule = "draft",
            baseline = 50,
            baselineDate = "2020-01-01",
            decayRatePercent = 0,
        )
        assertEquals(50, ViolationBudgetEvaluator.computeAllowance(budget, "2025-12-01"))
    }

    @Test
    fun `minimum floor is respected`() {
        val budget = ViolationBudgetConfig(
            rule = "branch-naming",
            baseline = 10,
            baselineDate = "2020-01-01",
            decayRatePercent = 50,
            decayPeriodMonths = 1,
            minimum = 2,
        )
        val allowance = ViolationBudgetEvaluator.computeAllowance(budget, "2025-12-01")
        assertTrue(allowance >= 2, "Allowance $allowance must not go below minimum 2")
    }

    @Test
    fun `no baseline date defaults to current date giving full baseline`() {
        val budget = ViolationBudgetConfig(
            rule = "size-limits",
            baseline = 20,
            baselineDate = null,
            decayRatePercent = 10,
            decayPeriodMonths = 1,
        )
        assertEquals(20, ViolationBudgetEvaluator.computeAllowance(budget, "2025-03-01"))
    }
}
