package io.karandash.cli.commands.validate

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.errors.ExitCodes
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `validate-local --dump-dir`: the flag reaches the pipeline, which writes the diagnostics and the `.gitignore` there. */
class DumpDirFlagTest {
    private val dir = TestFiles.createTestTempDirectory("karandash-dump-flag")

    @Test
    fun `validate-local --dump-dir writes diagnostic json and a gitignore into the directory`() =
        runTest {
            val config = Path(dir, "config.json").also { TestFiles.writeText(it, """{"rules":{"sizeLimits":{"maxLines":100000}}}""") }
            val dumpDir = Path(dir, "dump")

            val result = ValidateLocalCommand(PluginBootstrap().apply { loadPlugins() })
                .test("--config $config --target HEAD --dump-dir $dumpDir")

            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertTrue(SystemFileSystem.exists(Path(dumpDir, "diagnostic.json")), result.output)
            assertEquals("*\n", TestFiles.readText(Path(dumpDir, ".gitignore")))
        }
}
