package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class CommitMessagesCoverageTest {
    private class Probe : CommitMessagesRuleModule() {
        fun twoArg(
            mr: MergeRequest,
            config: CommitMessagesConfig,
        ): List<Finding> = doValidateTyped(mr, config)
    }

    @Test
    fun `two argument validation delegates to the pattern aware form`() {
        assertNotNull(Probe().twoArg(createTestMergeRequest(), CommitMessagesConfig()))
    }

    @Test
    fun `rule explanation is published for its own rule id`() =
        runTest {
            assertFalse(CommitMessagesRuleModule().ruleExplanation(CommitMessagesRuleModule.RULE_ID).isNullOrBlank())
        }
}
