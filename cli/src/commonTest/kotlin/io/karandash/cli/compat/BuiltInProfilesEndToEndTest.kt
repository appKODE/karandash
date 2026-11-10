package io.karandash.cli.compat

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Every built-in profile resolves and runs end to end through `validate`. */
class BuiltInProfilesEndToEndTest {
    @Test
    fun `every built-in profile validates a merge request end to end`() =
        runTest(timeout = 5.minutes) {
            val dir = TestFiles.createTestTempDirectory("ci8-profiles")
            try {
                TestFiles.writeText(Path(dir, "bad.json"), BAD_MR)
                EXPECTED_RULES.forEach { (profile, expected) ->
                    val config = Path(dir, "$profile.yaml")
                    TestFiles.writeText(config, "profile: $profile\nai:\n  statistics:\n    dir: \"$dir/stats\"\n")
                    val out = Path(dir, "$profile.out.json")
                    val bootstrap = PluginBootstrap(envReader = { null })
                    bootstrap.loadPluginsFromDirs(emptyList())
                    try {
                        val args =
                            listOf(
                                "--no-auto-detect",
                                "--no-cache",
                                "--config",
                                config.toString(),
                                "--mr",
                                Path(dir, "bad.json").toString(),
                                "--format",
                                "json",
                                "--output",
                                out.toString(),
                            )
                        val result = ValidateCommand(bootstrap).test(args)
                        assertFalse(result.output.contains("Unknown profile", ignoreCase = true), "$profile: ${result.output}")
                        // minimal is warning-only: it reports the bad MR but never fails it
                        val expectedExit = if (profile == "minimal") 0 else 1
                        assertEquals(expectedExit, result.statusCode, "$profile exit code: ${result.output}")
                        val rules = RULE_FIELD.findAll(TestFiles.readText(out)).map { it.groupValues[1] }.toSet()
                        assertTrue(rules.containsAll(expected), "$profile expected $expected but found $rules")
                    } finally {
                        bootstrap.shutdown()
                    }
                }
            } finally {
                TestFiles.deleteRecursively(dir)
            }
        }

    private companion object {
        val BAD_MR = """
            {"id":"bad-1","iid":2,"title":"wip","description":"","source_branch":"Fix/Bad_Branch","target_branch":"main",
             "author":{"username":"u","name":"U","email":"u@example.com"},"state":"opened","draft":false,"labels":[],
             "commits":[{"sha":"abc123def456abc123def456abc123def456abc1","message":"fix stuff","author":{"name":"U","email":"u@example.com"},"timestamp":"2026-01-15T10:00:00Z"}],
             "changes":[{"path":"src/main.kt","changeType":"MODIFIED","additions":5,"deletions":1},{"path":"config/.env","changeType":"MODIFIED","additions":1,"deletions":0}],"approvals":[],"ci_status":"success"}
        """.trimIndent()
        val RULE_FIELD = Regex("\"rule\": \"([^\"]+)\"")
        val EXPECTED_RULES =
            mapOf(
                "minimal" to setOf("security", "titleFormat"),
                "gitflow" to setOf("branchNaming", "titleFormat"),
                "github-flow" to setOf("branchNaming", "titleFormat"),
                "trunk-based" to setOf("branchNaming", "titleFormat"),
                "semantic-versioning" to setOf("commitMessages", "titleFormat"),
                "conventional-commits" to setOf("commitMessages", "titleFormat"),
                "security-conscious" to setOf("fileChanges", "security", "titleFormat"),
            )
    }
}
