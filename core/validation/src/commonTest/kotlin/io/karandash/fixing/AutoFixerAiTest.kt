package io.karandash.fixing

import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoFixerAiTest {
    private class MockMutator : PlatformServiceModule {
        val addedLabels = mutableListOf<List<String>>()
        val removedLabels = mutableListOf<List<String>>()
        var draftStatus: Boolean? = null

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) {
            addedLabels.add(labels)
        }

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) {
            removedLabels.add(labels)
        }

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean
        ) {
            draftStatus = draft
        }

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String
        ) {
        }

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String
        ) {
        }

        override suspend fun approve(
            projectId: String,
            mrId: String
        ) {}

        override suspend fun unapprove(
            projectId: String,
            mrId: String
        ) {}

        override val id: String = "mutator"
        override val name: String = "Mutator"
        override val version: String = "1.0.0"

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): io.karandash.models.MergeRequest = error("not implemented")

        override suspend fun fetchMergeRequestByUrl(url: String): io.karandash.models.MergeRequest = error("not implemented")

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String
        ) = ""

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String
        ) {}

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String
        ): String? = null

        override suspend fun postInlineComment(
            projectId: String,
            mrId: String,
            body: String,
            filePath: String,
            line: Int,
            diffRefs: io.karandash.models.DiffRefs,
            startLine: Int?,
        ) = ""
    }

    private fun aiCategorizationFinding(
        labels: List<String>,
        confidence: String,
    ) = Finding(
        severity = Severity.INFO,
        message = "Suggested labels: ${labels.joinToString(", ")}",
        rule = "change-categorization",
        metadata = mapOf(
            "suggestedLabels" to labels.joinToString(","),
            "confidence" to confidence,
        ),
        fixSuggestions = labels.map { FixSuggestion.AddLabel(it) },
    )

    @Test
    fun `AI finding with high confidence creates ADD_LABEL actions`() {
        val finding = aiCategorizationFinding(listOf("bug", "feature"), "0.85")

        val fixer = AutoFixer(MockMutator(), "proj", "123")
        val actions = fixer.analyzeFixable(listOf(finding))

        assertEquals(2, actions.size)
        assertTrue(actions.all { it.type == FixType.ADD_LABEL })
        assertTrue(actions.any { it.description.contains("'bug'") })
        assertTrue(actions.any { it.description.contains("'feature'") })
    }

    @Test
    fun `AI finding with low confidence is skipped`() {
        val finding = aiCategorizationFinding(listOf("bug"), "0.75")

        val fixer = AutoFixer(MockMutator(), "proj", "123")
        val actions = fixer.analyzeFixable(listOf(finding))

        assertEquals(0, actions.size)
    }

    @Test
    fun `AI finding respects custom confidence threshold`() {
        val finding = aiCategorizationFinding(listOf("bug"), "0.75")

        val fixer = AutoFixer(
            mutator = MockMutator(),
            projectId = "proj",
            mrId = "123",
            aiConfidenceThreshold = 0.7
        )
        val actions = fixer.analyzeFixable(listOf(finding))

        assertEquals(1, actions.size)
        assertEquals(FixType.ADD_LABEL, actions[0].type)
    }

    @Test
    fun `AI finding with confidence exactly at threshold creates actions`() {
        val finding = aiCategorizationFinding(listOf("enhancement"), "0.8")

        val fixer = AutoFixer(MockMutator(), "proj", "123")
        val actions = fixer.analyzeFixable(listOf(finding))

        assertEquals(1, actions.size)
        assertEquals(FixType.ADD_LABEL, actions[0].type)
        assertTrue(actions[0].description.contains("'enhancement'"))
    }

    @Test
    fun `AI finding with multiple labels creates multiple actions`() {
        val finding = aiCategorizationFinding(listOf("bug", "enhancement", "urgent"), "0.9")

        val fixer = AutoFixer(MockMutator(), "proj", "123")
        val actions = fixer.analyzeFixable(listOf(finding))

        assertEquals(3, actions.size)
        assertTrue(actions.all { it.type == FixType.ADD_LABEL })
        assertTrue(actions.any { it.description.contains("'bug'") })
        assertTrue(actions.any { it.description.contains("'enhancement'") })
        assertTrue(actions.any { it.description.contains("'urgent'") })
    }

    @Test
    fun `AI finding without fixSuggestions creates no actions`() {
        val finding = Finding(
            severity = Severity.INFO,
            message = "Some AI message",
            rule = "change-categorization",
            metadata = mapOf(
                "confidence" to "0.9"
            ),
        )

        val fixer = AutoFixer(MockMutator(), "proj", "123")
        val actions = fixer.analyzeFixable(listOf(finding))

        assertEquals(0, actions.size)
    }

    @Test
    fun `AI finding without confidence creates actions since no threshold applies`() {
        val finding = Finding(
            severity = Severity.INFO,
            message = "Suggested labels: bug",
            rule = "change-categorization",
            metadata = mapOf(
                "suggestedLabels" to "bug"
            ),
            fixSuggestions = listOf(FixSuggestion.AddLabel("bug")),
        )

        val fixer = AutoFixer(MockMutator(), "proj", "123")
        val actions = fixer.analyzeFixable(listOf(finding))

        assertEquals(1, actions.size)
    }

    @Test
    fun `AI finding with invalid confidence defaults to no threshold and creates actions`() {
        val finding = Finding(
            severity = Severity.INFO,
            message = "Suggested labels: bug",
            rule = "change-categorization",
            metadata = mapOf(
                "suggestedLabels" to "bug",
                "confidence" to "invalid"
            ),
            fixSuggestions = listOf(FixSuggestion.AddLabel("bug")),
        )

        val fixer = AutoFixer(MockMutator(), "proj", "123")
        val actions = fixer.analyzeFixable(listOf(finding))

        assertEquals(1, actions.size)
    }

    @Test
    fun `Non-AI findings still work correctly via fixSuggestions`() {
        val finding = Finding(
            severity = Severity.ERROR,
            message = "Missing required label: 'priority'",
            rule = "labels",
            fixSuggestions = listOf(FixSuggestion.AddLabel("priority")),
        )

        val fixer = AutoFixer(MockMutator(), "proj", "123")
        val actions = fixer.analyzeFixable(listOf(finding))

        assertEquals(1, actions.size)
        assertEquals(FixType.ADD_LABEL, actions[0].type)
        assertTrue(actions[0].description.contains("priority"))
    }

    @Test
    fun `Empty fixSuggestions list creates no actions`() {
        val finding = Finding(
            severity = Severity.INFO,
            message = "No labels suggested",
            rule = "change-categorization",
            metadata = mapOf(
                "suggestedLabels" to "",
                "confidence" to "0.9"
            ),
        )

        val fixer = AutoFixer(MockMutator(), "proj", "123")
        val actions = fixer.analyzeFixable(listOf(finding))

        assertEquals(0, actions.size)
    }

    @Test
    fun `applyFixes correctly applies AI-suggested labels via mutator`() =
        runTest {
            val finding = aiCategorizationFinding(listOf("bug", "documentation"), "0.92")

            val mutator = MockMutator()
            val fixer = AutoFixer(mutator, "proj-123", "mr-456")
            val actions = fixer.analyzeFixable(listOf(finding))
            val results = fixer.applyFixes(actions)

            assertEquals(2, results.size)
            assertTrue(results.all { it.success })

            assertEquals(1, mutator.addedLabels.size)
            assertTrue(mutator.addedLabels[0].contains("bug"))
            assertTrue(mutator.addedLabels[0].contains("documentation"))
        }

    @Test
    fun `Mixed AI and regular label findings are batched correctly`() =
        runTest {
            val aiFind = aiCategorizationFinding(listOf("bug"), "0.9")

            val regularFinding = Finding(
                severity = Severity.ERROR,
                message = "Missing required label: 'priority'",
                rule = "labels",
                fixSuggestions = listOf(FixSuggestion.AddLabel("priority")),
            )

            val mutator = MockMutator()
            val fixer = AutoFixer(mutator, "proj", "mr")
            val actions = fixer.analyzeFixable(listOf(aiFind, regularFinding))
            val results = fixer.applyFixes(actions)

            assertEquals(2, results.size)
            assertTrue(results.all { it.success })

            assertEquals(1, mutator.addedLabels.size)
            assertEquals(2, mutator.addedLabels[0].size)
            assertTrue(mutator.addedLabels[0].contains("bug"))
            assertTrue(mutator.addedLabels[0].contains("priority"))
        }

    @Test
    fun `Findings without fixSuggestions are ignored`() {
        val finding = Finding(
            severity = Severity.INFO,
            message = "Test",
            rule = "change-categorization",
            metadata = mapOf(
                "suggestedLabels" to "test",
                "confidence" to "0.876543"
            ),
        )

        val fixer = AutoFixer(MockMutator(), "proj", "123")
        val actions = fixer.analyzeFixable(listOf(finding))

        assertEquals(0, actions.size)
    }
}
