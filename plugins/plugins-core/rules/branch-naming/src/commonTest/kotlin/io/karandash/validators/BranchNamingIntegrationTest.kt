package io.karandash.validators

import io.karandash.config.Config
import io.karandash.config.RulesConfig
import io.karandash.testfixtures.createTestEngine
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.toJsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BranchNamingIntegrationTest {
    @Test
    fun testBranchNamingValidatorIntegration() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        BranchNamingRuleModule.RULE_ID to BranchNamingConfig(
                            pattern = "feature/.*",
                            requireSourceMatch = true,
                            requireTargetMatch = false
                        ).toJsonElement()
                    )
                )
            )
            val engine = createTestEngine(config)

            val validMr = createTestMergeRequest(sourceBranch = "feature/add-validation")
            val invalidMr = createTestMergeRequest(sourceBranch = "invalid-branch")

            val validResult = engine.validate(validMr)
            assertTrue(validResult.passed)
            assertTrue(validResult.findings.isEmpty())

            val invalidResult = engine.validate(invalidMr)
            assertFalse(invalidResult.passed)
            assertEquals(1, invalidResult.findings.size)
            assertEquals("branchNaming", invalidResult.findings[0].rule)
        }
}
