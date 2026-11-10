package io.karandash.plugins.ai.dispatch

import io.karandash.logger.Logger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.ReviewStrictness
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiPromptAssemblerOrderingTest {
    private val noopLogger = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

    private fun bigChange(index: Int): FileChange {
        val diff = (1..40).joinToString("\n") { "+import com.example.module$index.sub$it.SomeVeryLongClassName$it" } +
            "\n" + (1..40).joinToString("\n") { "+fun someFairlyLongFunctionName${index}_$it(argument: String): Int" }
        return FileChange(path = "src/File$index.kt", changeType = ChangeType.MODIFIED, additions = 80, diffContent = diff)
    }

    private fun systemPrompt(changes: List<FileChange>): String {
        val ruleConfig = AiRuleConfig.fromPluginConfig(
            KarandashJson.Default.parseToJsonElement("""{"responseLanguage":"Russian"}"""),
        )
        val extra = AiPromptAssembler(buildTestRegistry(), noopLogger).buildExtraInstructions(
            ruleId = "review-comments",
            mr = createTestMergeRequest(changes = changes),
            pluginCfg = AiPluginConfig(crossFileContextMaxChars = 20_000),
            aiRuleConfig = ruleConfig,
            strictness = ReviewStrictness.STANDARD,
            feedbackStore = FeedbackStore(),
            needsScopeDetection = { false },
        )
        return AiRuleAnalyzerUtils.effectivePrompt(
            basePrompt = "Review the merge request.",
            extraInstructions = extra,
            responseFormatSuffix = """{"findings": []}""",
        )
    }

    @Test
    fun `a system prompt past the old 10k cap keeps every section`() {
        val prompt = systemPrompt((1..20).map { bigChange(it) })
        assertTrue(prompt.length > 10_000, "the prompt is past the removed cap: ${prompt.length}")
        assertFalse(prompt.contains("prompt truncated"))
        assertContains(prompt, "the summary in Russian (ru)")
        assertContains(prompt, "FINDING LOCATION RULES:")
    }

    @Test
    fun `cross file context still follows the language instruction when it fits`() {
        val prompt = systemPrompt(listOf(bigChange(1)))
        assertTrue(prompt.indexOf("the summary in Russian (ru)") < prompt.indexOf("Cross-file context:"))
        assertContains(prompt, "SomeVeryLongClassName1")
    }
}
