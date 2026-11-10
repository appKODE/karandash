package io.karandash.contracts.ai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * `ai.review.instructions`: an instruction reaches the review request only when one of its paths matches a
 * changed file, and the findings of a review that matched exactly one instruction name it in
 * `metadata.instruction`.
 */
class AiReviewInstructionsContractTest {
    private fun instructionsYaml(vararg entries: Triple<String, String, String>): String =
        "  review:\n    instructions:\n" +
            entries.joinToString("\n") { (name, path, text) ->
                "      - name: $name\n        paths: [\"$path\"]\n        text: \"$text\""
            }

    @Test
    fun `an instruction whose paths match a changed file reaches the review request`() =
        runTest(timeout = 2.minutes) {
            val harness = AiReviewHarness()
            harness.run(instructionsYaml(Triple(KOTLIN, "src/**/*.kt", KOTLIN_TEXT), Triple(DOCS, "docs/**", DOCS_TEXT)))

            assertTrue(harness.requestBodies.isNotEmpty(), "setup: no LLM request reached the MockEngine")
            assertTrue(harness.allRequests.contains(KOTLIN_TEXT), "matching instruction missing from the request")
            assertFalse(harness.allRequests.contains(DOCS_TEXT), "an instruction whose paths match no changed file was sent")
        }

    @Test
    fun `findings of a review with one matched instruction carry its name`() =
        runTest(timeout = 2.minutes) {
            val harness = AiReviewHarness()
            val result = harness.run(instructionsYaml(Triple(KOTLIN, "src/**/*.kt", KOTLIN_TEXT)))
            val findings = result.validationResult
                ?.findings
                .orEmpty()
                .filter { it.rule == "review-comments" }

            assertTrue(findings.isNotEmpty(), "setup: no review-comments finding")
            assertEquals(listOf(KOTLIN), findings.map { it.metadata["instruction"] }.distinct())
        }

    @Test
    fun `findings without a matched instruction carry no instruction name`() =
        runTest(timeout = 2.minutes) {
            val harness = AiReviewHarness()
            val result = harness.run(instructionsYaml(Triple(DOCS, "docs/**", DOCS_TEXT)))
            val findings = result.validationResult
                ?.findings
                .orEmpty()
                .filter { it.rule == "review-comments" }

            assertTrue(findings.isNotEmpty(), "setup: no review-comments finding")
            assertTrue(findings.none { "instruction" in it.metadata }, "finding names an instruction that did not match")
        }

    private companion object {
        const val KOTLIN = "kotlin-nullability"
        const val DOCS = "docs-tone"
        const val KOTLIN_TEXT = "KOTLIN-INSTRUCTION prefer requireNotNull over the double-bang operator"
        const val DOCS_TEXT = "DOCS-INSTRUCTION keep the docs in second person"
    }
}
