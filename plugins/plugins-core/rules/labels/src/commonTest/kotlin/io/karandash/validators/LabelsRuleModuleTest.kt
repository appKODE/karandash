package io.karandash.validators

import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LabelsRuleModuleTest {
    @Test
    fun `module applies an all labels condition from json config`() =
        runTest {
            val module = LabelsRuleModule()
            assertTrue(module.initialize(TestPluginContext()))
            val config =
                Json.parseToJsonElement(
                    """{"conditionalLabels": [{"condition": {"hasAllLabels": ["release", "hotfix"]}, "requiredLabels": ["qa-approved"]}]}""",
                )

            assertTrue(module.validate(LabelsRuleModule.RULE_ID, createTestMergeRequest(labels = listOf("release")), config).isEmpty())
            val finding = module
                .validate(
                    LabelsRuleModule.RULE_ID,
                    createTestMergeRequest(labels = listOf("release", "hotfix")),
                    config
                ).single()
            assertTrue(finding.message.contains("all labels ['release', 'hotfix'] are present"), finding.message)
        }

    @Test
    fun `an empty condition never applies`() {
        val config = LabelsConfig(conditionalLabels = listOf(ConditionalLabel(condition = LabelCondition(), requiredLabels = listOf("x"))))

        assertEquals(emptyList(), LabelsValidator(config).validate(createTestMergeRequest()).map { it.message })
    }
}
