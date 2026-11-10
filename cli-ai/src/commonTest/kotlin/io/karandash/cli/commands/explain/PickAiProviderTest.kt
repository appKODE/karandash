package io.karandash.cli.commands.explain

import io.karandash.config.Config
import io.karandash.plugins.ai.providers.AnthropicProvider
import io.karandash.plugins.ai.providers.OpenAiProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `explain --ai` uses the provider whose credentials are set, not the first registered one. */
class PickAiProviderTest {
    private val providers = listOf(AnthropicProvider(), OpenAiProvider())

    @Test
    fun `the provider whose key is set is picked`() {
        val picked = pickAiProvider(providers, Config()) { if (it == "OPENAI_API_KEY") "k" else null }
        assertEquals("openai", picked?.providerId)
    }

    @Test
    fun `ai provider in the config wins`() {
        val config = Config(ai = JsonObject(mapOf("provider" to JsonPrimitive("OpenAI"))))
        assertEquals("openai", pickAiProvider(providers, config) { "k" }?.providerId)
    }

    @Test
    fun `no credentials picks nothing`() {
        assertNull(pickAiProvider(providers, Config()) { null })
    }

    @Test
    fun `an unknown ai provider in the config warns and picks nothing`() {
        val config = Config(ai = JsonObject(mapOf("provider" to JsonPrimitive("opneai"))))
        val warnings = mutableListOf<String>()

        assertNull(pickAiProvider(providers, config, env = { "k" }, warn = { warnings += it }))
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue(warnings.single().contains("opneai"), warnings.single())
        assertTrue(warnings.single().contains("openai"), warnings.single())
    }
}
