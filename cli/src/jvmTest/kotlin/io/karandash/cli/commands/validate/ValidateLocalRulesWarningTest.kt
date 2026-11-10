package io.karandash.cli.commands.validate

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.errors.ExitCodes
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JVM-only: the warning goes to the process stderr, captured by swapping `System.err`. */
class ValidateLocalRulesWarningTest {
    private val localConfig = """
        {"rules":{"titleFormat":{"pattern":"^STRICT-.*"},"sizeLimits":{"maxLines":100000}}}
    """.trimIndent()

    private fun file(
        suffix: String,
        content: String,
    ) = java.io
        .File(
            TestFiles.createTestTempDirectory("karandash-validate").toString(),
            "rules$suffix"
        ).apply { writeText(content) }
        .toString()

    private suspend fun bootstrap() = PluginBootstrap().apply { loadPlugins() }

    @Test
    fun `validate-local --rules warns about a rule that is not configured`() =
        runTest {
            val config = file(".json", localConfig)
            val stderr = ByteArrayOutputStream()
            val original = System.err
            System.setErr(PrintStream(stderr, true))
            val result =
                try {
                    ValidateLocalCommand(bootstrap()).test("--config $config --target HEAD --rules size-limits,nope")
                } finally {
                    System.setErr(original)
                }
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertTrue("--rules: 'nope' is not a configured rule" in stderr.toString(), stderr.toString())
        }
}
