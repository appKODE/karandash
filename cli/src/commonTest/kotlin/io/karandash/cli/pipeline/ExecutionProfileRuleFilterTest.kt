package io.karandash.cli.pipeline

import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.createTestTempDirectory
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** the active `ai.executionProfile` keeps excluded configured rules from running, through the pipeline. */
class ExecutionProfileRuleFilterTest {
    private class CountingRule(
        override val id: String,
    ) : RuleModule {
        var calls = 0
        override val name = id
        override val version = "1.0"

        override suspend fun contributedRules(): Set<String> = setOf(id)

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> {
            calls++
            return emptyList()
        }
    }

    private suspend fun run(profileYaml: String): Pair<CountingRule, CountingRule> {
        // A fresh cache dir per run: the persistent rule cache would rightly serve the previous run's results.
        val dir = createTestTempDirectory("brk15")
        val config = Path(dir, "brk15-profile.yaml")
        SystemFileSystem.sink(config).buffered().use {
            it.writeString(
                "cache:\n  dir: ${Path(dir, "cache")}\nai:\n  statistics:\n    dir: ${Path(dir, "stats")}\n$profileYaml\n" +
                    "rules:\n  $FAST: {}\n  $SLOW: {}\n"
            )
        }
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        val fast = CountingRule(FAST)
        val slow = CountingRule(SLOW)
        bootstrap.registry().register(fast)
        bootstrap.registry().register(slow)
        val options = ValidationOptions(
            configPathStr = config.toString(),
            mrDataStr = MR,
            mrId = null,
            platform = null,
            project = null,
            prUrl = null,
            format = "json",
            explicitFormat = true,
            outputPathStr = null,
            quiet = true,
            verbose = false,
            strict = false,
            dryRun = false,
            explain = false,
            fix = false,
            yes = false,
            noAutoDetect = true,
            outputWriter = { },
            sharedBootstrap = bootstrap,
            logger = NoOpLogger,
        )
        try {
            ValidationPipeline(options, bootstrap).execute()
        } finally {
            bootstrap.shutdown()
        }
        return fast to slow
    }

    @Test
    fun `a rule the execution profile excludes does not run`() =
        runTest(timeout = 5.minutes) {
            val (fast, slow) = run(
                "  executionProfile: quick\n  executionProfiles:\n    quick:\n      excludeRules: [$SLOW]",
            )

            assertEquals(1, fast.calls)
            assertEquals(0, slow.calls, "the excluded rule ran")
        }

    @Test
    fun `a rule outside the execution profile include list does not run`() =
        runTest(timeout = 5.minutes) {
            val (fast, slow) = run(
                "  executionProfile: quick\n  executionProfiles:\n    quick:\n      includeRules: [$FAST]",
            )

            assertEquals(1, fast.calls)
            assertEquals(0, slow.calls, "the rule outside includeRules ran")
        }

    @Test
    fun `without an execution profile every configured rule runs`() =
        runTest(timeout = 5.minutes) {
            val (fast, slow) = run("")

            assertEquals(1 to 1, fast.calls to slow.calls)
        }

    private companion object {
        const val MR = "tools/integration-tests/fixtures/test-mr.json"
        const val FAST = "brk15-fast"
        const val SLOW = "brk15-slow"
    }
}
