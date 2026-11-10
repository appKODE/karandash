package io.karandash.plugins.ai.core.architecture

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleMutationTesterTest {
    @Test
    fun generateMutationsReturnsNonEmptyList() {
        val mutations = RuleMutationTester.generateMutations()
        assertTrue(mutations.isNotEmpty())
    }

    @Test
    fun allMutationsHaveTargetRules() {
        val mutations = RuleMutationTester.generateMutations()
        for (mutation in mutations) {
            assertTrue(mutation.targetRule.isNotEmpty(), "Mutation '${mutation.name}' has empty targetRule")
        }
    }

    @Test
    fun runTestsWithCatchingValidatorGivesFullScore() {
        val result = RuleMutationTester.runTests { mr ->
            // Return a finding for every target rule across all mutations
            RuleMutationTester.generateMutations().map { mutation ->
                Finding(
                    severity = Severity.ERROR,
                    message = "violation",
                    rule = mutation.targetRule,
                )
            }
        }
        assertEquals(result.totalMutations, result.caughtCount)
        assertEquals(1.0, result.mutationScore)
        assertEquals(0, result.escapedCount)
    }

    @Test
    fun camelCaseRuleIdCatchesKebabCaseTarget() {
        val result = RuleMutationTester.runTests { mr ->
            if (mr.title == "fixed some stuff") {
                listOf(Finding(severity = Severity.ERROR, message = "bad title", rule = "titleFormat"))
            } else {
                emptyList()
            }
        }
        val outcome = result.outcomes.single { it.mutation.name == "bad-title-format" }
        assertTrue(outcome.caught)
        assertEquals(1, result.caughtCount)
    }

    @Test
    fun runTestsWithEmptyValidatorGivesZeroScore() {
        val result = RuleMutationTester.runTests { _ -> emptyList() }
        assertEquals(0, result.caughtCount)
        assertEquals(0.0, result.mutationScore)
        assertEquals(result.totalMutations, result.escapedCount)
    }

    @Test
    fun formatReportShowsCaughtAndEscaped() {
        val result = RuleMutationTester.runTests { _ -> emptyList() }
        val report = RuleMutationTester.formatReport(result)
        assertTrue(report.contains("Mutation Testing"))
        assertTrue(report.contains("ESCAPED"))
        assertFalse(report.contains("CAUGHT"))
    }

    @Test
    fun formatReportShowsCaughtWhenAllCaught() {
        val result = RuleMutationTester.runTests { mr ->
            RuleMutationTester.generateMutations().map { mutation ->
                Finding(severity = Severity.ERROR, message = "violation", rule = mutation.targetRule)
            }
        }
        val report = RuleMutationTester.formatReport(result)
        assertTrue(report.contains("CAUGHT"))
        assertFalse(report.contains("ESCAPED"))
    }

    @Test
    fun outcomesCountMatchesTotalMutations() {
        val result = RuleMutationTester.runTests { _ -> emptyList() }
        assertEquals(result.totalMutations, result.outcomes.size)
    }
}
