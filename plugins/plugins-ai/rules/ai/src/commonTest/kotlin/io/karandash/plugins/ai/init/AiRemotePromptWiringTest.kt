@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.init

import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.support.mapToJsonElement
import io.karandash.plugins.ai.buildTestRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

/** A URL prompt source gets a remote fetcher instead of always falling back to the builtin. */
class AiRemotePromptWiringTest {
    private suspend fun remoteEnabled(prompts: Map<String, Any?>): Boolean? {
        val context = object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(
                mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "prompts" to prompts,
                ),
            )
            override val enrichment: EnrichmentContext = EnrichmentContext.EMPTY

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }
        val result = AiRuleInitializer(buildTestRegistry(), null).initialize(context)
        return result?.promptResolver?.let { resolver -> resolver.remoteEnabled.also { resolver.close() } }
    }

    @Test
    fun `a url prompt source is fetched`() =
        runTest {
            val layered = mapOf("sources" to listOf(mapOf("url" to "https://prompts.example.com/review.txt")))

            assertEquals(true, remoteEnabled(mapOf("risk-scoring" to mapOf("url" to "https://prompts.example.com/r.txt"))))
            assertEquals(true, remoteEnabled(mapOf("risk-scoring" to layered)))
        }

    @Test
    fun `no url prompt source creates no http client`() =
        runTest {
            assertEquals(false, remoteEnabled(mapOf("risk-scoring" to "Inline prompt")))
        }
}
