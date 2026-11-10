package io.karandash.validators

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse

class ReviewerExplanationTest {
    @Test
    fun `rule explanation is published for its own rule id`() =
        runTest {
            assertFalse(ReviewerRuleModule().ruleExplanation(ReviewerRuleModule.RULE_ID).isNullOrBlank())
        }
}
