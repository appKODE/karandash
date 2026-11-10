package io.karandash.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.Option
import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.calibrate.CalibrateCommand
import io.karandash.cli.commands.generate.GenerateCommand
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.cli.commands.validate.ValidateExtendsCommand
import io.karandash.cli.commands.validate.ValidateLocalCommand
import io.karandash.cli.config.renderers.registerBuiltInRenderers
import io.karandash.cli.errors.ExitCodes
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Table-driven coverage of the CLI surface.
 *
 * - [every registered option of every command parses] walks the whole Clikt tree and feeds each
 *   option to its command (followed by `--help` so nothing executes): a renamed, duplicated or
 *   malformed option fails here.
 * - The remaining tests pin the behavior of individual flags.
 */
class CliFlagTableTest {
    private lateinit var tempDir: Path
    private var counter = 0

    @BeforeTest
    fun setup() {
        tempDir = TestFileHelpers.createTempDir("cli-flag-table")
    }

    @AfterTest
    fun cleanup() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    private suspend fun bootstrap(): PluginBootstrap =
        PluginBootstrap(envReader = { _ -> null }, onFormatRegistryCreated = ::registerBuiltInRenderers).also {
            it.loadPlugins()
        }

    private fun file(
        name: String,
        content: String,
    ): Path = Path(tempDir, "${counter++}-$name").also { TestFileHelpers.writeFile(it, content) }

    private fun leaves(command: CliktCommand): List<CliktCommand> {
        val subs = command.registeredSubcommands().filterIsInstance<CliktCommand>()
        return if (subs.isEmpty()) listOf(command) else subs.flatMap { leaves(it) } + command
    }

    private fun Option.sampleArgs(): List<String> {
        val name = names.first { it.startsWith("--") }
        return if (nvalues.last == 0) listOf(name) else listOf(name, "1")
    }

    @Test
    fun `every registered option of every command parses`() =
        runTest {
            val root = karandashCommandTree(bootstrap(), CliLogLevel.NORMAL, null, emptyList())
            val checked = mutableListOf<String>()

            for (command in leaves(root) - root) { // root flags are eager (--version) or pre-parsed in main
                for (option in command.registeredOptions()) {
                    if (option.names.none { it.startsWith("--") } || "--help" in option.names) continue
                    val args = option.sampleArgs() + "--help"
                    val result = command.test(args)
                    assertEquals(
                        0,
                        result.statusCode,
                        "${command.commandName} ${option.names}: ${result.output}",
                    )
                    assertTrue("Usage" in result.output, "${command.commandName} ${option.names}")
                    checked += "${command.commandName}${option.names}"
                }
            }

            val expected =
                listOf(
                    "--on-missing-plugin",
                    "--dry-run-enrich",
                    "--diagnostic",
                    "--min-samples",
                    "--init-git",
                    "--gate-on-cached",
                    "--target-fp-rate",
                    "--window",
                    "--against-history",
                    "--synthetic-mrs",
                    "--rule",
                    "--config",
                    "--analyze-cross-mr",
                    "--once",
                    "--rules-file",
                    "--target",
                    "--rules",
                    "--source",
                    "--dependencies",
                    "--key",
                )
            for (flag in expected) {
                assertTrue(checked.any { flag in it }, "flag $flag not discovered in the command tree: $checked")
            }
        }

    private val mrJson = """
        {"id":"1","iid":1,"title":"feat: add thing","description":"d","source_branch":"f","target_branch":"main",
         "author":{"username":"j","name":"J"},"state":"opened","draft":false,"labels":[],"commits":[],"changes":[]}
    """.trimIndent()

    private val configWithUnknownRule = """
        {"rules":{"titleFormat":{"pattern":"^feat: .+"},"no-such-module-xyz":{}}}
    """.trimIndent()

    private suspend fun validate(vararg extra: String): com.github.ajalt.clikt.testing.CliktCommandTestResult {
        val config = file("config.json", configWithUnknownRule)
        val mr = file("mr.json", mrJson)
        return ValidateCommand(bootstrap()).test(
            listOf("--config", config.toString(), "--mr", mr.toString(), "--no-auto-detect") + extra,
        )
    }

    @Test
    fun `on-missing-plugin fail rejects config referencing an uninstalled module`() =
        runTest {
            assertEquals(ExitCodes.CONFIG_ERROR, validate("--on-missing-plugin", "fail").statusCode)
        }

    @Test
    fun `on-missing-plugin with an unknown value is a usage error`() =
        runTest {
            val args = arrayOf("--mr", file("mr.json", mrJson).toString(), "--on-missing-plugin", "bogus")
            assertEquals(ExitCodes.CONFIG_ERROR, dispatchCli(ValidateCommand(bootstrap()), args, CliLogLevel.NORMAL) {})
        }

    @Test
    fun `on-missing-plugin ignore and warn let validation proceed`() =
        runTest {
            assertEquals(ExitCodes.SUCCESS, validate("--on-missing-plugin", "ignore").statusCode)
            assertEquals(ExitCodes.SUCCESS, validate("--on-missing-plugin", "warn").statusCode)
        }

    @Test
    fun `diagnostic writes a json file`() =
        runTest {
            val target = Path(tempDir, "diag.json")

            val result = validate("--on-missing-plugin", "ignore", "--diagnostic", target.toString())

            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertTrue(SystemFileSystem.exists(target), "diagnostic file was not written")
            assertTrue(TestFileHelpers.readFile(target).trimStart().startsWith("{"))
        }

    @Test
    fun `enrich with dry-run-enrich reaches enrichment and never fails validation`() =
        runTest {
            val result = validate("--on-missing-plugin", "ignore", "--enrich", "--dry-run-enrich")

            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        }

    @Test
    fun `rules on validate runs only the named rules`() =
        runTest {
            val result = validate("--on-missing-plugin", "ignore", "--rules", "title-format")

            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        }

    @Test
    fun `validate-local shares the run flags and lists registered formats`() =
        runTest {
            val bootstrap = bootstrap()
            val help = ValidateLocalCommand(bootstrap).test(listOf("--help")).output
            val formats = bootstrap.registry().formats().map { it.formatId }

            for (flag in listOf("--rules", "--watch", "--once", "--dry-run", "--explain", "--profile", "--no-cache", "--fix")) {
                assertTrue(flag in help, "validate-local --help lacks $flag")
            }
            assertTrue(formats.isNotEmpty() && formats.all { it in help }, "validate-local -f help lacks one of $formats: $help")
        }

    @Test
    fun `min-samples gates calibration on run count`() {
        val statsDir = Path(tempDir, "stats").also { SystemFileSystem.createDirectories(it) }

        val result = CalibrateCommand().test(listOf("--stats-dir", statsDir.toString(), "--min-samples", "3"))

        assertEquals(0, result.statusCode)
        assertTrue("need at least 3" in result.output, result.output)
    }

    @Test
    fun `init-git creates a git repository in the generated project`() =
        runTest {
            val result = GenerateCommand(bootstrap()).test(
                listOf("--type", "rule", "--name", "flag-table", "--output", tempDir.toString(), "--init-git"),
            )

            assertEquals(0, result.statusCode, result.output)
            val projectDir = SystemFileSystem.list(tempDir).single { it.name.startsWith("flag-table") }
            assertTrue(SystemFileSystem.exists(Path(projectDir, ".git")), "no .git in $projectDir")
        }

    @Test
    fun `without init-git no git repository is created`() =
        runTest {
            val result = GenerateCommand(bootstrap()).test(
                listOf("--type", "rule", "--name", "flag-table", "--output", tempDir.toString()),
            )

            assertEquals(0, result.statusCode, result.output)
            val projectDir = SystemFileSystem.list(tempDir).single { it.name.startsWith("flag-table") }
            assertTrue(!SystemFileSystem.exists(Path(projectDir, ".git")))
        }
}
