package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.logger.Logger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.ReviewStrictness
import io.karandash.plugins.ai.core.context.RelatedFileContextEnricher
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.time.Clock

class AiPromptAssemblerRelatedFilesTest {
    private val noopLogger = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

    private fun contextAt(dir: String): PluginContext =
        object : PluginContext {
            override val pluginConfig = KarandashJson.Default.parseToJsonElement("{}")
            override val logger: io.karandash.sdk.Logger = io.karandash.sdk.NoOpLogger
            override val hostVersion = "test"
            override val workingDirectory = dir

            override fun env(name: String): String? = null
        }

    private val changes = listOf(FileChange(path = "src/Auth.kt", changeType = ChangeType.MODIFIED, additions = 1))

    private suspend fun promptFor(
        ruleId: String,
        aiConfigJson: String,
    ): String {
        val dir = TestFiles.createTestTempDirectory("karandash-related-files")
        SystemFileSystem.createDirectories(Path(dir, "src"))
        val testFile = Path(dir, "src/AuthTest.kt")
        SystemFileSystem.sink(testFile).buffered().use { it.writeString("class AuthTest") }
        try {
            val enricher = RelatedFileContextEnricher()
            enricher.initialize(contextAt(dir.toString()))
            val base = buildTestRegistry()
            val registry = object : ModuleRegistry by base {
                override fun aiContextEnrichers(): List<AiContextEnricherModule> = listOf(enricher)
            }
            val pluginConfig = KarandashJson.Default.parseToJsonElement(aiConfigJson)
            return AiPromptAssembler(registry, noopLogger)
                .buildExtraInstructionsWithContext(
                    ruleId = ruleId,
                    mr = createTestMergeRequest(changes = changes),
                    changes = changes,
                    pluginCfg = AiPluginConfig(),
                    aiRuleConfig = AiRuleConfig.fromPluginConfig(pluginConfig),
                    strictness = ReviewStrictness.STANDARD,
                    feedbackStore = FeedbackStore(),
                    enricherConfigJson = pluginConfig,
                    needsScopeDetection = { false },
                ).orEmpty()
        } finally {
            SystemFileSystem.delete(testFile, mustExist = false)
            SystemFileSystem.delete(Path(dir, "src"), mustExist = false)
            SystemFileSystem.delete(dir, mustExist = false)
        }
    }

    @Test
    fun `related files key turns on the related test files block`() =
        runTest {
            val prompt = promptFor("review-comments", """{"contextQuality":{"relatedFiles":{"enabled":true}}}""")
            assertContains(prompt, RelatedFileContextEnricher.BLOCK_HEADING)
            assertContains(prompt, "src/Auth.kt → src/AuthTest.kt")
        }

    @Test
    fun `per rule related files override turns the block off`() =
        runTest {
            val config = """{"contextQuality":{"relatedFiles":{"enabled":true},""" +
                """"ruleContext":{"review-comments":{"relatedFiles":{"enabled":false}}}}}"""
            assertFalse(RelatedFileContextEnricher.BLOCK_HEADING in promptFor("review-comments", config))
            assertContains(promptFor("other-rule", config), RelatedFileContextEnricher.BLOCK_HEADING)
        }

    @Test
    fun `related test files block is off by default`() =
        runTest {
            assertFalse(RelatedFileContextEnricher.BLOCK_HEADING in promptFor("review-comments", """{}"""))
        }
}
