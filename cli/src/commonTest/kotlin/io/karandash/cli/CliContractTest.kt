package io.karandash.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.cli.commands.validate.ValidateLocalCommand
import io.karandash.cli.config.renderers.registerBuiltInRenderers
import io.karandash.cli.errors.ExitCodes
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CLI contract: the double message is pinned, the failure flag and `init` schema association keep working. */
class CliContractTest {
    private val work = TestFiles.createTestTempDirectory("karandash-l5-cli")

    private suspend fun bootstrap(): PluginBootstrap =
        PluginBootstrap(envReader = { _ -> null }, onFormatRegistryCreated = ::registerBuiltInRenderers).also { it.loadPlugins() }

    private suspend fun tree() = karandashCommandTree(bootstrap(), CliLogLevel.NORMAL, null, emptyList())

    private fun all(command: CliktCommand): List<CliktCommand> =
        listOf(command) + command.registeredSubcommands().filterIsInstance<CliktCommand>().flatMap { all(it) }

    private fun file(
        name: String,
        content: String,
    ) = Path(work, name).also { TestFiles.writeText(it, content) }

    @Test
    fun `a usage error exits 2 and help exits 0`() =
        runTest {
            val cli = tree()
            assertEquals(ExitCodes.CONFIG_ERROR, dispatchCli(cli, arrayOf("validate", "--no-such-flag"), CliLogLevel.NORMAL) {})
            assertEquals(ExitCodes.CONFIG_ERROR, dispatchCli(tree(), arrayOf("no-such-command"), CliLogLevel.NORMAL) {})
            assertEquals(ExitCodes.SUCCESS, dispatchCli(tree(), arrayOf("validate", "--help"), CliLogLevel.NORMAL) {})
        }

    @Test
    fun `an unknown init preset exits 2 and writes nothing`() =
        runTest {
            val target = Path(work, "preset-never.yaml")
            val code = dispatchCli(tree(), arrayOf("init", "--preset", "nope", "--output", target.toString()), CliLogLevel.NORMAL) {}
            assertEquals(ExitCodes.CONFIG_ERROR, code)
            assertFalse(TestFiles.exists(target))
        }

    @Test
    fun `every built-in command name is reserved from plugin commands`() =
        runTest {
            val names = tree()
                .registeredSubcommands()
                .filterIsInstance<CliktCommand>()
                .map { it.commandName }
                .toSet()
            assertEquals(emptySet(), names - RESERVED_COMMAND_NAMES, "built-in commands missing from RESERVED_COMMAND_NAMES")
            assertTrue("validate-local" in RESERVED_COMMAND_NAMES)
        }

    private val mrJson = """
        {"id":"1","iid":1,"title":"feat: add thing","description":"d","source_branch":"f","target_branch":"main",
         "author":{"username":"j","name":"J"},"state":"opened","draft":false,"labels":[],"commits":[],"changes":[]}
    """.trimIndent()

    @Test
    fun `an output that cannot be written exits 6`() =
        runTest {
            val config = file("brk30.json", """{"rules":{"titleFormat":{"pattern":"^feat: .+"}}}""")
            val mr = file("brk30-mr.json", mrJson)
            val blocker = file("brk30-blocker", "a regular file, not a directory")
            val result = ValidateCommand(bootstrap()).test(
                listOf("--config", config.toString(), "--mr", mr.toString(), "--no-auto-detect", "--output", "$blocker/report.json"),
            )
            assertEquals(ExitCodes.EXECUTION_ERROR, result.statusCode, result.output + result.stderr)
        }

    @Test
    fun `a real validation failure keeps its exit code when the output also fails`() =
        runTest {
            val config = file("brk30c.json", """{"rules":{"titleFormat":{"pattern":"^STRICT-.*"}}}""")
            val mr = file("brk30c-mr.json", mrJson)
            val blocker = file("brk30c-blocker", "a regular file, not a directory")
            val result = ValidateCommand(bootstrap()).test(
                listOf("--config", config.toString(), "--mr", mr.toString(), "--no-auto-detect", "--output", "$blocker/report.json"),
            )
            assertEquals(ExitCodes.VALIDATION_FAILED, result.statusCode, result.output + result.stderr)
        }

    @Test
    fun `an output into a missing directory creates it`() =
        runTest {
            val config = file("brk30b.json", """{"rules":{"titleFormat":{"pattern":"^feat: .+"}}}""")
            val mr = file("brk30b-mr.json", mrJson)
            val target = Path(work, "new-dir/nested/report.json")
            val result = ValidateCommand(bootstrap()).test(
                listOf("--config", config.toString(), "--mr", mr.toString(), "--no-auto-detect", "--output", target.toString()),
            )
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output + result.stderr)
            assertTrue(
                kotlinx.io.files.SystemFileSystem
                    .exists(target),
                "report not written to $target"
            )
        }

    @Test
    fun `no command declares one option name twice`() =
        runTest {
            for (command in all(tree())) {
                val names = command.registeredOptions().flatMap { it.names + it.secondaryNames }
                val duplicates = names.groupBy { it }.filterValues { it.size > 1 }.keys
                assertTrue(duplicates.isEmpty(), "${command.commandName} declares $duplicates twice")
            }
        }

    @Test
    fun `every command that reads a config accepts config and c`() =
        runTest {
            val configReaders = setOf("simulate", "deps", "mutate", "test-rule", "verify-policy")
            val commands = all(tree()).filter { it.commandName in configReaders } +
                all(tree()).first { it.commandName == "validate" && it.registeredOptions().any { o -> "--base" in o.names } }
            assertEquals(configReaders.size + 1, commands.size)
            for (command in commands) {
                val config = command.registeredOptions().firstOrNull { "--config" in it.names }
                assertTrue(config != null && "-c" in config.names, "${command.commandName} lacks --config/-c")
                val result = command.test(listOf("-c", "x.json", "--help"))
                assertEquals(ExitCodes.SUCCESS, result.statusCode, "${command.commandName}: ${result.output}")
            }
        }

    @Test
    fun `file and f keep working on commands that had them but are not in the help`() =
        runTest {
            for (name in setOf("simulate", "deps", "mutate", "test-rule", "verify-policy")) {
                val command = all(tree()).first { it.commandName == name }
                val result = command.test(listOf("--file", "x.json", "--help"))
                assertEquals(ExitCodes.SUCCESS, result.statusCode, "$name --file: ${result.output}")
                assertFalse("--file" in result.output, "$name still advertises --file")
            }
        }

    @Test
    fun `validate keeps f as the format`() =
        runTest {
            val validate = all(tree()).first { it.commandName == "validate" && it.registeredOptions().any { o -> "--format" in o.names } }
            val format = validate.registeredOptions().first { "--format" in it.names }
            assertTrue("-f" in format.names)
            assertTrue(validate.registeredOptions().none { "--file" in it.names })
        }

    @Test
    fun `init writes a schema association`() =
        runTest {
            val target = Path(work, "init-out/config.yaml")
            val result = all(tree()).first { it.commandName == "init" }.test(listOf("--output", target.toString()))
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            val written = TestFiles.readText(target)
            assertTrue("yaml-language-server: \$schema=" in written, written)
        }
}
