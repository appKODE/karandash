package io.karandash.plugins.ai.dispatch

import io.karandash.logger.Logger
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.ReviewStrictness
import io.karandash.plugins.ai.core.config.AiPipelineConfig
import io.karandash.plugins.ai.core.config.AiPriorFindingsConfig
import io.karandash.plugins.ai.core.incremental.IncrementalContext
import io.karandash.plugins.ai.core.rules.AiContextQualityConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.FindingValidatorConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiPromptAssemblerPriorFindingsTest {
    private val noopLogger = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

    private fun makeMr() =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test MR",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = User("1", "dev", "Dev"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = listOf(Commit("sha1", "init", CommitAuthor("Dev", "dev@test.com"), "2024-01-01T00:00:00Z")),
            changes = emptyList(),
        )

    private fun reviewFinding(
        path: String,
        line: Int,
        message: String = "Prior issue on $path:$line",
    ) = Finding(
        severity = Severity.WARNING,
        message = message,
        rule = "review-comments",
        location = Location.FileRef(path = path, line = line),
    )

    private fun assembler() = AiPromptAssembler(buildTestRegistry(), noopLogger)

    @Test
    fun `prior findings block appears when feature enabled and prior inputs present`() {
        val block = assembler().buildExtraInstructions(
            ruleId = "review-comments",
            mr = makeMr(),
            pluginCfg = AiPluginConfig(),
            aiRuleConfig = AiRuleConfig(),
            strictness = ReviewStrictness.STANDARD,
            feedbackStore = FeedbackStore(),
            priorFindingsByChangedFile = mapOf(
                "src/Foo.kt" to listOf(reviewFinding("src/Foo.kt", line = 10)),
            ),
            needsScopeDetection = { false },
        )
        assertNotNull(block)
        assertTrue(block.contains("Previously Reported Findings"))
        assertTrue(block.contains("### src/Foo.kt"))
        assertTrue(block.contains("line 10 [WARNING]"))
    }

    @Test
    fun `prior findings block omitted when feature disabled`() {
        val config = AiPluginConfig(
            pipeline = AiPipelineConfig(priorFindings = AiPriorFindingsConfig(enabled = false)),
        )
        val block = assembler().buildExtraInstructions(
            ruleId = "review-comments",
            mr = makeMr(),
            pluginCfg = config,
            aiRuleConfig = AiRuleConfig(),
            strictness = ReviewStrictness.STANDARD,
            feedbackStore = FeedbackStore(),
            priorFindingsByChangedFile = mapOf(
                "src/Foo.kt" to listOf(reviewFinding("src/Foo.kt", line = 10)),
            ),
            needsScopeDetection = { false },
        )
        // Block may be null entirely (no other content contributed by empty config) or non-null but
        // without the prior-findings header. Assert absence of the header either way.
        assertFalse(block.orEmpty().contains("Previously Reported Findings"))
    }

    @Test
    fun `dismissed prior findings are filtered out before the enricher renders`() {
        val feedbackStore = FeedbackStore()
        val dismissed = reviewFinding("src/Foo.kt", line = 10, message = "Dismissed issue")
        val kept = reviewFinding("src/Foo.kt", line = 20, message = "Still valid issue")
        feedbackStore.dismiss(
            rule = dismissed.rule,
            filePath = (dismissed.location as Location.FileRef).path,
            messageHash = dismissed.message.hashCode(),
        )

        val block = assembler().buildExtraInstructions(
            ruleId = "review-comments",
            mr = makeMr(),
            pluginCfg = AiPluginConfig(),
            aiRuleConfig = AiRuleConfig(),
            strictness = ReviewStrictness.STANDARD,
            feedbackStore = feedbackStore,
            priorFindingsByChangedFile = mapOf("src/Foo.kt" to listOf(dismissed, kept)),
            needsScopeDetection = { false },
        )
        assertNotNull(block)
        assertTrue(block.contains("Still valid issue"))
        assertFalse(block.contains("Dismissed issue"))
    }

    @Test
    fun `null returned when no content contributed`() {
        // finding-validator is on by default and always contributes the FINDING LOCATION RULES
        // block. Disable it so this test can observe the "nothing else contributed → null" case.
        val aiRuleConfig = AiRuleConfig(
            contextQuality = AiContextQualityConfig(
                findingValidator = FindingValidatorConfig(enabled = false),
            ),
        )
        val block = assembler().buildExtraInstructions(
            ruleId = "review-comments",
            mr = makeMr(),
            pluginCfg = AiPluginConfig(),
            aiRuleConfig = aiRuleConfig,
            strictness = ReviewStrictness.STANDARD,
            feedbackStore = FeedbackStore(),
            needsScopeDetection = { false },
        )
        assertNull(block)
    }

    private suspend fun instructionsFor(configChanged: Boolean): String {
        val context = IncrementalContext(
            mrKey = "mr",
            deltaMr = makeMr(),
            unchangedFiles = emptyList(),
            cachedFindings = emptyList(),
            hasCacheEntry = false,
            currentSnapshot = emptyMap(),
            priorFindingsByChangedFile = mapOf(
                "src/Foo.kt" to listOf(reviewFinding("src/Foo.kt", line = 10, message = "Prior file issue"))
            ),
            priorMrLevelFindings = listOf(reviewFinding("src/Foo.kt", line = 1, message = "Prior MR issue").copy(location = null)),
            configChanged = configChanged,
        )
        return assembler()
            .buildExtraInstructionsWithContext(
                ruleId = "review-comments",
                mr = makeMr(),
                changes = emptyList(),
                pluginCfg = AiPluginConfig(),
                aiRuleConfig = AiRuleConfig(),
                strictness = ReviewStrictness.STANDARD,
                feedbackStore = FeedbackStore(),
                incrementalContext = context,
                needsScopeDetection = { false },
            ).orEmpty()
    }

    @Test
    fun `prior findings stay out of the do-not-repeat block after a config change`() =
        runTest {
            val block = instructionsFor(configChanged = true)
            assertFalse(block.contains("Previously Reported Findings"), block)
            assertFalse(block.contains("Prior file issue"), block)
            assertFalse(block.contains("Prior MR issue"), block)
        }

    @Test
    fun `prior findings are in the do-not-repeat block when config is unchanged`() =
        runTest {
            val block = instructionsFor(configChanged = false)
            assertTrue(block.contains("Previously Reported Findings"), block)
            assertTrue(block.contains("Prior file issue"), block)
            assertTrue(block.contains("Prior MR issue"), block)
        }

    private suspend fun largeDiffPlan(): TieredExtras {
        val context = IncrementalContext(
            mrKey = "mr",
            deltaMr = makeMr(),
            unchangedFiles = emptyList(),
            cachedFindings = emptyList(),
            hasCacheEntry = false,
            currentSnapshot = emptyMap(),
            priorFindingsByChangedFile = mapOf(
                "src/Foo.kt" to listOf(reviewFinding("src/Foo.kt", line = 10, message = "Prior file issue"))
            ),
            priorMrLevelFindings = emptyList(),
            configChanged = false,
        )
        val extras = assembler().assembleExtras(
            ruleId = "review-comments",
            mr = makeMr(),
            changes = emptyList(),
            pluginCfg = AiPluginConfig(),
            aiRuleConfig = AiRuleConfig(),
            strictness = ReviewStrictness.STANDARD,
            feedbackStore = FeedbackStore(),
            enricherContext = mapOf("note" to "n".repeat(400)),
            incrementalContext = context,
            needsScopeDetection = { false },
        )
        return extras.plan(systemPrompt = "system", metadata = "meta", maxInputTokens = 2_000, diffTokensWanted = 100_000)
    }

    @Test
    fun `a large diff keeps the prior findings block`() =
        runTest {
            val plan = largeDiffPlan()
            assertTrue(plan.combinedExtra.orEmpty().contains("Prior file issue"), plan.toString())
            assertFalse("priorFindings" in plan.contextDropped, plan.toString())
        }

    @Test
    fun `a large diff still drops T2 enricher context`() =
        runTest {
            val plan = largeDiffPlan()
            assertTrue("enricherContext" in plan.contextDropped, plan.toString())
        }
}
