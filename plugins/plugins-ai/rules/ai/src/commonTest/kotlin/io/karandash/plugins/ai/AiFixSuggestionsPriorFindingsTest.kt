@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.context.ValidationContext
import io.karandash.duration.KarandashDuration
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.providers.OpenAiProvider
import io.karandash.testfixtures.createTestMergeRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fix-suggestions gets the deterministic rules' pass-1 findings to write AI fixes for. */
class AiFixSuggestionsPriorFindingsTest {
    private val seen = mutableListOf<List<Finding>>()

    @Test
    fun `fix-suggestions sees the failed deterministic rules`() =
        runTest {
            val titleFinding = Finding(severity = Severity.ERROR, message = "Bad title", rule = "title-format")
            val module = AiRuleModule(withFixAnalyzer())
            assertTrue(module.initialize(FakeContext(config())))

            module.validateWithFindings(
                "fix-suggestions",
                createTestMergeRequest(changes = listOf(FileChange("A.kt", ChangeType.MODIFIED, 1, 0, "@@ -1 +1 @@\n-a\n+b\n"))),
                JsonObject(emptyMap()),
                ValidationContext(priorFindings = listOf(titleFinding)),
            )
            module.shutdown()

            assertEquals(listOf(listOf(titleFinding)), seen)
        }

    private fun config(): JsonElement =
        JsonObject(
            mapOf(
                "provider" to JsonPrimitive("openai"),
                "apiKey" to JsonPrimitive("k"),
                "baseUrl" to JsonPrimitive("https://ai.test/v1"),
                "model" to JsonPrimitive("m"),
                "features" to JsonObject(mapOf("fixSuggestions" to JsonPrimitive(true))),
            ),
        )

    private suspend fun withFixAnalyzer(): ModuleRegistry {
        val provider = OpenAiProvider(
            apiKey = null,
            baseUrl = "https://ai.test/v1",
            timeout = KarandashDuration.ofSeconds(30),
            maxRetries = 0,
            httpClientEngine = MockEngine { _ -> respondError(HttpStatusCode.InternalServerError) },
        )
        val base = ModuleRegistryImpl().also { it.register(provider) }
        val analyzer = object : AiAnalyzerModule {
            override val id: String = "fix-suggestions"
            override val name: String = "fix-suggestions"
            override val version: String = "test"
            override val type: ModuleType get() = ModuleType.AI_ANALYZER
            override val analyzerId: String = "fix-suggestions"
            override val featureKey: String = "fixSuggestions"
            override val defaultEnabled: Boolean = false
            override val needsScopeDetection: Boolean = false

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
                seen += context.accumulatedFindings
                return emptyList()
            }
        }
        return object : ModuleRegistry by base {
            override fun aiAnalyzers() = listOf(analyzer)

            override fun findAiAnalyzer(analyzerId: String) = analyzer.takeIf { it.analyzerId == analyzerId }
        }
    }

    private class FakeContext(
        override val pluginConfig: JsonElement,
    ) : PluginContext {
        override fun env(name: String): String? = null

        override fun log(message: String) = Unit

        override fun warn(message: String) = Unit

        override val hostVersion: String = "1.0.0-test"
        override val workingDirectory: String = "/tmp/test"
    }
}
