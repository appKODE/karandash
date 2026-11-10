package io.karandash.cli.commands.projects

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.models.support.KarandashJson
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Executes scenario-based validation runs against the CLI and collects results.
 */
class ScenarioRunner(
    private val tempDir: Path
) {
    private var fileCounter = 0

    /** Runs all valid scenarios for a project, returning a list of failure descriptions. */
    suspend fun runValidScenarios(
        projectsDir: Path,
        project: String
    ): List<String> {
        val scenarios = ScenarioLoader.loadScenarios(projectsDir, project, "valid")
        val configPath = ScenarioLoader.configPath(projectsDir, project)
        val failures = mutableListOf<String>()
        for (scenario in scenarios) {
            val result = runScenario(configPath, scenario, project)
            if (result.exitCode != ExitCodes.SUCCESS) {
                val details = result.findings.joinToString("; ") { "${it.rule}(${it.severity}): ${it.message}" }
                failures.add("${scenario.name}: expected pass but exit=${result.exitCode} findings=[$details]")
            }
            checkExpectedWarnings(scenario, result, failures)
        }
        return failures
    }

    /** Runs all invalid scenarios for a project, returning a list of failure descriptions. */
    suspend fun runInvalidScenarios(
        projectsDir: Path,
        project: String
    ): List<String> {
        val scenarios = ScenarioLoader.loadScenarios(projectsDir, project, "invalid")
        val configPath = ScenarioLoader.configPath(projectsDir, project)
        val failures = mutableListOf<String>()
        for (scenario in scenarios) {
            val result = runScenario(configPath, scenario, project)
            if (result.exitCode == ExitCodes.SUCCESS && scenario.expectedRules.isNotEmpty()) {
                val allFindings = result.findings.joinToString("; ") { "${it.rule}(${it.severity})" }
                failures.add("${scenario.name}: expected fail but exit=0 findings=[$allFindings] ${result.output}")
                continue
            }
            if (result.exitCode == ExitCodes.CONFIG_ERROR) {
                failures.add("${scenario.name}: got CONFIG_ERROR(2) output=[${result.output.take(MAX_OUTPUT)}]")
                continue
            }
            checkExpectedRules(scenario, result, failures)
            checkExpectedWarnings(scenario, result, failures)
        }
        return failures
    }

    private fun checkExpectedRules(
        scenario: Scenario,
        result: ScenarioResult,
        failures: MutableList<String>
    ) {
        if (scenario.expectedRules.isEmpty()) return
        val actualRules = result.errorRules
        val missing = scenario.expectedRules.filter { expected ->
            val camel = kebabToCamelCase(expected)
            expected !in actualRules && camel !in actualRules
        }
        if (missing.isNotEmpty()) {
            failures.add("${scenario.name}: missing expected rules $missing, actual=$actualRules")
        }
    }

    private fun checkExpectedWarnings(
        scenario: Scenario,
        result: ScenarioResult,
        failures: MutableList<String>
    ) {
        if (scenario.expectedWarnings.isEmpty()) return
        val actualWarnings = result.warningRules
        val missing = scenario.expectedWarnings.filter { expected ->
            val camel = kebabToCamelCase(expected)
            expected !in actualWarnings && camel !in actualWarnings
        }
        if (missing.isNotEmpty()) {
            failures.add("${scenario.name}: missing expected warnings $missing, actual=$actualWarnings")
        }
    }

    private suspend fun runScenario(
        configPath: String,
        scenario: Scenario,
        project: String
    ): ScenarioResult {
        val mrJson = ScenarioLoader.scenarioToMrJson(scenario)
        val mrFile = writeTempFile("mr", ".json", mrJson)
        val outputFile = Path(tempDir, "output-${fileCounter++}.json")
        val bootstrap = PluginBootstrap(envReader = ScenarioLoader.envReaderForProject(project))
        bootstrap.loadPlugins()
        val args = "--config ${ScenarioLoader.isolatedConfig(
            tempDir,
            configPath,
            fileCounter++
        )} --mr $mrFile --format json --output $outputFile --quiet"
        println("[ScenarioRunner] project=$project scenario='${scenario.name}' expect=${scenario.expect}")
        println("[ScenarioRunner] config=$configPath")
        println("[ScenarioRunner] mr.title='${scenario.mr.title}' mr.draft=${scenario.mr.draft}")
        val cliResult = ValidateCommand(bootstrap).test(args)
        val findings = parseFindings(outputFile, cliResult.statusCode)
        println("[ScenarioRunner] exit=${cliResult.statusCode} findings=${findings.size}")
        for (f in findings) {
            println("[ScenarioRunner]   ${f.rule}(${f.severity}): ${f.message}")
        }
        if (cliResult.statusCode == ExitCodes.CONFIG_ERROR) {
            println("[ScenarioRunner] CLI output (config error): ${cliResult.output.take(MAX_OUTPUT)}")
        }
        if (cliResult.output.contains("error", ignoreCase = true) || cliResult.output.contains("exception", ignoreCase = true)) {
            println("[ScenarioRunner] CLI stderr/output snippet: ${cliResult.output.take(MAX_OUTPUT)}")
        }
        return ScenarioResult(exitCode = cliResult.statusCode, findings = findings, output = cliResult.output)
    }

    /**
     * Reads the JSON report. A run that validated (exit 0 or 1) must have written a parsable report with a
     * `findings` array; only a run that stopped earlier (config/provider error) may leave none.
     */
    private fun parseFindings(
        outputFile: Path,
        exitCode: Int
    ): List<FindingInfo> {
        val validated = exitCode == ExitCodes.SUCCESS || exitCode == ExitCodes.VALIDATION_FAILED
        val content = if (TestFileHelpers.exists(outputFile)) TestFileHelpers.readFile(outputFile) else ""
        if (content.isBlank()) {
            check(!validated) { "exit=$exitCode but no JSON report was written to $outputFile" }
            return emptyList()
        }
        val json = KarandashJson.Default.parseToJsonElement(content).jsonObject
        val findings = checkNotNull(json["findings"]?.jsonArray) { "JSON report has no findings array: $outputFile" }
        return findings.map { finding ->
            val obj = finding.jsonObject
            FindingInfo(
                rule = checkNotNull(obj["rule"]?.jsonPrimitive?.content) { "finding without rule: $obj" },
                message = obj["message"]?.jsonPrimitive?.content.orEmpty(),
                severity = checkNotNull(obj["severity"]?.jsonPrimitive?.content) { "finding without severity: $obj" },
            )
        }
    }

    private fun kebabToCamelCase(input: String): String =
        input
            .split('-')
            .mapIndexed { index, part ->
                if (index == 0) part else part.replaceFirstChar { it.uppercaseChar() }
            }.joinToString("")

    private fun writeTempFile(
        prefix: String,
        suffix: String,
        content: String
    ): Path {
        val file = Path(tempDir, "$prefix-${fileCounter++}$suffix")
        TestFileHelpers.writeFile(file, content)
        return file
    }

    companion object {
        private const val MAX_OUTPUT = 2000
    }
}

@Serializable
private data class ScenarioResult(
    val exitCode: Int,
    val findings: List<FindingInfo>,
    val output: String,
) {
    val errorRules: Set<String> get() = findings.filter { it.severity == "error" }.map { it.rule }.toSet()
    val warningRules: Set<String> get() = findings.filter { it.severity == "warning" }.map { it.rule }.toSet()
}

@Serializable
private data class FindingInfo(
    val rule: String,
    val message: String,
    val severity: String,
)
