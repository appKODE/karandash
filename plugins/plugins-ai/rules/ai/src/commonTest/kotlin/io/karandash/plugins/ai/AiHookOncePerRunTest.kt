package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.logger.Logger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** config hooks run once per run from the pipeline extension, never once per AI rule. */
class AiHookOncePerRunTest {
    private val messages = mutableListOf<String>()

    private val recording = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) {
            messages += message
        }

        override fun warn(message: String) {
            messages += message
        }

        override fun error(message: String) {
            messages += message
        }
    }

    private val context = object : PluginContext {
        override val pluginConfig: JsonElement = mapToJsonElement(
            mapOf(
                "provider" to "custom",
                "baseUrl" to "http://localhost:1234/v1",
                "model" to "test-model",
                "hooks" to mapOf("post-validation" to mapOf("prompt" to "Summarize {{findings_json}}")),
            ),
        )
        override val logger: Logger = recording

        override fun env(name: String): String? = null

        override fun log(message: String) {
            messages += message
        }

        override fun warn(message: String) {
            messages += message
        }

        override val hostVersion: String = "1.0.0-test"
        override val workingDirectory: String = "/tmp/test"
    }

    @Test
    fun `validating a rule does not run the post-validation hook`() =
        runTest {
            val registry = object : ModuleRegistry by buildTestRegistry() {
                override fun aiAnalyzers(): List<AiAnalyzerModule> = listOf(QuietAnalyzer)

                override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = QuietAnalyzer.takeIf { analyzerId == RULE }
            }
            val module = AiRuleModule(registry)
            assertTrue(module.initialize(context))
            val change = FileChange("A.kt", ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = "@@ -1 +1 @@\n-a\n+b")

            module.validate(RULE, createTestMergeRequest(changes = listOf(change)), JsonObject(emptyMap()))

            assertEquals(emptyList(), messages.filter { "hook" in it }, "the rule ran a hook")
            module.shutdown()
        }

    private object QuietAnalyzer : AiAnalyzerModule {
        override val analyzerId: String = RULE
        override val id: String = RULE
        override val name: String = RULE
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = RULE
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 100

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> = emptyList()
    }

    private companion object {
        const val RULE = "quiet"
    }
}
