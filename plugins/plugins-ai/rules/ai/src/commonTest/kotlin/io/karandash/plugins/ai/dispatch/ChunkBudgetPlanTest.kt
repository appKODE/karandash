package io.karandash.plugins.ai.dispatch

import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.DiffChunk
import io.karandash.plugins.ai.core.PromptTokenCalibration
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.config.AiLargeMrConfig
import io.karandash.plugins.ai.core.config.ChunkingConfig
import io.karandash.plugins.ai.core.config.CostBudgetConfig
import io.karandash.plugins.ai.core.pipeline.ReviewSamplingConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.rules.CustomAiRule
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkBudgetPlanTest {
    private val diff = "+" + "x".repeat(DIFF_CHARS)

    @Test
    fun `review-comments is funded first then whole-MR rules and the ceiling leaves the rest unfunded`() {
        val shares = shares()
        val run = TokenBudget(maxTokens = 3 * SHARE)
        val extra = extra(AiRuleIds.REVIEW_COMMENTS, AiPluginConfig())
        val cfg = AiPluginConfig(costBudget = CostBudgetConfig(maxTokensPerRun = 3 * SHARE + extra + 100))
        val granted = ChunkBudget(buildTestRegistry()).plan(input(shares, run, cfg), NoOpLogger)

        assertEquals(mapOf(AiRuleIds.REVIEW_COMMENTS to extra, AiRuleIds.CHECKS to 100), granted)
        assertEquals(3 * SHARE + extra + 100, run.maxTokens)
        assertEquals(SHARE, shares.getValue(AiRuleIds.CODE_SUGGESTIONS).maxTokens)
    }

    @Test
    fun `scale-budget off leaves every share as configured`() {
        val shares = shares()
        val cfg = AiPluginConfig(largeMr = AiLargeMrConfig(scaleBudget = false))

        assertEquals(emptyMap(), ChunkBudget(buildTestRegistry()).plan(input(shares, TokenBudget(maxTokens = 3 * SHARE), cfg), NoOpLogger))
        assertEquals(SHARE, shares.getValue(AiRuleIds.REVIEW_COMMENTS).maxTokens)
    }

    @Test
    fun `without cost-budget every rule is funded in full`() {
        val run = TokenBudget(maxTokens = 3 * SHARE)
        val expected = shares().keys.associateWith { extra(it, AiPluginConfig()) }.filterValues { it > 0 }
        val granted = ChunkBudget(buildTestRegistry()).plan(input(shares(), run, AiPluginConfig()), recording)

        assertEquals(expected, granted)
        assertEquals(3 * SHARE + expected.values.sum(), run.maxTokens)
        assertTrue(infos.any { it.contains("${run.maxTokens} tokens, every rule funded in full") })
    }

    @Test
    fun `without cost-budget max-run-tokens caps the growth and warns`() {
        val run = TokenBudget(maxTokens = 3 * SHARE)
        val cfg = AiPluginConfig(largeMr = AiLargeMrConfig(maxRunTokens = 3 * SHARE + 50))

        assertEquals(mapOf(AiRuleIds.REVIEW_COMMENTS to 50), ChunkBudget(buildTestRegistry()).plan(input(shares(), run, cfg), recording))
        assertEquals(3 * SHARE + 50, run.maxTokens)
        assertEquals(1, warnings.count { it.startsWith("ai.large-mr.max-run-tokens:") })
        assertTrue(warnings.any { it.contains("funded: ") && it.contains("raise ai.large-mr.max-run-tokens to ") })
    }

    @Test
    fun `a cost-budget cap still bounds the growth`() {
        val run = TokenBudget(maxTokens = 3 * SHARE)
        val cfg = AiPluginConfig(costBudget = CostBudgetConfig(maxTokensPerRun = 3 * SHARE + 50))

        assertEquals(mapOf(AiRuleIds.REVIEW_COMMENTS to 50), ChunkBudget(buildTestRegistry()).plan(input(shares(), run, cfg), NoOpLogger))
        assertEquals(3 * SHARE + 50, run.maxTokens)
    }

    @Test
    fun `a ceiling below the run budget clamps the run`() {
        val run = TokenBudget(maxTokens = 3 * SHARE)
        val cfg = AiPluginConfig(costBudget = CostBudgetConfig(maxTokensPerRun = 2 * SHARE))

        assertEquals(emptyMap(), ChunkBudget(buildTestRegistry()).plan(input(shares(), run, cfg), recording))
        assertEquals(2 * SHARE, run.maxTokens)
        assertTrue(warnings.any { it.contains("cut to the ${2 * SHARE} tokens") })
    }

    @Test
    fun `a configured priority outranks the default order`() {
        val shares = shares()
        val priorities = mapOf("codeSuggestions" to 5)
        val extra = extra(AiRuleIds.CODE_SUGGESTIONS, AiPluginConfig(priorities = priorities))
        val cfg = AiPluginConfig(costBudget = CostBudgetConfig(maxTokensPerRun = 3 * SHARE + extra + 100), priorities = priorities)
        val granted = ChunkBudget(buildTestRegistry()).plan(input(shares, TokenBudget(maxTokens = 3 * SHARE), cfg), NoOpLogger)

        assertEquals(mapOf(AiRuleIds.CODE_SUGGESTIONS to extra, AiRuleIds.REVIEW_COMMENTS to 100), granted)
    }

    @Test
    fun `a custom rule priority outranks the default order`() {
        val shares = shares() + (CUSTOM to TokenBudget(maxTokens = SHARE))
        val custom = CustomAiRule(id = CUSTOM, name = "custom", prompt = "p", priority = 9)
        val extra = extra(CUSTOM, AiPluginConfig(), listOf(custom))
        val cfg = AiPluginConfig(costBudget = CostBudgetConfig(maxTokensPerRun = 4 * SHARE + extra + 100))
        val granted = ChunkBudget(buildTestRegistry()).plan(
            input(shares, TokenBudget(maxTokens = 4 * SHARE), cfg, listOf(custom)),
            NoOpLogger,
        )

        assertEquals(mapOf(CUSTOM to extra, AiRuleIds.REVIEW_COMMENTS to 100), granted)
    }

    @Test
    fun `a big diff goes in sweet-spot calls not share-sized slivers`() {
        val sizing = bigSizing(AiPluginConfig(contextBudget = 100_000))

        assertEquals(AiLargeMrConfig.DEFAULT_MAX_CALL_TOKENS, sizing.call)
        assertTrue(sizing.calls in 3..5, "calls ${sizing.calls}")
    }

    @Test
    fun `scale-budget off keeps share-sized calls`() {
        val sizing = bigSizing(AiPluginConfig(contextBudget = 100_000, largeMr = AiLargeMrConfig(scaleBudget = false)))

        assertEquals(BIG_SHARE, sizing.call)
        assertTrue(sizing.calls in 7..10, "calls ${sizing.calls}")
    }

    @Test
    fun `maxChunks caps the calls`() {
        val sizing =
            bigSizing(
                AiPluginConfig(contextBudget = 100_000, chunking = ChunkingConfig(chunkSize = 4_000, maxChunks = 2), reviewSampling = ONE)
            )

        assertEquals(2, sizing.calls)
        assertTrue(sizing.need <= 2L * sizing.call, "need ${sizing.need}")
        assertTrue(warnings.any { it.contains("max-chunks keeps 2; raise ai.chunking.max-chunks to ") })
    }

    @Test
    fun `a chunk is funded for what it sends not the whole call`() {
        val sizing = bigSizing(AiPluginConfig(contextBudget = 100_000, chunking = ChunkingConfig(chunkSize = 4_000), reviewSampling = ONE))

        assertTrue(sizing.need < sizing.calls.toLong() * sizing.call, "need ${sizing.need} calls ${sizing.calls}x${sizing.call}")
    }

    @Test
    fun `a chunk with chunk-context is funded for the whole context half`() {
        val sizing = bigSizing(AiPluginConfig(contextBudget = 100_000, chunking = ChunkingConfig(chunkSize = 4_000)))
        val perCall = sizing.t0 + sizing.reserve + (sizing.call - sizing.t0 - sizing.reserve) / 2

        assertTrue(sizing.need >= sizing.calls.toLong() * perCall, "need ${sizing.need} calls ${sizing.calls} each over $perCall")
    }

    @Test
    fun `chunk-size above the room is clamped and warned once`() {
        val cfg = AiPluginConfig(contextBudget = 100_000, chunking = ChunkingConfig(chunkSize = 1_000_000))
        val shares = linkedMapOf(AiRuleIds.REVIEW_COMMENTS to TokenBudget(maxTokens = BIG_SHARE))
        ChunkBudget(buildTestRegistry()).plan(bigInput(shares, cfg), recording)

        assertEquals(1, warnings.count { it.contains("ai.chunking.chunk-size 1000000 is over") })
    }

    @Test
    fun `chunk-size below the room is honored`() {
        val cfg = AiPluginConfig(chunking = ChunkingConfig(chunkSize = 1_500))

        assertEquals(1_500, ChunkBudget.chunkChars(cfg, AiRuleConfig(maxDiffCharsTotal = 100_000)))
    }

    @Test
    fun `a configured chunk size is capped by maxDiffCharsTotal`() {
        val mr = createTestMergeRequest(changes = listOf(change("A.kt"), change("B.kt")))
        val cfg = AiPluginConfig(chunking = ChunkingConfig(chunkSize = 1_000_000, overlapSize = 0))

        assertEquals(2, ChunkBudget.split(mr, cfg, AiRuleConfig(maxDiffCharsTotal = DIFF_CHARS + 100)).chunks.size)
        assertEquals(1, ChunkBudget.split(mr, cfg, AiRuleConfig(maxDiffCharsTotal = 100_000)).chunks.size)
    }

    @Test
    fun `a call never exceeds the context or max-call-tokens`() {
        val small = AiPluginConfig(largeMr = AiLargeMrConfig(maxCallTokens = 9_000))
        val off = AiPluginConfig(largeMr = AiLargeMrConfig(scaleBudget = false, maxCallTokens = 9_000))

        assertEquals(9_000, ChunkBudget.chunkCallCap(1_000, small))
        assertEquals(1_000, ChunkBudget.chunkCallCap(1_000, off))
        assertEquals(9_000, ChunkBudget.chunkCallCap(50_000, off))
        assertEquals(8_000, ChunkBudget.callSize(50_000, 8_000, 1_000, 2_000, false)?.tokens)
    }

    @Test
    fun `T0 over the share floors the call`() {
        val size = checkNotNull(ChunkBudget.callSize(1_000, 100_000, 3_000, 2_000, false))

        assertEquals(3_000 + 2_000 + ChunkBudget.MIN_DIFF_TOKENS, size.tokens)
        assertEquals(ChunkBudget.MIN_DIFF_TOKENS, size.diffTokens)
    }

    @Test
    fun `T0 plus reserve over the context skips the call`() {
        assertNull(ChunkBudget.callSize(10_000, 5_000, 3_000, 2_000, false))
    }

    @Test
    fun `chunk-context off gives the diff the whole room`() {
        assertEquals(7_000, ChunkBudget.callSize(10_000, 100_000, 1_000, 2_000, false)?.diffTokens)
        assertEquals(3_500, ChunkBudget.callSize(10_000, 100_000, 1_000, 2_000, true)?.diffTokens)
    }

    @Test
    fun `a non-ASCII diff gets fewer chars per chunk`() {
        val ascii = createTestMergeRequest(changes = listOf(change("A.kt")))
        val cyrillic = createTestMergeRequest(changes = listOf(change("A.kt").copy(diffContent = "+" + "ж".repeat(DIFF_CHARS))))

        assertTrue(ChunkBudget.charsOf(1_000, cyrillic) < ChunkBudget.charsOf(1_000, ascii))
    }

    @Test
    fun `a whole-MR rule wants at most its own model's context`() {
        val cfg = AiPluginConfig(contextBudget = 8_000)
        val calibration = PromptTokenCalibration().apply { record("small", 20, 10) }
        val shares = linkedMapOf(AiRuleIds.CHECKS to TokenBudget(maxTokens = SHARE))
        val budget = ChunkBudget(buildTestRegistry())
        val own = bigInput(shares, cfg)
        val small = ChunkBudget.Input(
            own.shares,
            own.run,
            own.mr,
            cfg,
            own.aiRuleConfig,
            "m",
            perRuleModels = mapOf(AiRuleIds.CHECKS to "small"),
            calibration = calibration,
        )

        assertEquals(8_000L, budget.need(AiRuleIds.CHECKS, shares.getValue(AiRuleIds.CHECKS), own, NoOpLogger))
        assertEquals(4_000L, budget.need(AiRuleIds.CHECKS, shares.getValue(AiRuleIds.CHECKS), small, NoOpLogger))
    }

    @Test
    fun `a whole-MR rule whose prompt and reserve overrun its context is not funded`() {
        val shares = linkedMapOf(AiRuleIds.CHECKS to TokenBudget(maxTokens = SHARE))
        val input = bigInput(shares, AiPluginConfig(contextBudget = 1_000))

        assertEquals(
            SHARE.toLong(),
            ChunkBudget(buildTestRegistry()).need(AiRuleIds.CHECKS, shares.getValue(AiRuleIds.CHECKS), input, NoOpLogger)
        )
    }

    @Test
    fun `an excluded file is not sized`() {
        val cfg = AiPluginConfig(contextBudget = 8_000)
        val code = bigInput(linkedMapOf(), cfg).mr.changes
        val svg = (1..10).map { change("res/i$it.svg").copy(diffContent = "+" + "<path/>".repeat(2_000)) }

        fun plan(changes: List<FileChange>): List<RuleSizing> {
            val shares = linkedMapOf(
                AiRuleIds.REVIEW_COMMENTS to TokenBudget(maxTokens = SHARE),
                AiRuleIds.CHECKS to TokenBudget(maxTokens = SHARE),
            )
            val input = bigInput(shares, cfg)
            val mr = createTestMergeRequest(changes = changes)
            return ChunkBudget(buildTestRegistry())
                .ledger(ChunkBudget.Input(shares, input.run, mr, cfg, input.aiRuleConfig, "m"), NoOpLogger)
                .rules
        }

        val withSvg = plan(code + svg)
        assertEquals(plan(code), withSvg)
        assertTrue(withSvg.all { it.grant > 0 }, "a rule went unfunded: $withSvg")
    }

    @Test
    fun `the chunk holding the biggest change is held first`() {
        val small = change("A.kt")
        val big = change("B.kt").copy(additions = 50)
        val chunks = listOf(DiffChunk(0, listOf(small), DIFF_CHARS), DiffChunk(1, listOf(big), DIFF_CHARS))
        val order = ChunkBudget.hotFirst(chunks, createTestMergeRequest(changes = listOf(small, big)), "r", AiRuleConfig(), null)

        assertEquals(listOf(1, 0), order)
    }

    private val warnings = mutableListOf<String>()
    private val infos = mutableListOf<String>()
    private val recording = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) {
            infos += message
        }

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    /** The tokens [rule] wants past its share on the two-file MR. */
    private fun extra(
        rule: String,
        cfg: AiPluginConfig,
        customRules: List<CustomAiRule> = emptyList(),
    ): Int {
        val shares = shares() + (CUSTOM to TokenBudget(maxTokens = SHARE))
        val input = input(shares, TokenBudget(maxTokens = SHARE), cfg, customRules)
        val need = ChunkBudget(buildTestRegistry()).need(rule, shares.getValue(rule), input, NoOpLogger)
        return (need - SHARE).toInt()
    }

    private fun bigSizing(cfg: AiPluginConfig): RuleSizing {
        val shares = linkedMapOf(AiRuleIds.REVIEW_COMMENTS to TokenBudget(maxTokens = BIG_SHARE))
        return ChunkBudget(buildTestRegistry()).ledger(bigInput(shares, cfg), recording).rules.single()
    }

    /** Ten files of 10k ASCII characters: about 25k diff tokens. */
    private fun bigInput(
        shares: Map<String, TokenBudget>,
        cfg: AiPluginConfig,
    ) = ChunkBudget.Input(
        shares = shares,
        run = TokenBudget(maxTokens = BIG_SHARE),
        mr = createTestMergeRequest(changes = (1..10).map { change("F$it.kt").copy(diffContent = "+" + "y".repeat(10_000)) }),
        pluginCfg = cfg,
        aiRuleConfig = AiRuleConfig(maxDiffCharsTotal = 1_000_000),
        model = "m",
    )

    private fun shares() =
        // Code-suggestions first: the plan ranks rules, it does not follow the map's order.
        linkedMapOf(
            AiRuleIds.CODE_SUGGESTIONS to TokenBudget(maxTokens = SHARE),
            AiRuleIds.CHECKS to TokenBudget(maxTokens = SHARE),
            AiRuleIds.REVIEW_COMMENTS to TokenBudget(maxTokens = SHARE),
        )

    private fun input(
        shares: Map<String, TokenBudget>,
        run: TokenBudget,
        cfg: AiPluginConfig,
        customRules: List<CustomAiRule> = emptyList(),
        aiRuleConfig: AiRuleConfig = AiRuleConfig(maxDiffCharsTotal = DIFF_CHARS),
    ) = ChunkBudget.Input(
        shares = shares,
        run = run,
        // Each file's diff exceeds the chunk size, so chunked rules see two chunks.
        mr = createTestMergeRequest(changes = listOf(change("A.kt"), change("B.kt"))),
        pluginCfg = cfg,
        aiRuleConfig = aiRuleConfig,
        model = "m",
        customRules = customRules,
    )

    private fun change(path: String) =
        FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = diff)

    private companion object {
        val ONE = ReviewSamplingConfig(samples = 1)
        const val SHARE = 1000
        const val DIFF_CHARS = 2000
        const val CUSTOM = "ai-custom"
        const val BIG_SHARE = 10_000
    }
}
