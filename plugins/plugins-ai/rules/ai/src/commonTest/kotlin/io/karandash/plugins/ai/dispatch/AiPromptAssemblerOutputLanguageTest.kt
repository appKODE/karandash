package io.karandash.plugins.ai.dispatch

import io.karandash.logger.NoOpLogger
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.PromptPart
import io.karandash.plugins.ai.core.ReviewStrictness
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A non-English `report.language` adds one T0 output-language instruction; English adds none. */
class AiPromptAssemblerOutputLanguageTest {
    @Test
    fun `a Russian report language adds the output language instruction as T0`() =
        runTest {
            val extras = assemble("ru")

            val part = extras.t0.single { it.name == PART }
            assertTrue("Russian (ru)" in part.text, part.text)
            assertTrue("reason in English" in part.text, part.text)
            assertTrue("never a finding" in part.text, part.text)
            assertTrue("verbatim" in part.text, part.text)
        }

    @Test
    fun `a region tag gets the instruction of its base language`() =
        runTest {
            assertTrue("Polish (pl)" in assemble("pl-PL").t0.single { it.name == PART }.text)
        }

    @Test
    fun `English adds no output language instruction`() =
        runTest {
            assertTrue(assemble(AiRuleConfig.DEFAULT_RESPONSE_LANGUAGE).parts.none { it.name == PART })
            assertTrue(assemble("en-GB").parts.none { it.name == PART })
        }

    @Test
    fun `the output language instruction survives a tiny budget exactly once`() =
        runTest {
            val language = assemble("ru").t0.single { it.name == PART }
            val extras = AssembledExtras(
                listOf(language, PromptPart("crossFileContext", "x".repeat(4_000), priority = 1)),
                t0Names = setOf(PART),
            )

            val tiered = extras.plan(
                systemPrompt = "",
                metadata = "",
                maxInputTokens = language.text.length / 4 + 20,
                diffTokensWanted = 10
            )

            assertNull(tiered.skipReason)
            assertEquals(listOf("crossFileContext"), tiered.contextDropped)
            val combined = checkNotNull(tiered.combinedExtra)
            assertEquals(1, combined.split(MARKER).size - 1, combined)
            assertTrue(tiered.contextBlocks.none { MARKER in it.content })
        }

    private suspend fun assemble(language: String): AssembledExtras {
        val mr = createTestMergeRequest()
        return AiPromptAssembler(buildTestRegistry(), NoOpLogger).assembleExtras(
            ruleId = "review-comments",
            mr = mr,
            changes = mr.changes,
            pluginCfg = AiPluginConfig(),
            aiRuleConfig = AiRuleConfig(responseLanguage = language),
            strictness = ReviewStrictness.STANDARD,
            feedbackStore = FeedbackStore(),
            needsScopeDetection = { false },
        )
    }

    private companion object {
        const val PART = "responseLanguage"
        const val MARKER = "reason in English"
    }
}
