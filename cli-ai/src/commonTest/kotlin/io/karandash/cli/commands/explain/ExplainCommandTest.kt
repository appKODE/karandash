package io.karandash.cli.commands.explain

import com.github.ajalt.clikt.testing.test
import io.karandash.api.PluginContext
import io.karandash.api.errors.MissingPluginException
import io.karandash.api.exception.KarandashException
import io.karandash.api.module.ProfileModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.Config
import io.karandash.platform.readText
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `explain` over an empty plugin registry and an injected config loader. */
class ExplainCommandTest {
    private val bootstrap = PluginBootstrap()
    private val requested = mutableListOf<String?>()

    private fun explain(load: suspend (String?) -> ExplainConfigSource = { ExplainConfigSource(path = "found.yaml", config = Config()) },) =
        ExplainCommand(bootstrap) { path ->
            requested += path
            load(path)
        }

    @Test
    fun `text output names the discovered config`() {
        val result = explain().test(emptyList())
        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf<String?>(null), requested)
        assertContains(result.stdout, "found.yaml")
    }

    @Test
    fun `an explicit config path is passed to the loader and reported`() {
        val result = explain().test(listOf("--config", "given.yaml", "--format", "json"))
        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf<String?>("given.yaml"), requested)
        assertContains(result.stdout, "given.yaml")
        Json.parseToJsonElement(result.stdout)
    }

    @Test
    fun `output option writes the explanation to a file`() {
        val dir = TestFiles.createTestTempDirectory("explain")
        SystemFileSystem.createDirectories(dir)
        val out = Path(dir, "explain.md")
        val result = explain().test(listOf("--format", "markdown", "--output", "$out"))
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stdout, "Config explanation written to $out")
        assertContains(out.readText(), "found.yaml")
    }

    @Test
    fun `a config that does not load falls back to zero-config defaults with a warning`() =
        runTest {
            bootstrap.registry().register(ZeroConfigProfile)
            for (failure in listOf(KarandashException("bad yaml"), IOException("unreadable"), IllegalArgumentException("bad value"))) {
                val result = explain { throw failure }.test(emptyList())
                assertEquals(0, result.statusCode, result.output)
                assertContains(result.stderr, "Warning: Failed to parse config: ${failure.message}")
                assertFalse("found.yaml" in result.stdout, result.stdout)
                assertContains(result.stdout, "zero-config-rule")
            }
        }

    @Test
    fun `a missing plugin fails the command`() {
        val result = explain { throw MissingPluginException("plugin x is missing") }.test(emptyList())
        assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode)
        assertContains(result.stderr, "Error: plugin x is missing")
    }

    @Test
    fun `doc with ai and no provider returns the template with a warning`() {
        val ai = buildJsonObject { put("provider", JsonPrimitive("nobody")) }
        val result = explain { ExplainConfigSource(path = null, config = Config(ai = ai)) }.test(listOf("--format", "doc", "--ai"))
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stderr, "ai.provider 'nobody' is not a registered AI provider")
        assertContains(result.stderr, "Returning template-only output.")
        assertTrue(result.stdout.isNotBlank())
    }

    @Test
    fun `an unknown format is a usage error`() {
        assertEquals(1, explain().test(listOf("--format", "pdf")).statusCode)
    }

    private object ZeroConfigProfile : ProfileModule {
        override val id = "io.karandash.profile.explain-test"
        override val profileId = "explain-test"
        override val name = "Explain test profile"
        override val version = "1.0.0"
        override val summary = "Supplies the zero-config rules"
        override val isDefaultZeroConfigProfile = true

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun ruleConfigs(): Map<String, JsonElement> = mapOf("zero-config-rule" to JsonObject(emptyMap()))
    }
}
