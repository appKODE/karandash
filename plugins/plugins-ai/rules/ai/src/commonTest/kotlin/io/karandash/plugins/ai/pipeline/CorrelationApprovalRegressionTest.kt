package io.karandash.plugins.ai.pipeline

import io.karandash.logger.NoOpLogger
import io.karandash.models.Approval
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `correlation:security-without-review` fires although the MR is approved. */
class CorrelationApprovalRegressionTest {
    private val processor = AiFindingPostProcessor(NoOpLogger)
    private val security = Finding(severity = Severity.WARNING, message = "secret", rule = "security")

    @Test
    fun `security without review does not fire on an approved MR`() {
        val mr = createTestMergeRequest(approvals = listOf(Approval(user = User(username = "bob", name = "Bob"))))
        val result = processor.applyCorrelation(listOf(security), null, mr)
        assertEquals(listOf(security), result, result.toString())
    }

    @Test
    fun `security without review still fires on an unapproved MR`() {
        val result = processor.applyCorrelation(listOf(security), null, createTestMergeRequest())
        assertTrue(result.any { it.rule == "correlation:security-without-review" }, result.toString())
    }
}
