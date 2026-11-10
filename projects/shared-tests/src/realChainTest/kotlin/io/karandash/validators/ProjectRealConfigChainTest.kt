package io.karandash.validators

import io.karandash.config.pipeline.ConfigChainParser
import io.karandash.config.pipeline.ConfigChainResolver
import io.karandash.config.pipeline.ConfigFormatRegistry
import io.karandash.config.pipeline.ConfigMapParser
import io.karandash.manifest.ConfigSource
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * every project under `projects/` has a test that loads its REAL
 * `.karandash/config.yaml` through the extends chain (no hand-merged copy), so a project that stops
 * resolving, loses its `env:` block or leaves a placeholder unresolved fails here.
 *
 * The chain loader only reads local files under the working directory or the temp directory, and a Gradle
 * test runs inside the module directory, so the real `projects/<name>/.karandash/config.yaml` files are
 * copied (byte for byte, same relative layout) into a temp directory first.
 */
class ProjectRealConfigChainTest {
    private val secrets = mapOf(
        "KARANDASH_GITLAB_TOKEN" to "test-token",
        "BIFROST_API_KEY" to "test-bifrost-key",
        "BIFROST_BASE_URL" to "https://bifrost.example.com",
        "KARANDASH_BASE_TOKEN" to "test-base-token",
        "KARANDASH_JIRA_URL" to "https://jira.example.com",
        "JIRA_EMAIL" to "ci@example.com",
        "JIRA_TOKEN" to "test-jira-token",
    )

    private val registry = ConfigFormatRegistry(listOf(YamlFormatModule()))
    private lateinit var copyRoot: Path

    @BeforeTest
    fun copyProjectConfigs() {
        val repoRoot = checkNotNull(TestFiles.findRepoRoot()) { "settings.gradle.kts not found above the test working directory" }
        copyRoot = TestFiles.createTestTempDirectory("project-real-chain")
        SystemFileSystem.list(Path(repoRoot, "projects")).forEach { project ->
            val config = Path(project, ".karandash", "config.yaml")
            if (TestFiles.exists(config)) {
                val name = checkNotNull(project.name)
                TestFiles.writeText(Path(copyRoot, "projects", name, ".karandash", "config.yaml"), TestFiles.readText(config))
            }
        }
    }

    @AfterTest
    fun cleanup() {
        TestFiles.deleteRecursively(copyRoot)
    }

    private fun configPath(): Path = Path(copyRoot, "projects", ProjectSpec.NAME, ".karandash", "config.yaml")

    @Test
    fun `real config chain has the expected extends depth`() =
        runTest {
            val chain = ConfigChainResolver(registry).buildChain(ConfigSource(configPath().toString()))

            assertEquals(ProjectSpec.CHAIN_SIZE, chain.size)
            assertTrue(
                chain.entries
                    .last()
                    .source.url
                    .contains(ProjectSpec.NAME),
                "the project config is the last chain entry"
            )
        }

    @Test
    fun `real config chain interpolates the project env block`() =
        runTest {
            val chain = ConfigChainResolver(registry).buildChain(ConfigSource(configPath().toString()))
            val merged = ConfigChainParser(registry).parseAndMerge(chain)
            val result = ConfigMapParser.parseFullConfig(merged, ConfigMapParser.parseEnvBlock(merged).defaults + secrets)

            assertEquals(emptySet(), result.missingEnvVars, "env vars neither in the env block nor in the CI secrets")
            val jiraTicket = assertNotNull(
                result.config.rules.ruleConfigs["jiraTicket"]
                    ?.jsonObject,
                "jiraTicket rule config"
            )
            assertEquals(
                ProjectSpec.KEY,
                jiraTicket["projectKeys"]
                    ?.jsonArray
                    ?.get(0)
                    ?.jsonPrimitive
                    ?.content
            )
            val budget = assertNotNull(
                result.config.ai
                    ?.jsonObject
                    ?.get("costBudget")
                    ?.jsonObject,
                "ai cost-budget"
            )
            // The run cap is `KARANDASH_AI_MAX_TOKENS_CAP` from the base env block, the ceiling a chunked review grows to.
            assertEquals(BASE_TOKEN_CAP, budget["maxTokensPerRun"]?.jsonPrimitive?.content)
        }

    private companion object {
        const val BASE_TOKEN_CAP = "80000"
    }
}
