package io.karandash.cli

import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.config.renderers.registerBuiltInRenderers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliLogLevelTest {
    @Test
    fun flagsAnywhereBeforeDoubleDashWin() {
        assertEquals(CliLogLevel.VERBOSE, CliLogLevel.resolve(listOf("validate", "-v"), null))
        assertEquals(CliLogLevel.VERBOSE, CliLogLevel.resolve(listOf("--debug", "doctor"), null))
        assertEquals(CliLogLevel.QUIET, CliLogLevel.resolve(listOf("validate", "-v", "--quiet"), null))
        assertEquals(CliLogLevel.NORMAL, CliLogLevel.resolve(listOf("plugin-cmd", "--", "-v"), null))
    }

    @Test
    fun `a log flag given as an option value is not a log flag`() =
        runTest {
            val bootstrap = PluginBootstrap(envReader = { _ -> null }, onFormatRegistryCreated = ::registerBuiltInRenderers)
            bootstrap.loadPlugins()
            val valueOptions = valueOptionNames(karandashCommandTree(bootstrap, CliLogLevel.NORMAL, null, emptyList()))
            bootstrap.shutdown()
            assertTrue("--profile" in valueOptions && "-q" !in valueOptions, "$valueOptions")
            assertEquals(CliLogLevel.NORMAL, CliLogLevel.resolve(listOf("validate", "--profile", "-q"), null, valueOptions))
            assertEquals(CliLogLevel.QUIET, CliLogLevel.resolve(listOf("validate", "--dry-run", "-q"), null, valueOptions))
        }

    @Test
    fun extendsVerboseFlagStaysLocal() {
        assertEquals(CliLogLevel.NORMAL, CliLogLevel.resolve(listOf("validate", "extends", "-v"), null))
        assertEquals(CliLogLevel.VERBOSE, CliLogLevel.resolve(listOf("validate", "extends", "--debug"), null))
    }

    @Test
    fun cliFlagOverridesEnvWhichOverridesDefault() {
        assertEquals(CliLogLevel.VERBOSE, CliLogLevel.resolve(listOf("validate"), "DEBUG"))
        assertEquals(CliLogLevel.VERBOSE, CliLogLevel.resolve(listOf("validate"), "trace"))
        assertEquals(CliLogLevel.QUIET, CliLogLevel.resolve(listOf("validate"), "error"))
        assertEquals(CliLogLevel.NORMAL, CliLogLevel.resolve(listOf("validate"), "info"))
        assertEquals(CliLogLevel.VERBOSE, CliLogLevel.resolve(listOf("validate", "-v"), "error"))
        assertEquals(CliLogLevel.NORMAL, CliLogLevel.resolve(emptyList(), null))
    }

    @Test
    fun verboseIsConfiguredBeforePluginLoading() =
        runTest {
            val captured = mutableListOf<String>()
            val bootstrap = PluginBootstrap()
            preconfigureLogging(bootstrap, listOf("validate", "-v"), envLevel = null, sink = captured::add)
            bootstrap.loadPlugins()
            bootstrap.shutdown()
            assertTrue(captured.any { "[DEBUG] Plugin loading:" in it }, "loader logs lost: $captured")
        }

    @Test
    fun `a clean plugin load prints nothing at the normal level`() =
        runTest {
            val captured = mutableListOf<String>()
            val bootstrap = PluginBootstrap()
            preconfigureLogging(bootstrap, listOf("validate"), envLevel = null, sink = captured::add)
            bootstrap.loadPlugins()
            bootstrap.shutdown()
            assertTrue(captured.isEmpty(), "unexpected output: $captured")
        }

    @Test
    fun `a bootstrap warning such as an alias collision reaches stderr on doctor with no flags`() =
        runTest {
            val captured = mutableListOf<String>()
            val bootstrap = PluginBootstrap()
            preconfigureLogging(bootstrap, listOf("doctor"), envLevel = null, sink = captured::add)
            bootstrap.logger.warn("alias 'x' of b collides with a; the first registered module keeps it")
            assertEquals(1, captured.count { "[WARN] alias 'x' of b collides" in it }, "$captured")
        }
}
