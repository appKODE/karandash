package io.karandash.plugins.ai

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiProviderException
import io.karandash.api.facet.PluginCommandContext
import io.karandash.plugins.ai.core.AiDefaultModels
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `ai` subcommands redact secrets before the prompt leaves, always close the provider
 * and honour the configured `ai.model` and `ai.temperature`.
 */
class AiCliCommandPrivacyTest {
    private val secret = "ghp_" + "a".repeat(36)

    private class Recorder(
        private val fail: Boolean = false,
    ) : AiProvider {
        override val providerId = "custom"
        val prompts = mutableListOf<String>()
        val models = mutableListOf<String>()
        val temperatures = mutableListOf<Double>()
        var closed = 0

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse {
            prompts += prompt
            models += model
            temperatures += temperature
            if (fail) throw AiProviderException("boom")
            return AiResponse("ok", 1, 1, 2, model)
        }

        override suspend fun close() {
            closed++
        }
    }

    private fun context(config: JsonElement) =
        object : PluginContext {
            override val pluginConfig: JsonElement = config

            override fun env(name: String): String? = null

            override fun log(message: String) = Unit

            override fun warn(message: String) = Unit

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    private suspend fun run(
        sub: String,
        args: List<String>,
        provider: Recorder,
        config: JsonElement = JsonObject(emptyMap()),
    ): Int {
        val module = AiCliCommandModule { provider }
        val spec = module.cliCommands()[0].subcommands.single { it.name == sub }
        return checkNotNull(spec.run)(PluginCommandContext(args, context(config), {}, {}))
    }

    @Test
    fun `fix reply and describe never send a secret`() =
        runTest {
            val cases = mapOf(
                "fix" to listOf("--rule", "r", "--message", "token $secret leaked"),
                "reply" to listOf("--comment", "use $secret", "--context", "AKIA" + "B".repeat(16)),
                "describe" to listOf("--title", "t", "--diff", "+ key = $secret"),
            )
            for ((sub, args) in cases) {
                val provider = Recorder()
                assertEquals(0, run(sub, args, provider), sub)
                val prompt = provider.prompts.single()
                assertFalse(secret in prompt, "$sub: $prompt")
                assertFalse("AKIA" + "B".repeat(16) in prompt, "$sub: $prompt")
                assertEquals(1, provider.closed, sub)
            }
        }

    @Test
    fun `the provider is closed when the call fails`() =
        runTest {
            val provider = Recorder(fail = true)
            assertEquals(3, run("fix", listOf("--rule", "r", "--message", "m"), provider))
            assertEquals(1, provider.closed)
        }

    @Test
    fun `the configured ai model and temperature are used`() =
        runTest {
            val config = JsonObject(
                mapOf("ai" to JsonObject(mapOf("model" to JsonPrimitive("my-model"), "temperature" to JsonPrimitive(0.0)))),
            )
            val provider = Recorder()
            run("fix", listOf("--rule", "r", "--message", "m"), provider, config)
            assertEquals(listOf("my-model"), provider.models)
            assertEquals(listOf(0.0), provider.temperatures)
        }

    @Test
    fun `--model wins and an unset model falls back to the provider default`() =
        runTest {
            val flagged = Recorder()
            run("reply", listOf("--comment", "c", "--model", "flag-model"), flagged)
            assertEquals(listOf("flag-model"), flagged.models)
            val plain = Recorder()
            run("reply", listOf("--comment", "c"), plain)
            assertTrue(plain.models.single() == AiDefaultModels.cheapFor("custom"))
        }
}
