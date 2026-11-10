package io.karandash.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.CliCommandBase
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.cli.errors.ExitCodes
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.get
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SS-2 acceptance: `--global-timeout` cancels a command stuck on a network call and exits 7, and an
 * unexpected exception becomes a one-line internal error (exit 4) instead of a stack trace.
 *
 * JVM-only: the watchdog needs real time and the same nested-`runBlocking` shape as `runCli`.
 */
class GlobalTimeoutExitCodeTest {
    private class Root : CliktCommand(name = "karandash") {
        override fun run() = Unit
    }

    private class StalledHttpCommand : CliCommandBase("stall", PluginBootstrap()) {
        override suspend fun execute(): Int {
            HttpClient(MockEngine { awaitCancellation() }).use { it.get("https://stalled.example/api") }
            return ExitCodes.SUCCESS
        }
    }

    private class CrashingCommand : CliCommandBase("crash", PluginBootstrap()) {
        override suspend fun execute(): Int = error("boom")
    }

    private class RawCrashingCommand : CliktCommand(name = "raw-crash") {
        override fun run(): Unit = error("raw boom")
    }

    private fun root() = Root().subcommands(StalledHttpCommand(), CrashingCommand(), RawCrashingCommand())

    @Test
    fun `validate accepts the global timeout flag that the watchdog reads from raw args`() =
        runTest {
            val bootstrap = PluginBootstrap(envReader = { null })
            bootstrap.loadPlugins()
            val result = ValidateCommand(bootstrap).test("--global-timeout 5s --no-auto-detect")
            assertFalse("no such option" in result.stderr, result.stderr)
            assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode, result.stderr)
        }

    @Test
    fun `global timeout cancels a stalled http call and exits 7`() {
        var exit = -1
        // Separate thread + join timeout: if cancellation stops reaching the command, the test fails instead of hanging.
        val worker = thread {
            exit = runBlocking {
                runWithGlobalTimeout(arrayOf("--global-timeout", "1s")) {
                    dispatchCli(root(), arrayOf("stall"), CliLogLevel.NORMAL)
                }
            }
        }
        worker.join(JOIN_TIMEOUT_MS)
        assertFalse(worker.isAlive, "command was not cancelled by the global timeout")
        assertEquals(ExitCodes.GLOBAL_TIMEOUT, exit)
    }

    @Test
    fun `unexpected exception in a CliCommandBase command exits with internal error`() {
        val exit = runBlocking { dispatchCli(root(), arrayOf("crash"), CliLogLevel.NORMAL) }
        assertEquals(ExitCodes.INTERNAL_ERROR, exit)
    }

    @Test
    fun `last-resort handler prints one line without stack trace unless debug`() {
        val lines = mutableListOf<String>()
        val exit = runBlocking { dispatchCli(root(), arrayOf("raw-crash"), CliLogLevel.NORMAL, lines::add) }
        assertEquals(ExitCodes.INTERNAL_ERROR, exit)
        assertEquals("[ERROR] Internal error: IllegalStateException: raw boom", lines.first())
        assertFalse(lines.any { "\tat " in it }, lines.toString())

        val debugLines = mutableListOf<String>()
        runBlocking { dispatchCli(root(), arrayOf("raw-crash"), CliLogLevel.VERBOSE, debugLines::add) }
        assertTrue(debugLines.any { "\tat " in it }, debugLines.toString())
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 20_000L
    }
}
