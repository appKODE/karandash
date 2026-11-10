package io.karandash.contracts.ai

import io.karandash.contracts.ai.AiReviewHarness.Companion.A_KT_DIFF
import io.karandash.contracts.ai.AiReviewHarness.Companion.aKtChange
import io.karandash.contracts.ai.AiReviewHarness.Companion.defaultMr
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The extra-instructions block that `AiPromptAssembler.buildExtraInstructions`
 * assembles (scopes, cross-file context, strictness, responseLanguage, feedback, prior findings,
 * location constraint) and the enricher context blocks are discarded unless a custom prompt is
 * configured - analyzers receive only `pluginCfg.extraInstructions`. Each test drives the real
 * pipeline into a MockEngine LLM and asserts that one block reaches the HTTP request body.
 */
class AiPromptAssemblyContractTest {
    private suspend fun requestFor(aiBlockYaml: String): String {
        val harness = AiReviewHarness()
        harness.run(aiBlockYaml)
        assertTrue(harness.requestBodies.isNotEmpty(), "setup: no LLM request reached the MockEngine")
        return harness.allRequests
    }

    @Test
    fun `matched scope instructions reach the LLM request`() =
        runTest(timeout = 2.minutes) {
            val body = requestFor(
                """
                |  scopes:
                |    security:
                |      paths: ["src/**"]
                |      instructions: "SCOPE-MARKER check every input for injection"
                """.trimMargin(),
            )
            assertTrue(body.contains("SCOPE-MARKER"), "matched scope instructions missing from LLM request")
        }

    @Test
    fun `cross-file context reaches the LLM request`() =
        runTest(timeout = 2.minutes) {
            val body = requestFor("")
            assertTrue(body.contains("Cross-file context:"), "cross-file context block missing from LLM request")
        }

    @Test
    fun `strict strictness overlay reaches the LLM request`() =
        runTest(timeout = 2.minutes) {
            val body = requestFor("  strictness: strict")
            assertTrue(body.contains("Be thorough and strict."), "strictness=strict overlay missing from LLM request")
        }

    @Test
    fun `responseLanguage instruction reaches the LLM request`() =
        runTest(timeout = 2.minutes) {
            val body = requestFor("  responseLanguage: de")
            assertTrue(body.contains("the summary in German (de)"), "responseLanguage=de instruction missing from LLM request")
        }

    @Test
    fun `report language puts the output language instruction exactly once in every analyzer request`() =
        runTest(timeout = 2.minutes) {
            val harness = AiReviewHarness(extraReply = buildJsonObject { put("summary", "Adds a helper.") })
            harness.run("  mrSummary:\n    enabled: true", topLevelYaml = "report:\n  language: ru-RU")

            assertTrue(harness.requestBodies.size >= 2, "setup: review-comments and mr-summary both call the LLM")
            harness.requestBodies.forEach { body ->
                assertEquals(1, body.split("the summary in Russian (ru)").size - 1, body)
                assertTrue("is never a finding" in body, body)
            }
        }

    @Test
    fun `dismissed-feedback block reaches the LLM request`() =
        runTest(timeout = 2.minutes) {
            val entry = { rule: String ->
                """
                |    - fingerprint: fp-$rule
                |      rule: $rule
                |      filePath: src/A.kt
                |      messageHash: 42
                |      messagePrefix: FEEDBACK-MARKER avoid nitpicks
                |      dismissCount: 3
                |      firstDismissedAt: 1
                |      lastDismissedAt: 2
                """.trimMargin()
            }
            val body = requestFor(
                "  feedbackEnabled: true\n  feedback:\n" +
                    listOf("review-comments", "reviewComments", "ai-review").joinToString("\n") { entry(it) },
            )
            assertTrue(
                body.contains("## Previously Dismissed Findings"),
                "feedback (Previously Dismissed Findings) block missing from LLM request",
            )
        }

    @Test
    fun `prior findings block reaches the LLM request on an incremental re-run`() =
        runTest(timeout = 2.minutes) {
            val harness = AiReviewHarness()
            harness.run()
            val firstRunRequests = harness.requestBodies.size
            assertTrue(firstRunRequests > 0, "setup: no LLM request on the first run")
            val changedA = aKtChange("$A_KT_DIFF\n+fun added() = 1".replace("+5,10", "+5,11"))
            val untouched = FileChange("src/B.kt", ChangeType.MODIFIED, 1, 0, "@@ -1,1 +1,2 @@\n x\n+y")
            harness.run(mr = defaultMr(listOf(changedA, untouched)))
            val secondRun = harness.requestBodies.drop(firstRunRequests).joinToString("\n")
            assertTrue(secondRun.isNotEmpty(), "setup: no LLM request on the incremental re-run")
            assertTrue(
                secondRun.contains("## Previously Reported Findings"),
                "prior findings (Previously Reported Findings) block missing from the re-run LLM request",
            )
        }

    @Test
    fun `finding location constraint reaches the LLM request`() =
        runTest(timeout = 2.minutes) {
            val body = requestFor("")
            assertTrue(
                body.contains("FINDING LOCATION RULES:"),
                "FINDING_LOCATION_CONSTRAINT missing from LLM request (contextQuality defaults are on)",
            )
        }

    @Test
    fun `enricher context block reaches the LLM request`() =
        runTest(timeout = 2.minutes) {
            val withContent = aKtChange().copy(newContent = "package a\nfun full() = ENRICHER_FILE_MARKER\n")
            val harness = AiReviewHarness()
            harness.run(
                aiBlockYaml = "  contextQuality:\n    fullFileContext:\n      enabled: true",
                mr = defaultMr(listOf(withContent)),
            )
            assertTrue(harness.requestBodies.isNotEmpty(), "setup: no LLM request reached the MockEngine")
            assertTrue(
                // Request bodies are JSON, so the attribute quote arrives escaped.
                harness.allRequests.contains("<untrusted-content source=\\\"full-file-context\\\">"),
                "enricher context block (full-file context) missing from LLM request",
            )
        }
}
