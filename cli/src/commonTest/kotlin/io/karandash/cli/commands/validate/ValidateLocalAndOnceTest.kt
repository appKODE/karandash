package io.karandash.cli.commands.validate

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.keys.ModuleIds
import io.karandash.platforms.localgit.LocalGitProvider
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** `validate --once`, `validate --rules-file` and `validate-local [--rules]`. */
class ValidateLocalAndOnceTest {
    private val mrJson = """
        {"id":"1","iid":1,"title":"feat: add thing","description":"d","source_branch":"f","target_branch":"main",
         "author":{"username":"j","name":"J"},"state":"opened","draft":false,"labels":[],"commits":[],"changes":[]}
    """.trimIndent()

    private val dir = TestFiles.createTestTempDirectory("karandash-validate")

    /** Same content, same file: names are content hashes, so a test may write one file twice harmlessly. */
    private fun file(
        suffix: String,
        content: String,
    ) = Path(dir, "file-${content.hashCode().toUInt()}$suffix").also { TestFiles.writeText(it, content) }.toString()

    private suspend fun bootstrap() = PluginBootstrap().apply { loadPlugins() }

    @Test
    fun `local-git ID constant matches the provider`() {
        assertEquals(LocalGitProvider.ID, ModuleIds.LOCAL_GIT)
    }

    @Test
    fun `--once overrides --watch and runs a single validation`() =
        runTest {
            val config = file(".json", """{"rules":{"titleFormat":{"pattern":"^STRICT-.*"}}}""")
            val mr = file(".json", mrJson)
            val result = ValidateCommand(bootstrap()).test("--config $config --mr $mr --watch --once")
            assertEquals(ExitCodes.VALIDATION_FAILED, result.statusCode, result.output)
        }

    @Test
    fun `--rules-file is an alias of --config`() =
        runTest {
            val config = file(".json", """{"rules":{"titleFormat":{"pattern":"^STRICT-.*"}}}""")
            val mr = file(".json", mrJson)
            val result = ValidateCommand(bootstrap()).test("--rules-file $config --mr $mr")
            assertEquals(ExitCodes.VALIDATION_FAILED, result.statusCode, result.output)
        }

    // The working copy is this repository; target HEAD gives an MR with no commits and no changes.
    // No `platforms: local-git: true`: explicit selection works without enabling the provider.
    private val localConfig = """
        {"rules":{"titleFormat":{"pattern":"^STRICT-.*"},"sizeLimits":{"maxLines":100000}}}
    """.trimIndent()

    @Test
    fun `validate-local runs every configured rule against the local working copy`() =
        runTest {
            val result = ValidateLocalCommand(bootstrap()).test("--config ${file(".json", localConfig)} --target HEAD")
            assertEquals(ExitCodes.VALIDATION_FAILED, result.statusCode, result.output)
        }

    @Test
    fun `validate-local --rules runs only the named rules`() =
        runTest {
            val config = file(".json", localConfig)
            val result = ValidateLocalCommand(bootstrap()).test("--config $config --target HEAD --rules size-limits")
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        }

    @Test
    fun `validate --rules is not bypassed by a policy override for another rule`() =
        runTest {
            val config = file(
                ".json",
                """
                {"rules":{"titleFormat":{"pattern":"^feat.*"}},
                 "policies":[{"when":"branch_matches(\"^f$\")","override":{"branch-naming":{"pattern":"^nope$"}}}]}
                """.trimIndent(),
            )
            val mr = file(".json", mrJson)

            val all = ValidateCommand(bootstrap()).test("--config $config --mr $mr")
            val selected = ValidateCommand(bootstrap()).test("--config $config --mr $mr --rules title-format")

            assertEquals(ExitCodes.VALIDATION_FAILED, all.statusCode, all.output)
            assertEquals(ExitCodes.SUCCESS, selected.statusCode, selected.output)
        }
}
