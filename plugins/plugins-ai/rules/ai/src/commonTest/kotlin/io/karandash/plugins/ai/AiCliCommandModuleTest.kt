package io.karandash.plugins.ai

import io.karandash.api.PluginContext
import io.karandash.api.facet.PluginCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiCliCommandModuleTest {
    private fun createContext(
        envVars: Map<String, String> = emptyMap(),
        config: Map<String, String> = emptyMap(),
    ): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = JsonObject(config.mapValues { JsonPrimitive(it.value) })

            override fun env(name: String): String? = envVars[name]

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    @Test
    fun `initialize returns true with OPENAI_API_KEY`() =
        runTest {
            val module = AiCliCommandModule()
            val result = module.initialize(createContext(mapOf("OPENAI_API_KEY" to "sk-test")))
            assertTrue(result)
        }

    @Test
    fun `initialize returns true with GEMINI_API_KEY`() =
        runTest {
            val module = AiCliCommandModule()
            val result = module.initialize(createContext(mapOf("GEMINI_API_KEY" to "gemini-key")))
            assertTrue(result)
        }

    @Test
    fun `initialize returns false with no keys`() =
        runTest {
            val module = AiCliCommandModule()
            val result = module.initialize(createContext())
            assertFalse(result)
        }

    @Test
    fun `cliCommands returns ai command with all subcommands`() =
        runTest {
            val module = AiCliCommandModule()
            val commands = module.cliCommands()

            assertEquals(1, commands.size)
            assertEquals("ai", commands[0].name)
            assertEquals(listOf("fix", "reply", "describe"), commands[0].subcommands.map { it.name })
        }

    @Test
    fun `ai describe runs without a deprecation notice`() =
        runTest {
            val errors = mutableListOf<String>()
            val ctx = PluginCommandContext(
                rawArgs = emptyList(),
                pluginContext = createContext(),
                echo = {},
                echoError = { errors += it },
            )
            val describe = AiCliCommandModule().cliCommands()[0].subcommands.single { it.name == "describe" }

            assertEquals(2, checkNotNull(describe.run)(ctx))
            assertTrue(errors.none { it.contains("deprecated", ignoreCase = true) }, "no deprecation notice expected: $errors")
            assertTrue(!describe.help.contains("deprecated", ignoreCase = true), describe.help)
        }

    @Test
    fun `parseFlag extracts value after flag`() =
        runTest {
            val result = AiCliCommandModule.parseFlag(
                listOf("--rule", "title-format", "--message", "bad title"),
                "--rule",
            )
            assertEquals("title-format", result)
        }

    @Test
    fun `parseFlag returns null when flag missing`() =
        runTest {
            val result = AiCliCommandModule.parseFlag(listOf("--other", "value"), "--rule")
            assertNull(result)
        }

    @Test
    fun `parseFlag returns null when flag is last arg`() =
        runTest {
            val result = AiCliCommandModule.parseFlag(listOf("--rule"), "--rule")
            assertNull(result)
        }

    @Test
    fun `createProvider returns OpenAiProvider for OPENAI_API_KEY`() =
        runTest {
            val ctx = PluginCommandContext(
                rawArgs = emptyList(),
                pluginContext = createContext(mapOf("OPENAI_API_KEY" to "sk-test")),
                echo = {},
                echoError = {},
            )
            val provider = AiCliCommandModule.createProvider(ctx)

            assertNotNull(provider)
            assertEquals("openai", provider.providerId)
            provider.close()
        }

    @Test
    fun `createProvider returns GeminiProvider for GEMINI_API_KEY`() =
        runTest {
            val ctx = PluginCommandContext(
                rawArgs = emptyList(),
                pluginContext = createContext(mapOf("GEMINI_API_KEY" to "gem-test")),
                echo = {},
                echoError = {},
            )
            val provider = AiCliCommandModule.createProvider(ctx)

            assertNotNull(provider)
            assertEquals("gemini", provider.providerId)
            provider.close()
        }

    @Test
    fun `createProvider returns null when no keys set`() =
        runTest {
            val ctx = PluginCommandContext(
                rawArgs = emptyList(),
                pluginContext = createContext(),
                echo = {},
                echoError = {},
            )
            val provider = AiCliCommandModule.createProvider(ctx)

            assertNull(provider)
        }

    @Test
    fun `the plugin config is the ai block and ai provider alone initializes the module`() =
        runTest {
            val module = AiCliCommandModule()

            assertEquals("ai", module.configKey)
            assertTrue(module.initialize(createContext(config = mapOf("provider" to "ollama"))))
        }

    @Test
    fun `ai provider and ai baseUrl pick the provider and its endpoint over the env keys`() =
        runTest {
            val errors = mutableListOf<String>()
            val ctx = PluginCommandContext(
                rawArgs = listOf("--rule", "title-format", "--message", "bad title"),
                pluginContext = createContext(
                    envVars = mapOf("OPENAI_API_KEY" to "sk-test"),
                    config = mapOf("provider" to "ollama", "baseUrl" to "http://203.0.113.7:11434/v1"),
                ),
                echo = {},
                echoError = { errors += it },
            )
            val provider = assertNotNull(AiCliCommandModule.createProvider(ctx))
            assertEquals("ollama", provider.providerId)
            provider.close()

            val fix = AiCliCommandModule()
                .cliCommands()
                .single()
                .subcommands
                .single { it.name == "fix" }
            val exit = checkNotNull(fix.run)(ctx)

            assertEquals(3, exit, "a provider failure is the provider-error exit code")
            assertTrue(
                errors.single().contains("http://203.0.113.7:11434/v1 must be a loopback or private-network host"),
                errors.toString()
            )
        }

    @Test
    fun `a custom provider without ai baseUrl is not created`() =
        runTest {
            val ctx = PluginCommandContext(
                rawArgs = emptyList(),
                pluginContext = createContext(config = mapOf("provider" to "custom")),
                echo = {},
                echoError = {},
            )

            assertNull(AiCliCommandModule.createProvider(ctx))
        }

    @Test
    fun `a temperature that is not a number is a config error before any provider call`() =
        runTest {
            val errors = mutableListOf<String>()
            val ctx = PluginCommandContext(
                rawArgs = listOf("--rule", "title-format", "--message", "bad title", "--temperature", "abc"),
                pluginContext = createContext(envVars = mapOf("OPENAI_API_KEY" to "sk-test")),
                echo = {},
                echoError = { errors += it },
            )
            val fix = AiCliCommandModule()
                .cliCommands()
                .single()
                .subcommands
                .single { it.name == "fix" }

            assertEquals(2, checkNotNull(fix.run)(ctx))
            assertEquals(listOf("Error: temperature must be a number, got 'abc'"), errors)
        }
}
