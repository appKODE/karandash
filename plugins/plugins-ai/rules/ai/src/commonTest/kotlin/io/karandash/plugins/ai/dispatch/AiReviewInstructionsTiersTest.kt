package io.karandash.plugins.ai.dispatch

import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.ReviewConfig
import io.karandash.plugins.ai.core.ReviewInstruction
import io.karandash.plugins.ai.core.ReviewStrictness
import io.karandash.plugins.ai.core.ScopeMatcher
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ai.review.instructions` are T0: an instruction whose paths match a changed file is sent whole or the rule is
 * skipped, never cut, whatever its length and the prompt cap; one that matches no changed file is never sent.
 */
class AiReviewInstructionsTiersTest {
    private val changes = listOf(change("src/main/A.kt"), change("spec/b_spec.rb"))

    private fun change(path: String) = FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, diffContent = "+a")

    @Test
    fun `ai review instructions default to none`() {
        assertTrue(
            AiPluginConfig
                .fromPluginConfig(JsonObject(emptyMap()))
                .review.instructions
                .isEmpty()
        )
    }

    @Test
    fun `ai review instructions are read from the ai block`() {
        val config = KarandashJson.Default.parseToJsonElement(
            """{"review": {"instructions": [{"name": "kt", "paths": ["src/**"], "text": "prefer val"}]}}""",
        )

        assertEquals(
            listOf(ReviewInstruction("kt", listOf("src/**"), "prefer val")),
            AiPluginConfig.fromPluginConfig(config).review.instructions
        )
    }

    @Test
    fun `an instruction matches a changed file its paths include`() {
        val matched = ScopeMatcher.matchInstructions(changes, listOf(ReviewInstruction("kt", listOf("src/**/*.kt"), "t")))

        assertEquals(listOf("kt"), matched.map { it.name })
    }

    @Test
    fun `an instruction does not match a changed file its paths exclude`() {
        val instruction = ReviewInstruction("rb", listOf("**/*.rb", "!spec/**"), "t")

        assertTrue(ScopeMatcher.matchInstructions(changes, listOf(instruction)).isEmpty())
    }

    @Test
    fun `an instruction with only excluding paths matches every other changed file`() {
        val instruction = ReviewInstruction("not-spec", listOf("!spec/**"), "t")

        assertEquals(listOf("not-spec"), ScopeMatcher.matchInstructions(changes, listOf(instruction)).map { it.name })
    }

    @Test
    fun `an instruction with only excluding paths does not match when they cover every changed file`() {
        val instruction = ReviewInstruction("none", listOf("!spec/**", "!src/**"), "t")

        assertTrue(ScopeMatcher.matchInstructions(changes, listOf(instruction)).isEmpty())
    }

    @Test
    fun `matched instructions are sent whole and unmatched ones never whatever the prompt cap`() =
        runTest {
            val random = Random(SEED)
            repeat(ROUNDS) { round ->
                val matched = List(random.nextInt(1, 4)) { instruction(random, "M$round-$it", "src/**") }
                val unmatched = List(random.nextInt(0, 3)) { instruction(random, "U$round-$it", "docs/**") }
                val extras = assemble((matched + unmatched).shuffled(random))
                val tiered = extras.plan("system", "", maxInputTokens = random.nextInt(50, 20_000), diffTokensWanted = 500)
                val sent = tiered.combinedExtra.orEmpty()

                matched.forEach {
                    assertTrue(tiered.skipReason != null || it.text in sent, "round $round: ${it.name} cut without a skip")
                    assertFalse(it.text.take(PREFIX) in sent && it.text !in sent, "round $round: ${it.name} truncated")
                }
                unmatched.forEach { assertFalse(it.text.take(PREFIX) in sent, "round $round: unmatched ${it.name} sent") }
            }
        }

    private fun instruction(
        random: Random,
        name: String,
        path: String,
    ): ReviewInstruction {
        val words = List(random.nextInt(1, 2_000)) { WORDS[random.nextInt(WORDS.size)] }
        return ReviewInstruction(name, listOf(path), "$name ${words.joinToString(" ")} END-$name")
    }

    private suspend fun assemble(instructions: List<ReviewInstruction>): AssembledExtras =
        AiPromptAssembler(buildTestRegistry(), NoOpLogger).assembleExtras(
            ruleId = "review-comments",
            mr = createTestMergeRequest(changes = changes),
            changes = changes,
            pluginCfg = AiPluginConfig(review = ReviewConfig(instructions)),
            aiRuleConfig = AiRuleConfig(),
            strictness = ReviewStrictness.STANDARD,
            feedbackStore = FeedbackStore(),
            needsScopeDetection = { false },
        )

    private companion object {
        const val SEED = 14
        const val ROUNDS = 40
        const val PREFIX = 12
        val WORDS = listOf("prefer", "val", "over", "var", "check", "nulls", "and", "naming")
    }
}
