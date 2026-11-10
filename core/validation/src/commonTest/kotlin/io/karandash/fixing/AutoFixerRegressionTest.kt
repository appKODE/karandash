package io.karandash.fixing

import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Severity
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AutoFixer composition and ordering. */
class AutoFixerRegressionTest {
    private class RecordingMutator : PlatformServiceModule {
        val events = mutableListOf<String>()

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean,
        ) {
            events += "draft:$draft"
        }

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String,
        ) {
            events += "title-start:$title"
            delay(TITLE_LATENCY_MS)
            events += "title-end:$title"
        }

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String,
        ) {
            events += "description:$description"
        }

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) {}

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) {}

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
            mrId: String
        ): MergeRequest = error("not used")

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("not used")

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
            diffRefs: DiffRefs,
            startLine: Int?,
        ) = ""
    }

    private fun finding(vararg fixes: FixSuggestion) =
        Finding(severity = Severity.ERROR, message = "m", rule = "r", fixSuggestions = fixes.toList())

    @Test
    fun `draft change waits for the title update`() =
        runTest {
            val mutator = RecordingMutator()
            val fixer = AutoFixer(mutator, "p", "1")
            val actions = fixer.analyzeFixable(listOf(finding(FixSuggestion.SetDraft(false), FixSuggestion.SetTitle("feat: x"))))

            val results = fixer.applyFixes(actions)

            assertTrue(results.all { it.success })
            assertEquals(listOf("title-start:feat: x", "title-end:feat: x", "draft:false"), mutator.events)
        }

    @Test
    fun `conflicting title fixes apply the first and report the rest as skipped`() =
        runTest {
            val mutator = RecordingMutator()
            val fixer = AutoFixer(mutator, "p", "1")
            val actions = fixer.analyzeFixable(
                listOf(finding(FixSuggestion.SetTitle("feat: a")), finding(FixSuggestion.SetTitle("fix: b"))),
            )

            val results = fixer.applyFixes(actions)

            assertEquals(listOf("title-start:feat: a", "title-end:feat: a"), mutator.events)
            assertEquals(2, results.size)
            val conflicting = results.single { it.skipped }
            assertTrue(conflicting.error.orEmpty().contains("conflict"), conflicting.error)
            assertTrue(results.single { !it.skipped }.success)
        }

    @Test
    fun `identical title fixes are applied once and all succeed`() =
        runTest {
            val mutator = RecordingMutator()
            val fixer = AutoFixer(mutator, "p", "1")
            val actions = fixer.analyzeFixable(
                listOf(finding(FixSuggestion.SetTitle("feat: a")), finding(FixSuggestion.SetTitle("feat: a"))),
            )

            val results = fixer.applyFixes(actions)

            assertEquals(1, mutator.events.count { it.startsWith("title-start") })
            assertEquals(2, results.size)
            assertTrue(results.all { it.success })
        }

    @Test
    fun `conflicting description fixes apply the first and report the rest as skipped`() =
        runTest {
            val mutator = RecordingMutator()
            val fixer = AutoFixer(mutator, "p", "1")
            val actions = fixer.analyzeFixable(
                listOf(finding(FixSuggestion.SetDescription("d1")), finding(FixSuggestion.SetDescription("d2"))),
            )

            val results = fixer.applyFixes(actions)

            assertEquals(listOf("description:d1"), mutator.events)
            assertTrue(
                results
                    .single { it.skipped }
                    .error
                    .orEmpty()
                    .contains("conflict")
            )
        }

    private companion object {
        const val TITLE_LATENCY_MS = 100L
    }
}
