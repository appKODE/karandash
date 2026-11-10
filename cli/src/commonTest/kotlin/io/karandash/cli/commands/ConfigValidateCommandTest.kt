package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.config.ConfigValidateCommand
import io.karandash.cli.errors.ExitCodes
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** `config validate` exits 0 for a valid file and 2 for a missing, malformed or unsupported one (or a schema mismatch under `--strict`). */
class ConfigValidateCommandTest {
    private val tempDir = TestFileHelpers.createTempDir("karandash-config-validate")
    private val bootstrap = PluginBootstrap(envReader = { null })

    @AfterTest
    fun cleanup() = TestFileHelpers.deleteTempDir(tempDir)

    private fun file(
        name: String,
        content: String,
    ) = Path(tempDir, name).also { TestFileHelpers.writeFile(it, content) }.toString()

    private suspend fun validate(
        path: String,
        vararg flags: String,
    ) = bootstrap.loadPluginsFromDirs(emptyList()).let { ConfigValidateCommand(bootstrap).test(listOf("--file", path) + flags) }

    @Test
    fun `valid file reports its rule count`() =
        runTest {
            val path = file("config.yaml", "rules:\n  titleFormat:\n    pattern: \"^feat: .+\"\n")
            val result = validate(path)
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertContains(result.stdout, "Config is valid: $path (1 rule(s) configured)")
        }

    @Test
    fun `short -f and long --file both select the config`() =
        runTest {
            val path = file("config.yaml", "rules:\n  titleFormat:\n    pattern: \"^feat: .+\"\n")
            bootstrap.loadPluginsFromDirs(emptyList())
            val short = ConfigValidateCommand(bootstrap).test(listOf("-f", path))
            assertEquals(ExitCodes.SUCCESS, short.statusCode, short.output)
            assertContains(short.stdout, "Config is valid: $path")
            val missing = ConfigValidateCommand(bootstrap).test(listOf("-f", Path(tempDir, "nope.yaml").toString()))
            assertEquals(ExitCodes.CONFIG_ERROR, missing.statusCode, missing.output)
        }

    @Test
    fun `malformed yaml is a config error`() =
        runTest {
            val result = validate(file("config.yaml", "rules: [unclosed\n"))
            assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode, result.output)
            assertContains(result.stderr, "Config validation failed:")
        }

    @Test
    fun `missing file is a config error`() =
        runTest {
            val result = validate(Path(tempDir, "absent.yaml").toString())
            assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode, result.output)
            assertContains(result.stderr, "Config validation failed:")
        }

    @Test
    fun `unsupported extension is a config error`() =
        runTest {
            val path = file("config.txt", "rules: {}\n")
            val result = validate(path)
            assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode, result.output)
            assertContains(result.stderr, "Unsupported config format: $path")
        }

    @Test
    fun `missing file reference fails unless validated as a base`() =
        runTest {
            val path = file("base.yaml", "ai:\n  extra-instructions: { file: \"instructions/extra.md\" }\n")

            val plain = validate(path)
            assertEquals(ExitCodes.CONFIG_ERROR, plain.statusCode, plain.output)
            assertContains(plain.stderr, "External value file not found: instructions/extra.md")

            val asBase = validate(path, "--base")
            assertEquals(ExitCodes.SUCCESS, asBase.statusCode, asBase.output)
            assertContains(asBase.stderr, "{ file: \"instructions/extra.md\" } not found")
            assertContains(asBase.stderr, "each consumer must provide it")
        }

    @Test
    fun `a schema mismatch warns and fails under strict`() =
        runTest {
            val path = file("config.yaml", "rules:\n  title-format: 42\n")

            val plain = validate(path)
            assertEquals(ExitCodes.SUCCESS, plain.statusCode, plain.output)
            assertContains(plain.stderr, "[SCHEMA]")

            val strict = validate(path, "--strict")
            assertEquals(ExitCodes.CONFIG_ERROR, strict.statusCode, strict.output)
            assertContains(strict.stderr, "does not match the module schema")
        }

    @Test
    fun `pomsky compile error fails with the span marked and a hint`() =
        runTest {
            val path = file("config.yaml", "rules:\n  titleFormat:\n    pattern: \"pomsky:'a' unknown_ref\"\n")
            val result = validate(path)
            assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode, result.output)
            assertContains(
                result.stderr,
                "rules.titleFormat.pattern: pomsky pattern does not compile: Variable `unknown_ref` doesn't exist"
            )
            assertContains(result.stderr, "    'a' unknown_ref\n        ^^^^^^^^^^^")
            assertContains(result.stderr, "→ How to fix: ")
        }

    @Test
    fun `pomsky lint diagnostics warn with the pomsky help`() =
        runTest {
            val path = file("config.yaml", "rules:\n  titleFormat:\n    pattern: \"pomsky:('a'+)+\"\n")
            val result = validate(path)
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertContains(result.stderr, "Warning: rules.titleFormat.pattern: This expression may cause catastrophic backtracking")
            assertContains(result.stderr, "→ How to fix: Rewrite to avoid nesting unbounded quantifiers")
        }

    /** `config validate` rejects the config patterns `validate` rejects. */
    @Test
    fun `nested quantifier config pattern fails config validate`() =
        runTest {
            val path = file("config.yaml", "patterns:\n  bad: \"(a+)+\"\n")
            val result = validate(path)
            assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode, result.output)
            assertContains(result.stderr, "patterns.bad: Regex has nested quantifiers")
        }
}
