package io.karandash.contracts.ai

import io.karandash.contracts.ai.AiReviewHarness.Companion.ANTHROPIC
import io.karandash.contracts.ai.AiReviewHarness.Companion.MODEL
import io.karandash.contracts.ai.AiReviewHarness.Companion.aiFindings
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Provider behaviour end to end (YAML -> ValidationPipeline -> real provider over a MockEngine):
 * Anthropic wire format on Claude 5.x, model fallback, response cache, consensus, Retry-After and cost logging.
 */
class AiProviderEndToEndContractTest {
    @Test
    fun `Anthropic on claude-sonnet-5-5 sends version header without temperature and reads text after thinking`() =
        runTest(timeout = 2.minutes) {
            val harness = AiReviewHarness(provider = ANTHROPIC, model = SONNET)
            val findings = aiFindings(harness.run())

            assertTrue(harness.requestBodies.isNotEmpty(), "setup: no LLM request reached the MockEngine")
            assertEquals("2023-06-01", harness.requestHeaders.first()["anthropic-version"])
            assertTrue(harness.requestBodies.none { "\"temperature\"" in it }, "temperature sent to a claude-*-5 model")
            assertTrue(findings.any { it.message.contains("Possible null dereference") }, "text block not parsed: $findings")
        }

    @Test
    fun `modelFallback answers with the next model after the primary fails`() =
        runTest(timeout = 2.minutes) {
            val harness = AiReviewHarness(
                failures = { _, model -> FakeFailure(HttpStatusCode.ServiceUnavailable).takeIf { model == MODEL } },
            )
            val findings = aiFindings(harness.run("  modelFallback:\n    - $BACKUP"))

            assertEquals(MODEL, harness.requestModels.first(), "the primary model must be tried first")
            assertTrue(BACKUP in harness.requestModels, "fallback model never called: ${harness.requestModels}")
            assertTrue(findings.isNotEmpty(), "fallback answer produced no findings")
            assertTrue(
                harness.logger.messages.any { it.startsWith("INFO: [ai] fallback: model $MODEL failed") },
                harness.logger.messages.toString()
            )
        }

    @Test
    fun `a repeated identical review is answered from the response cache`() =
        runTest(timeout = 2.minutes) {
            // No findings, so the second prompt carries no prior-findings block and matches the first byte for byte.
            val harness = AiReviewHarness(comments = emptyList())
            harness.run()
            val firstRunCalls = harness.requestBodies.size
            // A config change defeats the incremental (per-file) cache, so the rule runs again with the same prompt.
            harness.run("  failOnError: false")

            assertTrue(firstRunCalls > 0, "setup: first run made no LLM call")
            assertEquals(firstRunCalls, harness.requestBodies.size, "second run called the LLM again instead of the cache")
            assertTrue(harness.logger.messages.any { it.startsWith("INFO: [ai] cache hit:") }, harness.logger.messages.toString())
        }

    @Test
    fun `consensusModels with analyzerConsensus asks every model`() =
        runTest(timeout = 2.minutes) {
            val harness = AiReviewHarness()
            harness.run("  consensusModels:\n    - $CONSENSUS_A\n    - $CONSENSUS_B\n  analyzerConsensus: true")

            assertTrue(CONSENSUS_A in harness.requestModels, "consensus model A not called: ${harness.requestModels}")
            assertTrue(CONSENSUS_B in harness.requestModels, "consensus model B not called: ${harness.requestModels}")
        }

    @Test
    fun `a 429 is retried after the server Retry-After`() =
        runTest(timeout = 2.minutes) {
            // the wait is the larger of the 1s backoff and the server value (CHANGELOG), so the server asks for more than 1s
            val harness = AiReviewHarness(
                maxRetries = 1,
                failures = {
                    call,
                    _
                    ->
                    FakeFailure(HttpStatusCode.TooManyRequests, headersOf("retry-after-ms", "1200")).takeIf { call == 0 }
                },
            )
            val findings = aiFindings(harness.run())

            assertTrue(findings.isNotEmpty(), "retry after 429 produced no findings")
            assertTrue(
                harness.logger.messages.any { it.startsWith("WARN:") && it.endsWith("retrying in 1200ms") },
                harness.logger.messages.toString()
            )
        }

    @Test
    fun `a priced model logs a non-zero cost per rule`() =
        runTest(timeout = 2.minutes) {
            val harness = AiReviewHarness(provider = ANTHROPIC, model = SONNET)
            harness.run()

            val cost = harness.logger.messages.firstOrNull { it.startsWith("INFO: [ai] rule=") && "model=$SONNET" in it }
            assertTrue(cost != null, "no per-rule cost line: ${harness.logger.messages}")
            assertFalse(cost.endsWith("cost=\$0.0000"), cost)
        }

    private companion object {
        const val SONNET = "claude-sonnet-5-5"
        const val BACKUP = "contract-backup-model"
        const val CONSENSUS_A = "consensus-model-a"
        const val CONSENSUS_B = "consensus-model-b"
    }
}
