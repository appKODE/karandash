package io.karandash.fixing

import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoFixerDescriptionTest {
    private class MockMutator : PlatformServiceModule {
        val addedLabels = mutableListOf<List<String>>()
        var updatedTitle: String? = null
        var updatedDescription: String? = null

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
        ) {}

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean
        ) {}

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String
        ) {
            updatedTitle = title
        }

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String
        ) {
            updatedDescription = description
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

    private fun descriptionFinding(
        suggestedTitle: String = "feat: improve auth flow",
        suggestedDescription: String = "This PR improves the authentication flow by adding retry logic.",
    ): Finding {
        val fixSuggestions = buildList {
            if (suggestedTitle.isNotEmpty()) add(FixSuggestion.SetTitle(suggestedTitle))
            if (suggestedDescription.isNotEmpty()) add(FixSuggestion.SetDescription(suggestedDescription))
        }
        return Finding(
            severity = Severity.INFO,
            message = "AI-generated description available. Suggested title: \"$suggestedTitle\"",
            rule = "generate-description",
            metadata = mapOf(
                "suggestedTitle" to suggestedTitle,
                "suggestedDescription" to suggestedDescription,
                "aiModel" to "gpt-4o",
                "aiTokensUsed" to "1200",
            ),
            fixSuggestions = fixSuggestions,
        )
    }

    @Test
    fun `finding with suggestedTitle creates UPDATE_TITLE action`() {
        val finding = descriptionFinding(suggestedTitle = "feat: improve auth flow")
        val fixer = AutoFixer(MockMutator(), "proj", "42")

        val actions = fixer.analyzeFixable(listOf(finding))

        val titleAction = actions.firstOrNull { it.type == FixType.UPDATE_TITLE }
        assertTrue(titleAction != null)
        assertTrue(titleAction.description.contains("feat: improve auth flow"))
    }

    @Test
    fun `finding with suggestedDescription creates UPDATE_DESCRIPTION action`() {
        val finding = descriptionFinding(suggestedDescription = "This PR fixes a critical bug.")
        val fixer = AutoFixer(MockMutator(), "proj", "42")

        val actions = fixer.analyzeFixable(listOf(finding))

        val descAction = actions.firstOrNull { it.type == FixType.UPDATE_DESCRIPTION }
        assertTrue(descAction != null)
    }

    @Test
    fun `finding with both suggestions creates both fix actions`() {
        val finding = descriptionFinding()
        val fixer = AutoFixer(MockMutator(), "proj", "42")

        val actions = fixer.analyzeFixable(listOf(finding))

        assertEquals(2, actions.size)
        assertTrue(actions.any { it.type == FixType.UPDATE_TITLE })
        assertTrue(actions.any { it.type == FixType.UPDATE_DESCRIPTION })
    }

    @Test
    fun `finding without fix suggestions produces no title or description actions`() {
        val finding = Finding(
            severity = Severity.ERROR,
            message = "Missing required label: 'bug'",
            rule = "labels",
            fixSuggestions = listOf(FixSuggestion.AddLabel("bug")),
        )
        val fixer = AutoFixer(MockMutator(), "proj", "42")

        val actions = fixer.analyzeFixable(listOf(finding))

        assertTrue(actions.none { it.type == FixType.UPDATE_TITLE })
        assertTrue(actions.none { it.type == FixType.UPDATE_DESCRIPTION })
    }

    @Test
    fun `applyFixes calls updateTitle via mutator`() =
        runTest {
            val finding = descriptionFinding(suggestedTitle = "fix: correct null pointer")
            val mutator = MockMutator()
            val fixer = AutoFixer(mutator, "proj-1", "mr-7")

            val actions = fixer.analyzeFixable(listOf(finding))
            val results = fixer.applyFixes(actions)

            assertEquals("fix: correct null pointer", mutator.updatedTitle)
            assertTrue(results.first { it.action.type == FixType.UPDATE_TITLE }.success)
        }

    @Test
    fun `applyFixes calls updateDescription via mutator`() =
        runTest {
            val desc = "Adds retry logic to the HTTP client."
            val finding = descriptionFinding(suggestedDescription = desc)
            val mutator = MockMutator()
            val fixer = AutoFixer(mutator, "proj-1", "mr-7")

            val actions = fixer.analyzeFixable(listOf(finding))
            val results = fixer.applyFixes(actions)

            assertEquals(desc, mutator.updatedDescription)
            assertTrue(results.first { it.action.type == FixType.UPDATE_DESCRIPTION }.success)
        }

    @Test
    fun `applyFixes applies both title and description when both present`() =
        runTest {
            val finding = descriptionFinding(
                suggestedTitle = "refactor: extract service layer",
                suggestedDescription = "Moves business logic out of controllers.",
            )
            val mutator = MockMutator()
            val fixer = AutoFixer(mutator, "proj", "mr-99")

            val actions = fixer.analyzeFixable(listOf(finding))
            val results = fixer.applyFixes(actions)

            assertEquals("refactor: extract service layer", mutator.updatedTitle)
            assertEquals("Moves business logic out of controllers.", mutator.updatedDescription)
            assertEquals(2, results.size)
            assertTrue(results.all { it.success })
        }

    @Test
    fun `finding with empty suggestedTitle produces no UPDATE_TITLE action`() {
        val finding = Finding(
            severity = Severity.INFO,
            message = "AI description",
            rule = "generate-description",
            metadata = mapOf(
                "suggestedTitle" to "",
                "suggestedDescription" to "Some description",
            ),
            fixSuggestions = listOf(FixSuggestion.SetDescription("Some description")),
        )
        val fixer = AutoFixer(MockMutator(), "proj", "42")

        val actions = fixer.analyzeFixable(listOf(finding))

        assertTrue(actions.none { it.type == FixType.UPDATE_TITLE })
        assertTrue(actions.any { it.type == FixType.UPDATE_DESCRIPTION })
    }

    @Test
    fun `finding with empty suggestedDescription produces no UPDATE_DESCRIPTION action`() {
        val finding = Finding(
            severity = Severity.INFO,
            message = "AI description",
            rule = "generate-description",
            metadata = mapOf(
                "suggestedTitle" to "feat: new feature",
                "suggestedDescription" to "",
            ),
            fixSuggestions = listOf(FixSuggestion.SetTitle("feat: new feature")),
        )
        val fixer = AutoFixer(MockMutator(), "proj", "42")

        val actions = fixer.analyzeFixable(listOf(finding))

        assertTrue(actions.any { it.type == FixType.UPDATE_TITLE })
        assertTrue(actions.none { it.type == FixType.UPDATE_DESCRIPTION })
    }

    @Test
    fun `mutator updateTitle not called when no UPDATE_TITLE action`() =
        runTest {
            val finding = Finding(
                severity = Severity.ERROR,
                message = "Missing required label: 'bug'",
                rule = "labels",
                fixSuggestions = listOf(FixSuggestion.AddLabel("bug")),
            )
            val mutator = MockMutator()
            val fixer = AutoFixer(mutator, "proj", "42")

            val actions = fixer.analyzeFixable(listOf(finding))
            fixer.applyFixes(actions)

            assertNull(mutator.updatedTitle)
            assertNull(mutator.updatedDescription)
        }
}
