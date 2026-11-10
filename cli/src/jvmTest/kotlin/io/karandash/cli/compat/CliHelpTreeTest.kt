package io.karandash.cli.compat

import io.karandash.cli.runCli
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Walks the `--help` tree from the root: every listed command prints its usage and exits 0. */
class CliHelpTreeTest {
    @Test
    fun `every command in the help tree prints usage and exits 0`() =
        runTest(timeout = 5.minutes) {
            val visited = mutableListOf<String>()
            val pending = ArrayDeque(listOf(emptyList<String>()))
            while (pending.isNotEmpty()) {
                val path = pending.removeFirst()
                val (exit, out, err) = ConsoleCapture.capture { runCli((path + "--help").toTypedArray()) }
                assertEquals(0, exit, "${path.joinToString(" ")} --help: $err")
                assertContains(out, "Usage: karandash ${path.joinToString(" ")}".trimEnd())
                visited += path.joinToString(" ")
                subcommands(out).forEach { pending.addLast(path + it) }
            }
            assertTrue(visited.containsAll(DOCUMENTED), "missing: ${DOCUMENTED - visited.toSet()}")
        }

    @Test
    fun `unknown command is a usage error`() =
        runTest(timeout = 5.minutes) {
            val (exit, _, err) = ConsoleCapture.capture { runCli(arrayOf("no-such-command")) }
            assertEquals(2, exit)
            assertContains(err, "no-such-command")
        }

    /** Names listed under `Commands:` (two-space indent; wrapped descriptions are indented further). */
    private fun subcommands(help: String): List<String> =
        help
            .substringAfter("Commands:", "")
            .lineSequence()
            .drop(1)
            .takeWhile { it.isNotBlank() }
            .filter { it.startsWith("  ") && !it.startsWith("   ") }
            .map { it.trim().substringBefore(' ') }
            .toList()

    private companion object {
        val DOCUMENTED = listOf(
            "validate",
            "validate-extends",
            "init",
            "wizard",
            "stats",
            "calibrate",
            "test-rule",
            "verify-policy",
            "simulate",
            "mutate",
            "deps",
            "doctor",
            "explain",
            "generate",
            "profile list",
            "profile show",
            "profile export",
            "config validate",
            "config describe",
            "report summary",
            "cache stats",
            "cache gc",
            "cache clear",
            "cache push",
            "plugin",
            "lsp",
        )
    }
}
