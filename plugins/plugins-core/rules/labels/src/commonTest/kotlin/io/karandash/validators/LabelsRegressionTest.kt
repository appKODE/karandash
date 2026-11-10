@file:OptIn(ExperimentalKarandashApi::class)

package io.karandash.validators

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.platform.MergeRequestField
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every set label condition must hold, not just the first;
 * the labels rule is skipped on platforms without labels.
 */
class LabelsRegressionTest {
    private val config = LabelsConfig(
        conditionalLabels = listOf(
            ConditionalLabel(
                condition = LabelCondition(hasLabel = "a", hasAllLabels = listOf("a", "b")),
                requiredLabels = listOf("c"),
            ),
        ),
    )

    @Test
    fun `all set conditions must hold`() {
        val findings = LabelsValidator(config).validate(createTestMergeRequest(labels = listOf("a")))
        assertEquals(0, findings.size, findings.toString())
    }

    @Test
    fun `conditions together still trigger the requirement`() {
        val findings = LabelsValidator(config).validate(createTestMergeRequest(labels = listOf("a", "b")))
        assertEquals(1, findings.size, findings.toString())
    }

    @Test
    fun `labels rule requires the labels field`() {
        assertEquals(setOf(MergeRequestField.LABELS), LabelsRuleModule().requiredMergeRequestFields(LabelsRuleModule.RULE_ID))
    }
}
