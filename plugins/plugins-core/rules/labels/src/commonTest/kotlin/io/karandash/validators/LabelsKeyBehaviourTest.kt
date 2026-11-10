package io.karandash.validators

import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LabelsKeyBehaviourTest {
    private fun messages(
        config: LabelsConfig,
        vararg labels: String,
    ): List<String> = LabelsValidator(config).validate(createTestMergeRequest(labels = labels.toList())).map { it.message }

    private fun conditional(
        condition: LabelCondition,
        required: List<String> = emptyList(),
        forbidden: List<String> = emptyList(),
    ) = LabelsConfig(conditionalLabels = listOf(ConditionalLabel(condition, required, forbidden)))

    @Test
    fun `requiredLabels flags a missing label`() {
        val config = LabelsConfig(requiredLabels = listOf("reviewed"))
        assertTrue(messages(config).single().contains("Missing required label"))
        assertEquals(emptyList(), messages(config, "reviewed"))
    }

    @Test
    fun `forbiddenLabels flags a present label`() {
        val config = LabelsConfig(forbiddenLabels = listOf("wip"))
        assertTrue(messages(config, "wip").single().contains("Forbidden label present"))
        assertEquals(emptyList(), messages(config, "ready"))
    }

    @Test
    fun `conditionalLabels applies requirements only when the condition holds`() {
        val config = conditional(LabelCondition(hasLabel = "db"), required = listOf("migration"))
        assertEquals(emptyList(), messages(config, "ui"))
        assertEquals(1, messages(config, "db").size)
    }

    @Test
    fun `conditionalLabels condition selects when the requirements apply`() {
        val config = conditional(LabelCondition(hasLabel = "db"), required = listOf("migration"))
        assertEquals(1, messages(config, "db").size)
        assertEquals(emptyList(), messages(config, "ui"))
        assertEquals(emptyList(), messages(config, "db", "migration"))
    }

    @Test
    fun `conditionalLabels condition hasLabel triggers on a single label`() {
        val config = conditional(LabelCondition(hasLabel = "db"), required = listOf("migration"))
        assertTrue(messages(config, "db").single().contains("migration"))
    }

    @Test
    fun `conditionalLabels condition hasAnyLabel triggers on one of several labels`() {
        val config = conditional(LabelCondition(hasAnyLabel = listOf("db", "api")), required = listOf("migration"))
        assertEquals(1, messages(config, "api").size)
        assertEquals(emptyList(), messages(config, "ui"))
    }

    @Test
    fun `conditionalLabels condition hasAllLabels needs every label`() {
        val config = conditional(LabelCondition(hasAllLabels = listOf("db", "api")), required = listOf("migration"))
        assertEquals(emptyList(), messages(config, "api"))
        assertEquals(1, messages(config, "api", "db").size)
    }

    @Test
    fun `conditionalLabels requiredLabels flags a missing label when the condition holds`() {
        val config = conditional(LabelCondition(hasLabel = "db"), required = listOf("migration"))
        assertEquals(1, messages(config, "db").size)
        assertEquals(emptyList(), messages(config, "db", "migration"))
    }

    @Test
    fun `conditionalLabels forbiddenLabels flags a present label when the condition holds`() {
        val config = conditional(LabelCondition(hasLabel = "db"), forbidden = listOf("hotfix"))
        assertTrue(messages(config, "db", "hotfix").single().contains("forbidden"))
        assertEquals(emptyList(), messages(config, "hotfix"))
    }
}
