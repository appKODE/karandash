package io.karandash.plugins.ai

import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.module.ConfigFormatModule
import io.karandash.api.module.PipelineEnvironment
import io.karandash.api.module.RuleModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.config.keys.ModuleIds
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.core.context.ConventionContextEnricher
import io.karandash.plugins.ai.init.DuoReviewInstructions
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The GitLab Duo instructions file is opt-in and reaches the prompt only as read from the MR base. */
class AiDuoBaseInstructionsTest {
    /** Each analyzer call's prompt channels: extra instructions, then the context blocks. */
    private val prompts = mutableListOf<List<String>>()

    private val analyzer = object : AiAnalyzerModule {
        override val analyzerId: String = RULE
        override val id: String = RULE
        override val name: String = RULE
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = RULE
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 100

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            prompts += listOf(context.extraInstructions.orEmpty(), context.contextBlocks.joinToString { it.content })
            return emptyList()
        }
    }

    private val context = object : PluginContext {
        override val pluginConfig: JsonElement = mapToJsonElement(
            mapOf("provider" to "custom", "baseUrl" to "http://localhost:1234/v1", "model" to "test-model"),
        )

        override fun env(name: String): String? = null

        override fun log(message: String) {}

        override fun warn(message: String) {}

        override val hostVersion: String = "1.0.0-test"
        override val workingDirectory: String = "/tmp/test"
    }

    private val base = object : ModuleRegistry by buildTestRegistry() {
        override fun aiAnalyzers(): List<AiAnalyzerModule> = listOf(analyzer)

        override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { it.analyzerId == analyzerId }

        override fun configFormats(): List<ConfigFormatModule> = listOf(YamlFormatModule())
    }

    private fun extension(module: AiRuleModule) =
        AiPipelineExtension(
            object : ModuleRegistry by base {
                override fun get(id: String): Module? = if (id == ModuleIds.AI_ANALYSIS) module else null

                override fun ruleModules(): List<RuleModule> = listOf(module)
            },
        )

    @Test
    fun `the duo file is off by default and asked for from the base only when enabled`() {
        val ext = AiPipelineExtension(base)

        assertFalse(DuoReviewInstructions.PATH in ext.baseFiles(JsonObject(emptyMap())))
        assertTrue(ext.baseFiles(null).isEmpty())
        val on = mapToJsonElement(mapOf("review" to mapOf("duoInstructionsFile" to true)))
        assertTrue(DuoReviewInstructions.PATH in ext.baseFiles(on))
    }

    @Test
    fun `convention files are asked for from the base and reach the convention enricher`() =
        runTest {
            val conventions = ConventionContextEnricher()
            val ext = AiPipelineExtension(
                object : ModuleRegistry by base {
                    override fun aiContextEnrichers(): List<AiContextEnricherModule> = listOf(conventions)
                },
            )

            assertTrue("AGENTS.md" in ext.baseFiles(JsonObject(emptyMap())))
            ext.onBaseFiles(mapOf("AGENTS.md" to "Base agent rule"))
            assertTrue(conventions.initialize(context))

            val block = conventions.provideBlock(rubyMr(), rubyMr().changes, JsonObject(emptyMap()))
            assertTrue(block?.content.orEmpty().contains("Base agent rule"), block.toString())
        }

    @Test
    fun `convention files of one run do not reach a next run that reads no base files`() =
        runTest {
            val conventions = ConventionContextEnricher()
            val ext = AiPipelineExtension(
                object : ModuleRegistry by base {
                    override fun aiContextEnrichers(): List<AiContextEnricherModule> = listOf(conventions)
                },
            )
            assertTrue(conventions.initialize(context))
            ext.onConfigured(PipelineEnvironment(aiService = null, rawConfig = JsonObject(emptyMap()), statsDir = ".karandash/stats"))
            ext.onBaseFiles(mapOf("AGENTS.md" to "Base agent rule"))

            ext.onConfigured(PipelineEnvironment(aiService = null, rawConfig = JsonObject(emptyMap()), statsDir = ".karandash/stats"))

            assertNull(conventions.provideBlock(rubyMr(), rubyMr().changes, JsonObject(emptyMap())))
        }

    @Test
    fun `instructions read from the base reach the prompt and none come from elsewhere`() =
        runTest {
            val module = AiRuleModule(base)
            assertTrue(module.initialize(context))
            val mr = createTestMergeRequest(changes = listOf(FileChange("app.rb", ChangeType.MODIFIED, 1, 0, diffContent = DIFF)))

            module.validate(RULE, mr, JsonObject(emptyMap()))
            assertFalse(prompts.single().any { BASE_TEXT in it }, "instructions sent before any base file was read")

            extension(module).onBaseFiles(mapOf(DuoReviewInstructions.PATH to BASE_FILE))
            extension(module).onBaseFiles(mapOf(DuoReviewInstructions.PATH to BASE_FILE))
            module.validate(RULE, mr, JsonObject(emptyMap()))

            val counts = prompts.last().map { Regex(BASE_TEXT).findAll(it).count() }
            assertTrue(counts.any { it == 1 } && counts.none { it > 1 }, "base instruction not sent exactly once: $counts")
            module.shutdown()
        }

    @Test
    fun `base instructions survive the enrichment re-initialize`() =
        runTest {
            val module = AiRuleModule(base)
            assertTrue(module.initialize(context))
            extension(module).onBaseFiles(mapOf(DuoReviewInstructions.PATH to BASE_FILE))
            assertTrue(module.initialize(context, emptyMap()))

            module.validate(RULE, rubyMr(), JsonObject(emptyMap()))

            assertTrue(prompts.single().any { BASE_TEXT in it }, "base instruction lost on re-initialize: $prompts")
            module.shutdown()
        }

    @Test
    fun `base instructions read before initialize reach the prompt`() =
        runTest {
            val module = AiRuleModule(base)
            extension(module).onBaseFiles(mapOf(DuoReviewInstructions.PATH to BASE_FILE))
            assertTrue(module.initialize(context))

            module.validate(RULE, rubyMr(), JsonObject(emptyMap()))

            assertTrue(prompts.single().any { BASE_TEXT in it }, "base instruction dropped without an init state: $prompts")
            module.shutdown()
        }

    @Test
    fun `base instructions of one run do not leak into the next run`() =
        runTest {
            val module = AiRuleModule(base)
            extension(module).onBaseFiles(mapOf(DuoReviewInstructions.PATH to BASE_FILE))
            assertTrue(module.initialize(context))
            module.validate(RULE, rubyMr(), JsonObject(emptyMap()))
            module.shutdown()

            assertTrue(module.initialize(context))
            module.validate(RULE, rubyMr(), JsonObject(emptyMap()))

            assertTrue(prompts.first().any { BASE_TEXT in it }, "first run lost its base instruction: $prompts")
            assertFalse(prompts.last().any { BASE_TEXT in it }, "base instruction leaked into the next run: $prompts")
            module.shutdown()
        }

    private fun rubyMr() = createTestMergeRequest(changes = listOf(FileChange("app.rb", ChangeType.MODIFIED, 1, 0, diffContent = DIFF)))

    private companion object {
        const val RULE = "review"
        const val BASE_TEXT = "Base branch rule"
        const val DIFF = "@@ -1,1 +1,1 @@\n-old line\n+new line"
        val BASE_FILE =
            """
            |instructions:
            |  - name: Ruby
            |    fileFilters: ["*.rb"]
            |    instructions: $BASE_TEXT
            |
            """.trimMargin()
    }
}
