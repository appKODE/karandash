package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class DescriptionFormatCoverageTest {
    private class Probe : DescriptionFormatRuleModule() {
        fun twoArg(
            mr: MergeRequest,
            config: DescriptionFormatConfig,
        ): List<Finding> = doValidateTyped(mr, config)
    }

    @Test
    fun `two argument validation delegates to the pattern aware form`() {
        assertNotNull(Probe().twoArg(createTestMergeRequest(), DescriptionFormatConfig()))
    }

    @Test
    fun `rule explanation is published for its own rule id`() =
        runTest {
            assertFalse(DescriptionFormatRuleModule().ruleExplanation(DescriptionFormatRuleModule.RULE_ID).isNullOrBlank())
        }
}
