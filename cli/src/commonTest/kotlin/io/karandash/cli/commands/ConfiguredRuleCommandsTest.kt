package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.mutate.MutateCommand
import io.karandash.cli.commands.testrule.TestRuleCommand
import io.karandash.cli.commands.verify.VerifyPolicyCommand
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.support.KarandashJson
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/** `verify-policy`, `mutate` and `test-rule` evaluate the loaded config instead of printing stub output. */
class ConfiguredRuleCommandsTest {
    private val tempDir = TestFileHelpers.createTempDir("karandash-rule-commands")
    private val bootstrap = PluginBootstrap()

    @AfterTest
    fun cleanup() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    private fun config(content: String): String {
        val path = Path(tempDir, "config.yaml")
        TestFileHelpers.writeFile(path, content.trimIndent())
        return path.toString()
    }

    private val titleConfig = """
        rules:
          titleFormat:
            pattern: "^(feat|fix): .+"
    """

    private fun mr(title: String) =
        MergeRequest(
            id = "1",
            iid = 1,
            title = title,
            description = null,
            sourceBranch = "feature/x",
            targetBranch = "main",
            author = User(username = "dev", name = "Dev"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
        )

    @Test
    fun `verify-policy fails on contradicting policies`() =
        runTest {
            bootstrap.loadPlugins()
            val path = config(
                """
                rules:
                  titleFormat:
                    pattern: "^feat: .+"
                policies:
                  - when: 'is_draft'
                    modules: [titleFormat]
                    severity: warning
                  - when: 'is_draft'
                    modules: [titleFormat]
                    severity: error
                """,
            )
            val result = VerifyPolicyCommand(ConfiguredRuleRunner.source(bootstrap)).test(listOf("--file", path))
            assertNotEquals(0, result.statusCode, result.output)
        }

    @Test
    fun `verify-policy passes non-conflicting policies`() =
        runTest {
            bootstrap.loadPlugins()
            val path = config(
                """
                rules:
                  titleFormat:
                    pattern: "^feat: .+"
                policies:
                  - when: 'is_draft'
                    severity: warning
                """,
            )
            val result = VerifyPolicyCommand(ConfiguredRuleRunner.source(bootstrap)).test(listOf("--file", path))
            assertEquals(0, result.statusCode, result.output)
        }

    @Test
    fun `mutate runs the configured rules against each mutation`() =
        runTest {
            bootstrap.loadPlugins()
            val result = MutateCommand(ConfiguredRuleRunner.source(bootstrap)).test(listOf("--file", config(titleConfig)))
            assertContains(result.output, "[CAUGHT] bad-title-format")
            assertContains(result.output, "[ESCAPED] no-description")
        }

    @Test
    fun `verify-policy accepts --config as the config path`() =
        runTest {
            bootstrap.loadPlugins()
            val path = config(
                """
                policies:
                  - when: 'is_draft'
                    severity: warning
                  - when: 'is_draft'
                    severity: error
                """,
            )
            val result = VerifyPolicyCommand(ConfiguredRuleRunner.source(bootstrap)).test(listOf("--config", path))
            assertNotEquals(0, result.statusCode, result.output)
            assertContains(result.output, "severity")
        }

    @Test
    fun `mutate --rule counts a mutation as caught when that rule fires`() =
        runTest {
            bootstrap.loadPlugins()
            val result = MutateCommand(ConfiguredRuleRunner.source(bootstrap))
                .test(listOf("--file", config(titleConfig), "--rule", "titleFormat", "--synthetic-mrs", "9"))
            assertEquals(0, result.statusCode, result.output)
            assertContains(result.output, "/9 caught)")
            assertContains(result.output, "[CAUGHT] bad-title-format#1")
            assertContains(result.output, "[CAUGHT] bad-title-format#2")
            assertContains(result.output, "[ESCAPED] no-description#1")
            assertContains(result.output, "Target rule: titleFormat")
        }

    @Test
    fun `mutate --synthetic-mrs below the template count runs that many`() =
        runTest {
            bootstrap.loadPlugins()
            val result = MutateCommand(ConfiguredRuleRunner.source(bootstrap))
                .test(listOf("--file", config(titleConfig), "--synthetic-mrs", "2"))
            assertContains(result.output, "(1/2 caught)")
            assertContains(result.output, "[CAUGHT] bad-title-format:")
        }

    @Test
    fun `test-rule replays the configured rule on snapshots`() =
        runTest {
            bootstrap.loadPlugins()
            val good = Path(tempDir, "good.json")
            val bad = Path(tempDir, "bad.json")
            TestFileHelpers.writeFile(good, KarandashJson.Default.encodeToString(MergeRequest.serializer(), mr("feat: ok")))
            TestFileHelpers.writeFile(bad, KarandashJson.Default.encodeToString(MergeRequest.serializer(), mr("stuff")))
            val result = TestRuleCommand(ConfiguredRuleRunner.source(bootstrap)).test(
                listOf("titleFormat", "--file", config(titleConfig), "--snapshot", good.toString(), "--snapshot", bad.toString()),
            )
            assertEquals(0, result.statusCode, result.output)
            assertContains(result.output, "Replayed: 2 MR(s)")
            assertContains(result.output, "Pass: 1 | Fail: 1")
        }

    @Test
    fun `test-rule exits non-zero when the rule is not configured`() =
        runTest {
            bootstrap.loadPlugins()
            val snapshot = Path(tempDir, "mr.json")
            TestFileHelpers.writeFile(snapshot, KarandashJson.Default.encodeToString(MergeRequest.serializer(), mr("x")))
            val result = TestRuleCommand(ConfiguredRuleRunner.source(bootstrap)).test(
                listOf("branchNaming", "--file", config(titleConfig), "--snapshot", snapshot.toString()),
            )
            assertNotEquals(0, result.statusCode, result.output)
        }

    @Test
    fun `fetching an MR honors the config's allow_localhost`() =
        runTest {
            bootstrap.loadPlugins()
            val path = config(
                """
                allow_localhost: true
                platforms:
                  gitlab:
                    url: "http://localhost:1"
                    token: "t"
                """,
            )
            val runner = ConfiguredRuleRunner.load(bootstrap, path)
            val error = assertFails { runner.fetchMergeRequest("http://localhost:1/group/project/-/merge_requests/1") }
            assertFalse("failed to initialize" in error.message.orEmpty(), error.message)
        }
}
