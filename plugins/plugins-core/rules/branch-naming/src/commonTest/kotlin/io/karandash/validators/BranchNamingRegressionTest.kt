package io.karandash.validators

import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** setting both pattern and allowedPatterns is reported, not silently resolved. */
class BranchNamingRegressionTest {
    @Test
    fun `warns when both pattern and allowedPatterns are set`() {
        val config = BranchNamingConfig(pattern = "^nope$", allowedPatterns = listOf("^feature/.+$"))
        val finding = BranchNamingValidator(config)
            .validate(createTestMergeRequest(sourceBranch = "feature/x"))
            .single()
        assertEquals(Severity.WARNING, finding.severity)
        assertTrue(finding.message.contains("allowedPatterns"), finding.message)
    }
}
