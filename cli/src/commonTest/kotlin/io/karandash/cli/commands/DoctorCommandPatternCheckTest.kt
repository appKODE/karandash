package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.doctor.DoctorCommand
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** `doctor` checks the config's `pomsky:` patterns: a compile error fails, lint and complexity findings warn. */
class DoctorCommandPatternCheckTest {
    private val tempDir = TestFileHelpers.createTempDir("karandash-doctor-pattern-test")

    @AfterTest
    fun cleanup() = TestFileHelpers.deleteTempDir(tempDir)

    private suspend fun doctor(pattern: String): Pair<Int, String> {
        val configDir = Path(tempDir, ".karandash")
        SystemFileSystem.createDirectories(configDir)
        TestFileHelpers.writeFile(Path(configDir, "config.yaml"), "rules:\n  titleFormat:\n    pattern: \"$pattern\"\n")
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        val result = DoctorCommand(bootstrap, workingDir = tempDir).test(listOf("--offline"))
        return result.statusCode to result.output
    }

    @Test
    fun `a pomsky compile error fails with the span marked and a hint`() =
        runTest {
            val (status, output) = doctor("pomsky:'a' unknown_ref")
            assertEquals(1, status, output)
            assertContains(output, "[Patterns]\n  FAIL: rules.titleFormat.pattern: pomsky pattern does not compile")
            assertContains(output, "    'a' unknown_ref\n        ^^^^^^^^^^^")
            assertContains(output, "→ How to fix: ")
        }

    @Test
    fun `pomsky lint and complexity findings warn without failing`() =
        runTest {
            val (_, output) = doctor("pomsky:(.* .*)* 'x'")
            assertContains(output, "  WARN: rules.titleFormat.pattern: This expression may cause catastrophic backtracking")
            assertContains(output, "  WARN: rules.titleFormat.pattern: pomsky pattern is complex (score 10)")
            assertContains(output, "→ How to fix: Rewrite to avoid nesting unbounded quantifiers")
        }

    @Test
    fun `clean pomsky patterns pass`() =
        runTest {
            val (_, output) = doctor("pomsky:('feat' | 'fix') ': ' .+")
            assertContains(output, "[Patterns]\n  OK: pomsky patterns compile without warnings")
        }
}
