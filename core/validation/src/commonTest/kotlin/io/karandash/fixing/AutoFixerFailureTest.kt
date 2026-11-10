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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AutoFixerFailureTest {
    /** Every mutation fails with a message naming the operation; reviewers fail through [Result]. */
    private class DownMutator : PlatformServiceModule {
        override val id: String = "down"
        override val name: String = "Down"
        override val version: String = "1.0.0"

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ): Unit = error("down: addLabels")

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ): Unit = error("down: removeLabels")

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean,
        ): Unit = error("down: setDraft")

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String,
        ): Unit = error("down: updateTitle")

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String,
        ): Unit = error("down: updateDescription")

        override suspend fun approve(
            projectId: String,
            mrId: String,
        ) {}

        override suspend fun unapprove(
            projectId: String,
            mrId: String,
        ) {}

        override suspend fun supports(operation: PlatformOperation): Boolean = true

        override suspend fun addReviewers(
            projectId: String,
            mrId: String,
            usernames: List<String>,
        ): Result<Unit> = Result.failure(IllegalStateException("down: addReviewers"))

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

    private fun finding(
        vararg suggestions: FixSuggestion,
        confidence: String? = null,
    ) = Finding(
        Severity.ERROR,
        "needs fix",
        "rule",
        fixSuggestions = suggestions.toList(),
        metadata = confidence?.let { mapOf("confidence" to it) }.orEmpty(),
    )

    private fun throwingHandler(
        type: String,
        error: Exception,
    ) = object : FixHandler {
        override fun canHandle(suggestion: FixSuggestion) = (suggestion as? FixSuggestion.Custom)?.type == type

        override suspend fun apply(
            suggestion: FixSuggestion,
            finding: Finding,
            mutator: PlatformServiceModule,
            projectId: String,
            mrId: String,
        ): String = throw error
    }

    @Test
    fun `every failed platform mutation becomes a failed result carrying the error`() =
        runTest {
            val fixer = AutoFixer(DownMutator(), "p", "1")
            val actions = fixer.analyzeFixable(
                listOf(
                    finding(
                        FixSuggestion.AddLabel("a"),
                        FixSuggestion.AddLabel("b"),
                        FixSuggestion.RemoveLabel("c"),
                        FixSuggestion.SetDraft(false),
                        FixSuggestion.SetTitle("t"),
                        FixSuggestion.SetDescription("d"),
                    ),
                ),
            )

            val results = fixer.applyFixes(actions)

            assertEquals(6, results.size)
            assertTrue(results.none { it.success || it.skipped })
            assertEquals(
                listOf("addLabels", "addLabels", "removeLabels", "updateTitle", "setDraft", "updateDescription"),
                results.map { it.error.orEmpty().removePrefix("down: ") },
            )
        }

    @Test
    fun `custom handlers that throw are failures and unsupported operations are skips`() =
        runTest {
            val fixer = AutoFixer(
                DownMutator(),
                "p",
                "1",
                fixHandlers = listOf(
                    throwingHandler("unsupported", UnsupportedOperationException("no such api")),
                    throwingHandler("broken", IllegalStateException("boom")),
                ),
            )
            val reviewWith = { data: kotlinx.serialization.json.JsonElement ->
                FixSuggestion.Custom(type = RequestReviewFixHandler.TYPE, data = data)
            }
            val suggestions = listOf(
                FixSuggestion.Custom(type = "unsupported"),
                FixSuggestion.Custom(type = "broken", description = "fix it"),
                reviewWith(buildJsonObject { put("username", JsonPrimitive("bob")) }),
                reviewWith(buildJsonObject { put("username", JsonPrimitive(3)) }),
                reviewWith(buildJsonObject { put("username", JsonArray(emptyList())) }),
                reviewWith(buildJsonObject { put("username", JsonPrimitive(" ")) }),
                reviewWith(JsonPrimitive("bob")),
                reviewWith(kotlinx.serialization.json.JsonNull),
            )

            val results = fixer.applyFixes(fixer.analyzeFixable(listOf(finding(*suggestions.toTypedArray()))))

            assertEquals("fix it", results[1].action.description)
            assertEquals(listOf(true, false, false, true, true, true, true, true), results.map { it.skipped })
            assertEquals(listOf("no such api", "boom", "down: addReviewers"), results.take(3).map { it.error })
            assertTrue(results.none { it.success })
        }

    @Test
    fun `confidence gates suggestions and drafting or empty suggestions produce no action`() {
        val fixer = AutoFixer(DownMutator(), "p", "1", aiConfidenceThreshold = 0.5)
        val label = FixSuggestion.AddLabel("x")
        val cases = mapOf(
            finding(label, confidence = "0.49") to 0,
            finding(label, confidence = "0.5") to 1,
            finding(label, confidence = "not a number") to 1,
            finding(FixSuggestion.SetDraft(true), FixSuggestion.SetTitle(""), FixSuggestion.SetDescription("")) to 0,
        )
        cases.forEach { (input, expected) -> assertEquals(expected, fixer.analyzeFixable(listOf(input)).size, input.toString()) }
        listOf(-0.1, 1.1).forEach { threshold ->
            assertFailsWith<IllegalArgumentException> { AutoFixer(DownMutator(), "p", "1", aiConfidenceThreshold = threshold) }
        }
    }
}
