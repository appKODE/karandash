package io.karandash.validators

import io.karandash.config.Config
import io.karandash.config.RulesConfig
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.testfixtures.createTestEngine
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.toJsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileChangesIntegrationTest {
    @Test
    fun testFileChangesValidatorIntegration() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        FileChangesRuleModule.RULE_ID to FileChangesConfig(
                            patterns = listOf(
                                FilePatternRule(
                                    pattern = "*.md",
                                    requireLabels = listOf("documentation")
                                )
                            )
                        ).toJsonElement()
                    )
                )
            )
            val engine = createTestEngine(config)

            val mrWithoutLabel = createTestMergeRequest(
                changes = listOf(FileChange("README.md", ChangeType.MODIFIED)),
                labels = emptyList()
            )

            val mrWithLabel = createTestMergeRequest(
                changes = listOf(FileChange("README.md", ChangeType.MODIFIED)),
                labels = listOf("documentation")
            )

            val resultWithoutLabel = engine.validate(mrWithoutLabel)
            assertFalse(resultWithoutLabel.passed)
            assertEquals(1, resultWithoutLabel.findings.size)
            assertEquals("fileChanges", resultWithoutLabel.findings[0].rule)

            val resultWithLabel = engine.validate(mrWithLabel)
            assertTrue(resultWithLabel.passed)
            assertTrue(resultWithLabel.findings.isEmpty())
        }
}
