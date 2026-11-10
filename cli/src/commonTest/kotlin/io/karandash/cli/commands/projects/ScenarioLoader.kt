package io.karandash.cli.commands.projects

import io.karandash.cli.TestFileHelpers
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.mamoe.yamlkt.Yaml

/**
 * Discovers project directories and loads test scenario files.
 */
object ScenarioLoader {
    private val yaml = Yaml { }

    /**
     * Returns the JIRA key for a project (used for env var override).
     * The base/base-kmp configs have `KARANDASH_JIRA_KEY: null` and test
     * scenarios use "PROJ" as placeholder; child configs set real keys.
     */
    private val projectJiraKeys: Map<String, String> = mapOf(
        "base" to "PROJ",
        "base-kmp" to "PROJ",
        "coinbox" to "CBX",
        "gridmon" to "GRD",
        "ledger" to "LDG",
        "navigator" to "NAV",
        "telecom" to "TEL",
        "wallet" to "WAL",
        "ai-context-quality" to "ACQ",
    )

    /** Returns an envReader that supplies project-specific JIRA key. */
    fun envReaderForProject(project: String): (String) -> String? {
        val jiraKey = projectJiraKeys[project]
        return { name ->
            when (name) {
                "KARANDASH_JIRA_KEY" -> jiraKey
                else -> null
            }
        }
    }

    /**
     * Locates the `projects/` directory. With the test working directory set to
     * the karandash root (cli/build.gradle.kts sets it for JVM and native tests), this is simply `projects/`.
     * Fails if it is missing: a scenario test that cannot find its inputs must not pass by running nothing.
     */
    fun discoverProjectsDir(): Path {
        val candidate = Path("projects")
        if (SystemFileSystem.exists(candidate)) return resolve(candidate)
        // Fallback: Gradle CWD might still be karandash/cli/
        val fallback = Path("..", "projects")
        if (SystemFileSystem.exists(fallback)) return resolve(fallback)
        error("projects/ directory not found from the test working directory (tried ./projects and ../projects)")
    }

    /**
     * a config that extends [configPath] and puts the cache and statistics dirs inside [tempDir], so a run
     * never writes `cache/` or `.karandash/stats` into the checkout the tests run from.
     */
    fun isolatedConfig(
        tempDir: Path,
        configPath: String,
        n: Int,
    ): Path {
        val wrapper = Path(tempDir, "isolated-$n.yaml")
        val yaml = "extends: \"$configPath\"\ncache:\n  dir: \"${Path(tempDir, "cache")}\"\n" +
            "ai:\n  statistics:\n    dir: \"${Path(tempDir, "stats")}\"\n"
        TestFileHelpers.writeFile(wrapper, yaml)
        return wrapper
    }

    /** Returns the absolute config file path for a project. */
    fun configPath(
        projectsDir: Path,
        project: String
    ): String = resolve(Path(projectsDir, project, ".karandash", "config.yaml")).toString()

    /** Loads and parses scenarios from a YAML file. Fails on a missing, unparsable or empty scenario file. */
    fun loadScenarios(
        projectsDir: Path,
        project: String,
        type: String
    ): List<Scenario> {
        val file = Path(projectsDir, project, "tests", "$type-scenarios.yaml")
        check(SystemFileSystem.exists(file)) { "Scenario file not found: $file" }
        val scenarios = yaml.decodeFromString(ScenarioFile.serializer(), readFileContent(file)).scenarios
        check(scenarios.isNotEmpty()) { "Scenario file has no scenarios: $file" }
        return scenarios
    }

    /**
     * Converts a [Scenario] into a MR JSON string compatible with
     * `MergeRequest` deserialization. Builds JSON manually to exercise
     * the full deserialization path as a black-box test.
     */
    fun scenarioToMrJson(scenario: Scenario): String {
        val mr = scenario.mr
        val json = buildJsonObject {
            put("id", "1")
            put("iid", 1)
            put("title", mr.title)
            put("description", mr.description ?: "")
            put("source_branch", mr.branch ?: "feature/test")
            put("target_branch", "main")
            put(
                "author",
                buildJsonObject {
                    put("username", "testuser")
                    put("name", "Test User")
                    put("email", "test@example.com")
                },
            )
            put("state", "opened")
            put("draft", mr.draft)
            put("labels", buildJsonArray { mr.labels.forEach { add(it) } })
            put(
                "commits",
                buildJsonArray {
                    mr.commits.forEachIndexed { index, commit ->
                        add(buildCommitJson(index, commit))
                    }
                },
            )
            put(
                "changes",
                buildJsonArray {
                    mr.changes.forEach { change ->
                        add(buildChangeJson(change))
                    }
                },
            )
        }
        return json.toString()
    }

    private fun buildCommitJson(
        index: Int,
        commit: ScenarioCommit
    ) = buildJsonObject {
        put("sha", "abc${index}def456abc123def456abc123def456abc1")
        put("message", commitMessage(commit))
        put(
            "author",
            buildJsonObject {
                put("name", "Test User")
                put("email", "test@example.com")
            },
        )
        put("timestamp", "2024-01-01T00:00:00Z")
    }

    private fun buildChangeJson(change: ScenarioChange) =
        buildJsonObject {
            put("path", change.path)
            put("changeType", change.changeType)
            put("additions", change.additions)
            put("deletions", change.deletions)
        }

    private fun commitMessage(commit: ScenarioCommit): String =
        if (commit.body.isNullOrEmpty()) {
            commit.subject
        } else {
            "${commit.subject}\n\n${commit.body}"
        }

    private fun resolve(path: Path): Path =
        try {
            SystemFileSystem.resolve(path)
        } catch (_: Exception) {
            path
        }

    private fun readFileContent(path: Path): String =
        SystemFileSystem.source(path).buffered().use { source ->
            source.readByteArray().decodeToString()
        }
}
