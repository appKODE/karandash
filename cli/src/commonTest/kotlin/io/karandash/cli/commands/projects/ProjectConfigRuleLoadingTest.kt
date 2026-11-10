package io.karandash.cli.commands.projects

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.models.support.KarandashJson
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that each project config loads correctly and that rules inherited
 * via `extends:` produce findings as expected. Uses a deliberately invalid
 * MR to trigger multiple rules simultaneously.
 */
class ProjectConfigRuleLoadingTest {
    private lateinit var tempDir: Path
    private val projectsDir = ScenarioLoader.discoverProjectsDir()

    @BeforeTest
    fun setup() {
        tempDir = TestFileHelpers.createTempDir("rule-loading")
    }

    @AfterTest
    fun cleanup() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    @Test
    fun `base project - Cyrillic title triggers language-policy`() =
        runTest {
            val rules = runProbeAndCollectRules("base", "Добавить фичу")
            assertContainsRules(rules, "base", setOf("languagePolicy"))
        }

    @Test
    fun `base project - short title triggers title-format`() =
        runTest {
            val rules = runProbeAndCollectRules("base", "Fix bug")
            assertContainsRules(rules, "base", setOf("titleFormat"))
        }

    @Test
    fun `base project - draft MR triggers draft-prevention`() =
        runTest {
            val rules = runProbeAndCollectRules("base", "Draft: Work in progress", draft = true)
            assertContainsRules(rules, "base", setOf("draftPrevention"))
        }

    @Test
    fun `base project - wrong JIRA key triggers jira-ticket`() =
        runTest {
            val rules = runProbeAndCollectRules("base", "[WAL-999] Add feature")
            assertContainsRules(rules, "base", setOf("jiraTicket"))
        }

    @Test
    fun `base project - too many commits triggers size-limits`() =
        runTest {
            val commits = (1..OVER_MAX_COMMITS).map { ScenarioCommit(subject = "Fix issue #$it") }
            val rules = runProbeAndCollectRules("base", "Fix many things", commits = commits)
            assertContainsRules(rules, "base", setOf("sizeLimits"))
        }

    @Test
    fun `coinbox project inherits language-policy from base`() =
        runTest {
            val rules = runProbeAndCollectRules("coinbox", "Добавить фичу")
            assertContainsRules(rules, "coinbox", setOf("languagePolicy"))
        }

    @Test
    fun `base-kmp project loads context-tag rule`() =
        runTest {
            // Missing platform tag should trigger context-tag
            val rules = runProbeAndCollectRules("base-kmp", "[PROJ-123] Add feature")
            assertContainsRules(rules, "base-kmp", setOf("contextTag"))
        }

    @Test
    fun `navigator project loads context-tag with 3 tags`() =
        runTest {
            val rules = runProbeAndCollectRules("navigator", "[NAV-123] Add feature")
            assertContainsRules(rules, "navigator", setOf("contextTag"))
        }

    @Test
    fun `wallet project inherits context-tag from base-kmp`() =
        runTest {
            val rules = runProbeAndCollectRules("wallet", "[WAL-123] Add feature")
            assertContainsRules(rules, "wallet", setOf("contextTag"))
        }

    private suspend fun runProbeAndCollectRules(
        project: String,
        title: String,
        draft: Boolean = false,
        commits: List<ScenarioCommit> = listOf(ScenarioCommit(subject = "Fix something")),
    ): Set<String> {
        val dir = projectsDir
        val configPath = ScenarioLoader.configPath(dir, project)
        val mrJson = ScenarioLoader.scenarioToMrJson(
            Scenario(
                name = "probe",
                mr = ScenarioMr(title = title, draft = draft, commits = commits),
                expect = "fail",
            ),
        )
        val mrFile = Path(tempDir, "probe-$project-${title.hashCode()}.json")
        TestFileHelpers.writeFile(mrFile, mrJson)
        val outputFile = Path(tempDir, "probe-out-$project-${title.hashCode()}.json")

        val bootstrap = PluginBootstrap(envReader = ScenarioLoader.envReaderForProject(project))
        bootstrap.loadPlugins()
        val args = "--config ${ScenarioLoader.isolatedConfig(
            tempDir,
            configPath,
            0
        )} --mr $mrFile --format json --output $outputFile --quiet"
        println("[RuleLoadingProbe] project=$project title='$title' draft=$draft config=$configPath")
        val result = ValidateCommand(bootstrap).test(args)
        println("[RuleLoadingProbe] exit=${result.statusCode} outputLen=${result.output.length}")
        if (result.statusCode != ExitCodes.VALIDATION_FAILED) {
            println("[RuleLoadingProbe] CLI output: ${result.output.take(2000)}")
            val outputContent = if (TestFileHelpers.exists(
                    outputFile
                )
            ) {
                TestFileHelpers.readFile(outputFile).take(2000)
            } else {
                "<no output file>"
            }
            println("[RuleLoadingProbe] JSON output: $outputContent")
        }

        assertEquals(
            ExitCodes.VALIDATION_FAILED,
            result.statusCode,
            "Probe MR for '$project' should fail validation (title='$title'). " +
                "Exit=${result.statusCode}, output=${result.output.take(500)}",
        )

        return parseRulesFromOutput(outputFile)
    }

    private fun parseRulesFromOutput(outputFile: Path): Set<String> {
        // Called only after exit=VALIDATION_FAILED, so the JSON report must exist and parse.
        check(TestFileHelpers.exists(outputFile)) { "no JSON report written to $outputFile" }
        val json = KarandashJson.Default.parseToJsonElement(TestFileHelpers.readFile(outputFile)).jsonObject
        val findings = checkNotNull(json["findings"]?.jsonArray) { "JSON report has no findings array: $outputFile" }
        val skipped = json["skippedRules"]?.jsonArray.orEmpty()
        return findings.mapNotNull { it.jsonObject["rule"]?.jsonPrimitive?.content }.toSet() +
            skipped.mapNotNull { it.jsonObject["ruleId"]?.jsonPrimitive?.content }
    }

    private fun assertContainsRules(
        actual: Set<String>,
        project: String,
        expected: Set<String>
    ) {
        val missing = expected - actual
        assertTrue(missing.isEmpty(), "Project '$project' missing rules: $missing (found: $actual)")
    }

    companion object {
        /** Base config has maxCommits: 30. */
        private const val OVER_MAX_COMMITS = 31
    }
}
