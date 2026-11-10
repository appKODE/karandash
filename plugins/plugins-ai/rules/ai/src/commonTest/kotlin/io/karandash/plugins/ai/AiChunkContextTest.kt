package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.support.mapToJsonElement
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

/** `ai.large-mr.chunk-context`: a diff bigger than the prompt is chunked and keeps context in every chunk call. */
class AiChunkContextTest {
    @Test
    fun `chunk-context keeps cross-file context in chunked calls and false lets the diff crowd it out`() =
        runTest {
            assertEquals(emptyList(), contextDropped(chunkContext = true))
            assertEquals(listOf(CROSS_FILE), contextDropped(chunkContext = false))
        }

    private suspend fun contextDropped(chunkContext: Boolean): List<String> {
        val analyzer = object : AiAnalyzerModule {
            override val analyzerId: String = RULE
            override val id: String = RULE
            override val name: String = RULE
            override val version: String = "test"
            override val type: ModuleType get() = ModuleType.AI_ANALYZER
            override val featureKey: String = RULE
            override val defaultEnabled: Boolean = true
            override val estimatedTokens: Int = 1

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun analyze(context: AiAnalyzerContext): List<Finding> = emptyList()
        }
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun aiAnalyzers(): List<AiAnalyzerModule> = listOf(analyzer)

            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { it.analyzerId == analyzerId }
        }
        val module = AiRuleModule(registry)
        assertTrue(module.initialize(context(chunkContext)))
        // Diff about 20k tokens, prompt cap 8000: the MR is chunked either way.
        val changes = (1..FILES).map { i ->
            FileChange("src/F$i.kt", ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = diff(i))
        }
        module.validate(RULE, createTestMergeRequest(changes = changes), JsonObject(emptyMap()))
        val coverage = assertNotNull(module.reviewProvenance()?.coverage, "no coverage recorded")
        module.shutdown()
        return coverage.contextDropped
    }

    private fun diff(i: Int): String = "@@ -1,1 +1,2 @@\n import com.example.dep.Dep$i\n+val filler$i = \"${"x".repeat(FILE_CHARS)}\""

    private fun context(chunkContext: Boolean): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(
                mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "maxTokenBudget" to 1_000_000,
                    "budget" to mapOf("maxInputTokens" to MAX_INPUT_TOKENS),
                    "largeMr" to mapOf("chunkContext" to chunkContext),
                ),
            )

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    private companion object {
        const val RULE = "review"
        const val CROSS_FILE = "crossFileContext"
        const val FILES = 40
        const val FILE_CHARS = 2000
        const val MAX_INPUT_TOKENS = 8000
    }
}
