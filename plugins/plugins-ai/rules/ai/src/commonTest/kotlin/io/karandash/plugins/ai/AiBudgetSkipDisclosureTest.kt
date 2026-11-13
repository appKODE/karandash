package io.karandash.plugins.ai

import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.finding.ReviewCoverage
import io.karandash.api.module.RuleModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.config.keys.ModuleIds
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Rules and chunks an analyzer did not review are disclosed as skipped, never shown as a clean run. */
class AiBudgetSkipDisclosureTest {
    /** One share for every chunk, so the budget skips chunks instead of growing per chunk. */
    private val fixedShare = mapOf("largeMr" to mapOf("scaleBudget" to false))

    private fun context(extra: Map<String, Any> = emptyMap()): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(
                mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "maxTokenBudget" to BUDGET,
                    // One file fits a chunk, two do not, so every file becomes its own chunk.
                    "limits" to mapOf("maxDiffCharsTotal" to DIFF.length + 5),
                ) + extra,
            )

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    @Test
    fun `budget skipped rule is reported as skipped not as no findings`() =
        runTest {
            // Like every built-in analyzer: spend the estimate when it fits, otherwise return nothing.
            val analyzer = TestAnalyzer(RULE) {
                if (it.budget.canSpend(ESTIMATE)) it.budget.recordSpend(ESTIMATE)
                emptyList()
            }
            val module = initializedModule(listOf(analyzer), context(fixedShare))
            // Two chunks; the budget covers one.
            module.validate(RULE, createTestMergeRequest(changes = listOf(change("A.kt"), change("B.kt"))), JsonObject(emptyMap()))

            val summary = costSummary(module)
            assertTrue("skipped 1 of 2 chunks (not reviewed)" in ruleLine(summary, RULE), summary)
            val coverage = assertNotNull(module.reviewProvenance()?.coverage, "no coverage recorded")
            assertEquals(ReviewCoverage(totalFiles = 2, reviewedFiles = 1, chunksSkipped = 1), coverage)
            module.shutdown()
        }

    @Test
    fun `analyzer self skip is disclosed while a quiet rule stays no findings`() =
        runTest {
            val declining = TestAnalyzer(DECLINING, SMALL_ESTIMATE) { it.skip("no input data") }
            val quiet = TestAnalyzer(QUIET, SMALL_ESTIMATE) { emptyList() }
            val module = initializedModule(listOf(declining, quiet))
            val mr = createTestMergeRequest(changes = listOf(change("A.kt")))

            module.validate(DECLINING, mr, JsonObject(emptyMap()))
            module.validate(QUIET, mr, JsonObject(emptyMap()))

            val summary = costSummary(module)
            assertEquals("  $DECLINING: skipped (no input data)", ruleLine(summary, DECLINING), summary)
            assertTrue("skipped" !in ruleLine(summary, QUIET), summary)
            assertEquals(setOf(DECLINING), module.skippedRuleReasons().keys)
            module.shutdown()
        }

    @Test
    fun `chunks the analyzer self skips count as skipped`() =
        runTest {
            val declining = TestAnalyzer(DECLINING, SMALL_ESTIMATE) { it.skip("no input data") }
            val module = initializedModule(listOf(declining))

            module.validate(DECLINING, createTestMergeRequest(changes = listOf(change("A.kt"), change("B.kt"))), JsonObject(emptyMap()))

            val coverage = assertNotNull(module.reviewProvenance()?.coverage, "no coverage recorded")
            assertEquals(2, coverage.chunksSkipped)
            assertEquals(0, coverage.reviewedFiles)
            assertEquals("no input data", module.skippedRuleReasons().getValue(DECLINING).reason)
            module.shutdown()
        }

    @Test
    fun `one self skipped chunk of two counts once and the rule still ran`() =
        runTest {
            var calls = 0
            val partial = TestAnalyzer(QUIET, SMALL_ESTIMATE) {
                if (calls++ == 0) it.skip("first chunk unusable")
                emptyList()
            }
            val module = initializedModule(listOf(partial))

            module.validate(QUIET, createTestMergeRequest(changes = listOf(change("A.kt"), change("B.kt"))), JsonObject(emptyMap()))

            val coverage = assertNotNull(module.reviewProvenance()?.coverage, "no coverage recorded")
            assertEquals(1, coverage.chunksSkipped)
            assertEquals(1, coverage.reviewedFiles)
            assertTrue(module.skippedRuleReasons().isEmpty(), module.skippedRuleReasons().toString())
            module.shutdown()
        }

    @Test
    fun `a rule whose T0 prompt is over the input token cap is skipped naming the item`() =
        runTest {
            var calls = 0
            val analyzer = TestAnalyzer(QUIET, SMALL_ESTIMATE) {
                calls++
                emptyList()
            }
            val module = initializedModule(listOf(analyzer), context(mapOf("budget" to mapOf("maxInputTokens" to 10))))

            module.validate(QUIET, createTestMergeRequest(changes = listOf(change("A.kt"))), JsonObject(emptyMap()))

            assertEquals(0, calls, "a T0 overflow never reaches the model")
            val reason = module.skippedRuleReasons().getValue(QUIET).reason
            assertTrue(reason.startsWith("T0 over budget: ") && "of ai.context.budget 10 " in reason, reason)
            module.shutdown()
        }

    @Test
    fun `the output reserve comes off the input cap before T0 is planned`() =
        runTest {
            var calls = 0
            val analyzer = TestAnalyzer(QUIET, SMALL_ESTIMATE) {
                calls++
                emptyList()
            }
            val budget = mapOf("maxInputTokens" to 100_000, "reserveOutputTokens" to 99_990)
            val module = initializedModule(listOf(analyzer), context(mapOf("budget" to budget)))

            module.validate(QUIET, createTestMergeRequest(changes = listOf(change("A.kt"))), JsonObject(emptyMap()))

            assertEquals(0, calls, "10 tokens left after the reserve cannot hold T0")
            val reason = module.skippedRuleReasons().getValue(QUIET).reason
            assertTrue(reason.endsWith("of ai.context.budget 100000 less reserve-output-tokens 99990"), reason)
            module.shutdown()
        }

    @Test
    fun `a share grown for a large MR still sends the diff in sweet-spot chunks`() =
        runTest {
            var calls = 0
            val analyzer = TestAnalyzer(QUIET, SMALL_ESTIMATE) {
                calls++
                emptyList()
            }
            val grown = mapOf("costBudget" to mapOf("maxTokensPerRun" to 100_000), "limits" to emptyMap<String, Any>())
            val module = initializedModule(listOf(analyzer), context(grown))
            // 24 files of about 1500 tokens each: over two 16k calls in all.
            val big = "@@ -1,1 +1,1 @@\n" + "+${"x".repeat(LINE)}\n".repeat(LINES)
            val changes = (1..24).map { change("F$it.kt").copy(diffContent = big) }

            module.validate(QUIET, createTestMergeRequest(changes = changes), JsonObject(emptyMap()))

            assertTrue(calls > 1, "the grown share went out in $calls call")
            module.shutdown()
        }

    @Test
    fun `a chunk that does not fit its call is skipped and disclosed never cut`() =
        runTest {
            var calls = 0
            val analyzer = TestAnalyzer(QUIET, SMALL_ESTIMATE) {
                calls++
                emptyList()
            }
            val module = initializedModule(listOf(analyzer), context(mapOf("limits" to mapOf("maxDiffCharsTotal" to DIFF.length - 1))))

            module.validate(QUIET, createTestMergeRequest(changes = listOf(change("A.kt"), change("B.kt"))), JsonObject(emptyMap()))

            assertEquals(0, calls, "a chunk over its call is never sent cut")
            val coverage = assertNotNull(module.reviewProvenance()?.coverage, "no coverage recorded")
            assertEquals(ReviewCoverage(totalFiles = 2, reviewedFiles = 0, chunksSkipped = 2), coverage)
            module.shutdown()
        }

    @Test
    fun `a whole-MR rule over its context discloses the hunks cut`() =
        runTest {
            val analyzer = TestAnalyzer(QUIET, SMALL_ESTIMATE, mrLevel = true) { emptyList() }
            val cfg = mapOf("budget" to mapOf("maxInputTokens" to 2_900), "limits" to mapOf("maxDiffCharsTotal" to 100_000))
            val module = initializedModule(listOf(analyzer), context(cfg))
            val hunks = (1..40).joinToString("\n") { "@@ -$it,1 +$it,1 @@\n-old line $it\n+new line ${"x".repeat(LINE)}" }

            module.validate(
                QUIET,
                createTestMergeRequest(changes = listOf(change("A.kt").copy(diffContent = hunks))),
                JsonObject(emptyMap())
            )

            val line = assertNotNull(
                module
                    .reviewProvenance()
                    ?.analyzers
                    ?.single { it.ruleId == QUIET }
                    ?.context,
                "no context line"
            )
            assertTrue(Regex("[1-9][0-9]* hunks cut").containsMatchIn(line), line)
            module.shutdown()
        }

    @Test
    fun `a file cut by the running diff total is reported`() =
        runTest {
            val analyzer = TestAnalyzer(QUIET, SMALL_ESTIMATE, mrLevel = true) { emptyList() }
            val module =
                initializedModule(listOf(analyzer), context(mapOf("limits" to mapOf("maxDiffCharsTotal" to DIFF.length + 2 * HUNK.length))))
            // B.kt has three hunks; the total leaves it room for one or two of them only.
            val threeHunks = change("B.kt").copy(diffContent = List(3) { HUNK }.joinToString("\n"))

            module.validate(QUIET, createTestMergeRequest(changes = listOf(change("A.kt"), threeHunks)), JsonObject(emptyMap()))

            val coverage = assertNotNull(module.reviewProvenance()?.coverage, "no coverage recorded")
            assertEquals(1, coverage.truncatedFiles, coverage.toString())
            module.shutdown()
        }

    @Test
    fun `an MR with no diff files reports no coverage`() =
        runTest {
            val quiet = TestAnalyzer(QUIET, SMALL_ESTIMATE) { emptyList() }
            val module = initializedModule(listOf(quiet))
            val noDiff = FileChange("Big.bin", ChangeType.MODIFIED, additions = 0, deletions = 0, diffContent = null)

            module.validate(QUIET, createTestMergeRequest(changes = emptyList()), JsonObject(emptyMap()))
            module.validate(QUIET, createTestMergeRequest(changes = listOf(noDiff)), JsonObject(emptyMap()))

            assertEquals(null, module.reviewProvenance()?.coverage)
            module.shutdown()
        }

    @Test
    fun `the summary names a budget skipped unit and test gaps without a location`() =
        runTest {
            val analyzer = TestAnalyzer(RULE) {
                if (it.budget.canSpend(ESTIMATE)) it.budget.recordSpend(ESTIMATE)
                emptyList()
            }
            val module = initializedModule(listOf(analyzer), context(fixedShare))
            val mr = createTestMergeRequest(changes = listOf(change("app/A.kt"), change("net/B.kt")))
            module.validate(RULE, mr, JsonObject(emptyMap()))

            val added = AiPipelineExtension(serving(module)).beforeReporting(emptyList(), mr)

            assertEquals(
                listOf(
                    "Large MR: 2 review units: 1 reviewed, 1 not reviewed, 0 excluded or unchanged",
                    "Test gap: 2 unit(s) change 2 production files and no test",
                ),
                added.map { it.message },
            )
            assertTrue(added.all { it.location == null })
            module.shutdown()
        }

    private suspend fun initializedModule(
        analyzers: List<AiAnalyzerModule>,
        context: PluginContext = context(),
    ): AiRuleModule {
        val base = object : ModuleRegistry by buildTestRegistry() {
            override fun aiAnalyzers(): List<AiAnalyzerModule> = analyzers

            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzers.find { it.analyzerId == analyzerId }
        }
        val module = AiRuleModule(base)
        assertTrue(module.initialize(context))
        return module
    }

    private fun serving(module: AiRuleModule) =
        object : ModuleRegistry by buildTestRegistry() {
            override fun get(id: String): Module? = if (id == ModuleIds.AI_ANALYSIS) module else null

            override fun ruleModules(): List<RuleModule> = listOf(module)
        }

    private fun costSummary(module: AiRuleModule): String = checkNotNull(AiPipelineExtension(serving(module)).costSummary())

    private fun ruleLine(
        summary: String,
        ruleId: String,
    ): String = summary.lines().single { it.startsWith("  $ruleId:") }

    private fun change(path: String) = FileChange(path, ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = DIFF)

    private class TestAnalyzer(
        override val analyzerId: String,
        private val estimate: Int = ESTIMATE,
        override val mrLevel: Boolean = false,
        private val body: suspend (AiAnalyzerContext) -> List<Finding>,
    ) : AiAnalyzerModule {
        override val id: String = analyzerId
        override val name: String = analyzerId
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = analyzerId
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = estimate

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> = body(context)
    }

    private companion object {
        const val RULE = "review"
        const val DECLINING = "declining"
        const val QUIET = "quiet"
        const val ESTIMATE = 3000
        const val SMALL_ESTIMATE = 100
        const val BUDGET = 5000
        const val LINE = 99
        const val LINES = 60
        const val DIFF = "@@ -1,1 +1,1 @@\n-old line\n+new line"
        val HUNK = "@@ -1,1 +1,1 @@\n-old line\n+${"x".repeat(LINE)}"
    }
}
