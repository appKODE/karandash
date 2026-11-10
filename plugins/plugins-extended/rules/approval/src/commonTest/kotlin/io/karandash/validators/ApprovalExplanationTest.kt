package io.karandash.validators

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse

class ApprovalExplanationTest {
    @Test
    fun `rule explanation is published for its own rule id`() =
        runTest {
            assertFalse(ApprovalRuleModule().ruleExplanation(ApprovalRuleModule.RULE_ID).isNullOrBlank())
        }
}
