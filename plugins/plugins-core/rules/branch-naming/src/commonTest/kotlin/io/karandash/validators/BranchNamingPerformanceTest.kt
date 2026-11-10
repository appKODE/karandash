package io.karandash.validators

import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BranchNamingPerformanceTest {
    @Test
    fun testBranchNamingValidatorProducesConsistentResultsForValidBranches() {
        val config = BranchNamingConfig(
            pattern = "feature/.*",
            requireSourceMatch = true,
            requireTargetMatch = false
        )
        val validator = BranchNamingValidator(config)

        for (i in 1..100) {
            val mr = createTestMergeRequest(sourceBranch = "feature/ticket-$i")
            val findings = validator.validate(mr)
            assertTrue(
                findings.isEmpty(),
                "Expected no findings for valid branch on iteration $i, got: $findings"
            )
        }
    }

    @Test
    fun testBranchNamingValidatorProducesConsistentResultsForInvalidBranches() {
        val config = BranchNamingConfig(
            pattern = "feature/.*",
            requireSourceMatch = true,
            requireTargetMatch = false
        )
        val validator = BranchNamingValidator(config)

        for (i in 1..100) {
            val mr = createTestMergeRequest(sourceBranch = "random-branch-$i")
            val findings = validator.validate(mr)
            assertEquals(
                1,
                findings.size,
                "Expected exactly one finding for invalid branch on iteration $i"
            )
            assertEquals(
                BranchNamingRuleModule.RULE_ID,
                findings[0].rule,
                "Rule must be branch-naming on iteration $i"
            )
            assertEquals(
                Severity.ERROR,
                findings[0].severity,
                "Severity must be ERROR on iteration $i"
            )
        }
    }
}
