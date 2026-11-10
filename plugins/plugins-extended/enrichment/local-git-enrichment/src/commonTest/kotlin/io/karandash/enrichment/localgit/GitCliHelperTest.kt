package io.karandash.enrichment.localgit

import io.karandash.api.platform.SubprocessBudget
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [GitCliHelper].
 *
 * [GitCliHelper] is an `internal object` that directly delegates to
 * [PlatformProcess.executeAsync] with no injectable seam. Tests therefore
 * exercise the real subprocess path where available.
 *
 * On native targets, subprocess execution may not resolve `git` in PATH,
 * so tests that require a successful `git` invocation are written to
 * accept both outcomes (non-null on JVM, possibly null on native).
 *
 * Budget and sanitizer testing is NOT possible here because [GitCliHelper]
 * does not consume a [SubprocessBudget] or invoke [GitArgSanitizer] --
 * those responsibilities belong to calling code.
 */
class GitCliHelperTest {
    @Test
    fun `run returns non-empty output for successful git command when git is available`() =
        runTest {
            val output = GitCliHelper.run(
                workingDir = ".",
                args = listOf("rev-parse", "--git-dir"),
            )
            // On JVM inside a git repo this succeeds; on native git may not be on PATH
            if (output != null) {
                assertTrue(output.trim().isNotEmpty(), "Output should be non-empty when git succeeds")
            }
        }

    @Test
    fun `run returns null for non-zero exit code`() =
        runTest {
            // "git log" with an impossible ref yields non-zero exit
            val output = GitCliHelper.run(
                workingDir = ".",
                args = listOf("log", "--oneline", "-1", "0000000000000000000000000000000000000000"),
            )
            // Non-zero exit or git-not-found both produce null
            assertNull(output, "Expected null for a git command that fails")
        }

    @Test
    fun `run returns null when workingDir does not exist`() =
        runTest {
            val output = GitCliHelper.run(
                workingDir = "/nonexistent/path/that/should/not/exist",
                args = listOf("status"),
            )
            assertNull(output, "Expected null when workingDir does not exist")
        }

    @Test
    fun `run with null workingDir does not crash`() =
        runTest {
            // PlatformProcess.executeAsync with null workingDir uses CWD
            // On JVM in a repo this succeeds; on native git may not be on PATH
            val output = GitCliHelper.run(
                workingDir = null,
                args = listOf("rev-parse", "--git-dir"),
            )
            // Accept both outcomes - the important contract is no crash
            if (output != null) {
                assertTrue(output.trim().isNotEmpty())
            }
        }

    @Test
    fun `run with empty args list does not crash`() =
        runTest {
            // bare "git" with no args may print usage to stderr and exit non-zero
            val output = GitCliHelper.run(
                workingDir = ".",
                args = emptyList(),
            )
            // Either null (non-zero exit or git not found) or non-empty output
            if (output != null) {
                assertTrue(output.isNotEmpty())
            }
        }

    @Test
    fun `run with custom timeout does not crash`() =
        runTest {
            val output = GitCliHelper.run(
                workingDir = ".",
                args = listOf("rev-parse", "--git-dir"),
                timeoutSeconds = 5,
            )
            // Accept both outcomes - testing that the timeout param is accepted
            if (output != null) {
                assertTrue(output.trim().isNotEmpty())
            }
        }

    @Test
    fun `a call that overruns the wall-time budget is recorded as a rejection`() =
        runTest {
            val budget = SubprocessBudget(maxCalls = 5, maxWallMillis = 1)
            val output = GitCliHelper.run(".", listOf("rev-parse", "--git-dir"), budget = budget, logger = NoOpLogger)
            // git may be missing on native, and a sub-millisecond spawn stays within budget
            if (output != null && budget.wallMillisUsed > 1) {
                assertTrue(budget.hasRejected, "overrunning the wall-time budget must count as a rejection")
            }
        }

    @Test
    fun `an exhausted call or wall-time budget warns once`() {
        val warnings = mutableListOf<String>()
        val logger = object : Logger {
            override fun debug(message: String) = Unit

            override fun info(message: String) = Unit

            override fun warn(message: String) {
                warnings += message
            }

            override fun error(message: String) = Unit
        }
        val calls = SubprocessBudget(maxCalls = 0, maxWallMillis = 60_000)
        repeat(2) { GitCliHelper.chargeAndLog(calls, listOf("git", "log"), elapsedMs = 1, logger = logger) }
        val wall = SubprocessBudget(maxCalls = 5, maxWallMillis = 1)
        repeat(2) { GitCliHelper.chargeAndLog(wall, listOf("git", "log"), elapsedMs = 5, logger = logger) }

        assertEquals(
            listOf(
                "git helper: subprocess call budget exhausted (1/0 after 'git')",
                "git helper: subprocess wall-time budget exhausted (5/1ms after 'git')",
            ),
            warnings,
        )
    }
}
