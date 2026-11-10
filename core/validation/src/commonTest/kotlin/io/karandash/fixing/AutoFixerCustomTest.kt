@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.fixing

import io.karandash.api.PluginContext
import io.karandash.api.module.FixHandler
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.PlatformOperation
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoFixerCustomTest {
    private class ReviewerMutator(
        private val supported: Set<PlatformOperation>,
    ) : PlatformServiceModule {
        val reviewerCalls = mutableListOf<Triple<String, String, List<String>>>()
        val removedReviewerCalls = mutableListOf<Triple<String, String, List<String>>>()

        override val id: String = "mutator"
        override val name: String = "Mutator"
        override val version: String = "1.0.0"

        override suspend fun initialize(context: PluginContext): Boolean = true

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

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean
        ) {}

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String
        ) {}

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String
        ) {}

        override suspend fun approve(
            projectId: String,
            mrId: String
        ) {}

        override suspend fun unapprove(
            projectId: String,
            mrId: String
        ) {}

        override suspend fun supports(operation: PlatformOperation): Boolean = operation in supported

        override suspend fun addReviewers(
            projectId: String,
            mrId: String,
            usernames: List<String>,
        ): Result<Unit> {
            reviewerCalls += Triple(projectId, mrId, usernames)
            return Result.success(Unit)
        }

        override suspend fun removeReviewers(
            projectId: String,
            mrId: String,
            usernames: List<String>,
        ): Result<Unit> {
            removedReviewerCalls += Triple(projectId, mrId, usernames)
            return Result.success(Unit)
        }

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): MergeRequest = error("not used")

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("not used")

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String,
        ) = ""

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String,
        ) {}

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String,
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

    private fun finding(suggestion: FixSuggestion) = Finding(Severity.ERROR, "needs fix", "approval", fixSuggestions = listOf(suggestion))

    private val requestReview = FixSuggestion.Custom(
        type = RequestReviewFixHandler.TYPE,
        data = buildJsonObject { put("username", "bob") },
    )

    @Test
    fun `request-review custom fix adds the reviewer on a capable platform`() =
        runTest {
            val mutator = ReviewerMutator(setOf(PlatformOperation.MANAGE_REVIEWERS))
            val fixer = AutoFixer(mutator, "group/proj", "7")

            val results = fixer.applyFixes(fixer.analyzeFixable(listOf(finding(requestReview))))

            assertEquals(listOf(Triple("group/proj", "7", listOf("bob"))), mutator.reviewerCalls)
            assertTrue(results.single().success)
        }

    @Test
    fun `remove-review custom fix removes the reviewer through removeReviewers`() =
        runTest {
            val removeReview = FixSuggestion.Custom(
                type = RemoveReviewFixHandler.TYPE,
                data = buildJsonObject { put("username", "author") },
            )
            val capable = ReviewerMutator(setOf(PlatformOperation.MANAGE_REVIEWERS))
            val fixer = AutoFixer(capable, "group/proj", "7")

            val results = fixer.applyFixes(fixer.analyzeFixable(listOf(finding(removeReview))))

            assertEquals(listOf(Triple("group/proj", "7", listOf("author"))), capable.removedReviewerCalls)
            assertTrue(results.single().success)
            val incapable = ReviewerMutator(emptySet())
            val skipped = AutoFixer(incapable, "p", "1").let { it.applyFixes(it.analyzeFixable(listOf(finding(removeReview)))) }
            assertTrue(incapable.removedReviewerCalls.isEmpty())
            assertTrue(skipped.single().skipped)
        }

    @Test
    fun `request-review is skipped when the platform cannot manage reviewers`() =
        runTest {
            val mutator = ReviewerMutator(emptySet())
            val fixer = AutoFixer(mutator, "p", "1")

            val result = fixer.applyFixes(fixer.analyzeFixable(listOf(finding(requestReview)))).single()

            assertTrue(mutator.reviewerCalls.isEmpty())
            assertFalse(result.success)
            assertTrue(result.skipped)
        }

    @Test
    fun `unknown custom type without handler is reported as skipped`() =
        runTest {
            val fixer = AutoFixer(ReviewerMutator(PlatformOperation.entries.toSet()), "p", "1")

            val result = fixer
                .applyFixes(fixer.analyzeFixable(listOf(finding(FixSuggestion.Custom(type = "bump-version")))))
                .single()

            assertTrue(result.skipped)
            assertTrue(result.error.orEmpty().contains("bump-version"))
        }

    @Test
    fun `plugin fix handler is used for its custom type`() =
        runTest {
            val handled = mutableListOf<String>()
            val handler = object : FixHandler {
                override fun canHandle(suggestion: FixSuggestion) = (suggestion as? FixSuggestion.Custom)?.type == "bump-version"

                override suspend fun apply(
                    suggestion: FixSuggestion,
                    finding: Finding,
                    mutator: PlatformServiceModule,
                    projectId: String,
                    mrId: String,
                ): String {
                    handled += "$projectId!$mrId"
                    return "bumped"
                }
            }
            val fixer = AutoFixer(ReviewerMutator(emptySet()), "p", "9", fixHandlers = listOf(handler))

            val result = fixer
                .applyFixes(fixer.analyzeFixable(listOf(finding(FixSuggestion.Custom(type = "bump-version")))))
                .single()

            assertEquals(listOf("p!9"), handled)
            assertTrue(result.success)
        }
}
