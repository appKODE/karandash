package io.karandash.plugins.ai.dispatch

import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.ContextBudget
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.plugins.ai.rules.CustomAiRule
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A synthetic 150-file, 50k-line MR: bounded wall time and tokens, and every file reviewed or listed. */
class AiLargeMrBenchmarkTest {
    private class TokenProvider : AiProvider {
        override val providerId = "fake"
        var calls = 0

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse {
            calls++
            delay(CALL_MS)
            return AiResponse("{}", prompt.length / CHARS_PER_TOKEN, 0, prompt.length / CHARS_PER_TOKEN, model)
        }

        override suspend fun close() = Unit
    }

    private fun change(path: String): FileChange {
        val body = (1..LINES_PER_FILE).joinToString("\n") { "+val line$it = compute($it) // $path" }
        return FileChange(path, ChangeType.MODIFIED, additions = LINES_PER_FILE, diffContent = "@@ -0,0 +1,$LINES_PER_FILE @@\n$body")
    }

    private fun syntheticMr() =
        createTestMergeRequest(
            changes = (0 until UNITS)
                .flatMap { unit ->
                    (0 until SOURCES_PER_UNIT).map { "module$unit/src/main/kotlin/S$it.kt" } +
                        "module$unit/src/test/kotlin/S0Test.kt"
                }.take(FILES - EXCLUDED.size)
                .map(::change) + EXCLUDED.map(::change),
        )

    @Test
    fun `a 150 file 50k line MR finishes in bounded time and tokens with every file accounted for`() =
        runTest {
            val mr = syntheticMr()
            assertEquals(FILES, mr.changes.size)
            assertTrue(mr.changes.sumOf { it.additions } >= TOTAL_LINES)
            val provider = TokenProvider()
            val budget = TokenBudget(maxTokens = BUDGET)
            val started = TimeSource.Monotonic.markNow()

            val result = AiRuleDispatcher(buildTestRegistry(), NoOpLogger).dispatch(
                AiRuleDispatcher.DispatchInput(
                    ruleId = RULE_ID,
                    mr = mr,
                    concurrencySemaphore = Semaphore(PERMITS),
                    aiProvider = provider,
                    budget = budget,
                    model = "m",
                    config = JsonObject(emptyMap()),
                    resolvedPrompt = null,
                    aiService = null,
                    aiRuleConfig = AiRuleConfig(),
                    pluginCfg = AiPluginConfig(),
                    contextProcessors = emptyList(),
                    accumulatedFindings = emptyList(),
                    customRules = listOf(CustomAiRule(id = RULE_ID, name = "Bench", prompt = "Check it")),
                ),
            )

            val elapsed = started.elapsedNow()
            val coverage = checkNotNull(result.coverage)
            val listed = coverage.reviewed + coverage.dropped.map { it.substringBefore(" (") } +
                coverage.excluded.map { it.substringBefore(" (") }
            val report =
                "calls=${provider.calls} wall=$elapsed virtualMs=$currentTime spent=${budget.totalConsumed()} " +
                    "reviewed=${coverage.reviewed.size} " +
                    "dropped=${coverage.dropped.size} excluded=${coverage.excluded.size}"
            assertEquals(mr.changes.map { it.path }.toSet(), listed.toSet(), report)
            // The chunk limit bounds the calls however big the MR is; the files past it are listed as dropped.
            assertEquals(AiRuleConfig.DEFAULT_MAX_CHUNKS, provider.calls, report)
            assertEquals(FILES - coverage.reviewed.size, coverage.dropped.size, report)
            // max-parallel 4: the calls go out four at a time, not one after another.
            assertEquals((provider.calls + MAX_PARALLEL - 1) / MAX_PARALLEL * CALL_MS, currentTime, report)
            assertTrue(elapsed < MAX_WALL, report)
            // a custom rule sends its chunk's capped diff plus the file list; the auto context budget bounds each call.
            assertTrue(budget.totalConsumed() <= provider.calls * (ContextBudget.AUTO_MAX_TOKENS + FILE_LIST_TOKENS), report)
        }

    private companion object {
        const val RULE_ID = "bench-rule"
        const val FILES = 150
        const val UNITS = 30
        const val SOURCES_PER_UNIT = 4
        const val LINES_PER_FILE = 334
        const val TOTAL_LINES = 50_000
        const val BUDGET = 2_000_000
        const val PERMITS = 8
        const val MAX_PARALLEL = 4
        const val CALL_MS = 100L
        const val CHARS_PER_TOKEN = 4
        const val FILE_LIST_TOKENS = 1_000
        val MAX_WALL = 10.seconds
        val EXCLUDED = listOf("package-lock.json", "vendor/lib/x.go", "web/node_modules/a/index.js", "build/generated/Api.kt", "Cargo.lock")
    }
}
