package io.karandash.cli.commands.validate

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.testing.test
import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.errors.ProviderException
import io.karandash.api.exception.ConfigParseException
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.lsp.LspCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.cli.plugin.PluginCommandBridge
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The exit code each failure class of a validate run maps to (docs/reference/error-codes.md). */
class ExitCodeMappingTest {
    private suspend fun exitCodeOf(error: Exception): Int =
        assertFailsWith<ProgramResult> { runMappingErrors(CliLogger(sink = {})) { throw error } }.statusCode

    @Test
    fun `each failure class maps to its documented exit code`() =
        runTest {
            assertEquals(ExitCodes.PROVIDER_ERROR, exitCodeOf(ProviderException("401 from GitLab")))
            assertEquals(ExitCodes.INTERNAL_ERROR, exitCodeOf(IllegalStateException("bug")))
            assertEquals(ExitCodes.CONFIG_ERROR, exitCodeOf(ConfigParseException("bad key")))
            assertEquals(3, ExitCodes.PROVIDER_ERROR)
            assertEquals(4, ExitCodes.INTERNAL_ERROR)
            assertEquals(2, ExitCodes.CONFIG_ERROR)
        }

    @Test
    fun `a plugin command whose module fails to initialize and lsp on a native binary exit 6`() {
        val context = object : PluginContext {
            override val pluginConfig: JsonElement = JsonNull
            override val hostVersion: String = "test"
            override val workingDirectory: String = "."

            override fun env(name: String): String? = null
        }
        val failing = object : Module {
            override val id = "failing"
            override val name = "failing"
            override val version = "1.0.0"
            override val type = ModuleType.RULE

            override suspend fun initialize(context: PluginContext): Boolean = false
        }
        val spec = PluginCommandSpec("leaf", "leaf", run = { 0 })

        val plugin = PluginCommandBridge.buildCommand(spec, context, failing).test(emptyList())
        val lsp = LspCommand(PluginBootstrap(), serve = null).test(emptyList())

        assertEquals(ExitCodes.EXECUTION_ERROR, plugin.statusCode, plugin.output)
        assertEquals(ExitCodes.EXECUTION_ERROR, lsp.statusCode, lsp.output)
        assertEquals(6, ExitCodes.EXECUTION_ERROR)
    }
}
