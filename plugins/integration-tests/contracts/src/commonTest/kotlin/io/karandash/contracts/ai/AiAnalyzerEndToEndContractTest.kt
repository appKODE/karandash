package io.karandash.contracts.ai

import io.karandash.contracts.ai.AiReviewHarness.Companion.ADDED_LINE
import io.karandash.contracts.ai.AiReviewHarness.Companion.aiFindings
import io.karandash.models.finding.Location
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The review-comments and mr-summary analyzers end to end: YAML enables them, each parses its part of
 * the fake LLM reply and the pipeline returns its finding; code-suggestions runs as its own analyzer.
 */
class AiAnalyzerEndToEndContractTest {
    private val reply = buildJsonObject {
        put("summary", SUMMARY)
        put("keyChanges", buildJsonArray { add(JsonPrimitive("adds risky()")) })
    }

    @Test
    fun `review-comments returns the LLM comment at its line`() =
        runTest(timeout = 2.minutes) {
            val findings = aiFindings(AiReviewHarness().run()).filter { it.rule == "review-comments" }

            val comment = findings.single { it.message.contains("Possible null dereference") }
            assertEquals(ADDED_LINE, (comment.location as? Location.FileRef)?.line, comment.toString())
        }

    /** `mode: separate`: the code-suggestions call supplies the code, merged into the review comment on its line. */
    @Test
    fun `code-suggestions mode separate gives the review comment on its line a committable suggestion`() =
        runTest(timeout = 2.minutes) {
            val comment = FakeComment("src/A.kt", SUGGESTION_LINE, "Bar() bypasses the factory")
            val suggestionReply = buildJsonObject {
                reply.forEach { (key, value) -> put(key, value) }
                put(
                    "suggestions",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("file", "src/A.kt")
                                put("line", SUGGESTION_LINE)
                                put("existingCode", "fun other() = Bar()")
                                put("suggestedCode", SUGGESTED)
                                put("explanation", "Use the factory.")
                            },
                        )
                    },
                )
            }
            val harness = AiReviewHarness(comments = listOf(comment), extraReply = suggestionReply)
            val findings = aiFindings(harness.run("  codeSuggestions:\n    enabled: true\n    mode: separate"))

            val suggestion = findings.singleOrNull { it.metadata[AiFindingMetadataKeys.SUGGESTED_CODE] != null }
            assertTrue(suggestion != null, "no committable suggestion: $findings")
            assertEquals("review-comments", suggestion.rule)
            assertEquals("Bar() bypasses the factory", suggestion.message)
            assertEquals(SUGGESTED, suggestion.metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
            assertEquals(Location.FileRef(path = "src/A.kt", line = SUGGESTION_LINE), suggestion.location)
        }

    @Test
    fun `mr-summary returns one summary finding`() =
        runTest(timeout = 2.minutes) {
            val harness = AiReviewHarness(extraReply = reply)
            val findings = aiFindings(harness.run("  mrSummary:\n    enabled: true"))

            val summary = findings.singleOrNull { it.rule == "mr-summary" }
            assertTrue(summary != null, "no mr-summary finding: $findings")
            assertEquals("true", summary.metadata[AiFindingMetadataKeys.IS_MR_SUMMARY])
            assertTrue(summary.message.startsWith(SUMMARY) && "adds risky()" in summary.message, summary.message)
        }

    private companion object {
        const val SUGGESTED = "fun other() = Bar.create()"

        /** `+fun other() = Bar()`: a different line from the review comment, so the two are not merged. */
        const val SUGGESTION_LINE = ADDED_LINE + 1
        const val SUMMARY = "Adds a risky helper."
    }
}
