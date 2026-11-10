package io.karandash.cli.pipeline

import io.karandash.api.PluginContext
import io.karandash.api.exception.ConfigParseException
import io.karandash.api.module.RuleModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.SingleFlight
import io.karandash.cli.compat.ConsoleCapture
import io.karandash.cli.compat.Golden
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Watch re-runs equal a fresh run, every HTTP client is closed, runs stay isolated. */
class RunLifecycleTest {
    private class Outcome(
        val exit: Int,
        val output: String,
        val findings: List<String>,
        val created: Int,
        val closed: Int,
    )

    /** [captureStdout] swaps the global System.out, so concurrent runs pass false. */
    private suspend fun run(
        bootstrap: PluginBootstrap,
        captureStdout: Boolean = true,
        configure: (ValidationOptions) -> ValidationOptions = { it },
    ): Pair<Outcome, ValidationPipelineResult> {
        val written = StringBuilder()
        val options = configure(
            ValidationOptions(
                configPathStr = CONFIG,
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
                outputWriter = { written.appendLine(it) },
                sharedBootstrap = bootstrap,
                noCache = true,
                logger = NoOpLogger,
            ),
        )
        val pipeline = ValidationPipeline(options, bootstrap)
        val (result, out) = if (captureStdout) {
            ConsoleCapture.capture { pipeline.execute() }.let { it.first to it.second }
        } else {
            pipeline.execute() to ""
        }
        val outcome = Outcome(
            exit = result.exitCode,
            output = Golden.normalize(written.toString() + out + result.reportOutput.orEmpty()),
            findings = result.validationResult
                ?.findings
                .orEmpty()
                .map { "${it.rule}:${it.severity}:${it.message}" },
            created = pipeline.clientLedger.createdCount,
            closed = pipeline.clientLedger.closedCount,
        )
        return outcome to result
    }

    private suspend fun <T> withBootstrap(block: suspend (PluginBootstrap) -> T): T {
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        return try {
            block(bootstrap)
        } finally {
            bootstrap.shutdown()
        }
    }

    @Test
    fun `watch re-run on the fetched MR prints what a fresh run prints`() =
        runTest(timeout = 5.minutes) {
            withBootstrap { bootstrap ->
                val (fresh, freshResult) = run(bootstrap)
                val mr = assertNotNull(freshResult.mr)
                val (watch, _) = run(bootstrap) { it.copy(mrDataStr = null, mrOverride = mr) }
                assertEquals(fresh.exit, watch.exit)
                assertEquals(fresh.findings, watch.findings)
                assertEquals(fresh.output, watch.output)
            }
        }

    @Test
    fun `every HTTP client a run creates is closed when it returns`() =
        runTest(timeout = 5.minutes) {
            withBootstrap { bootstrap ->
                repeat(2) {
                    val (outcome, _) = run(bootstrap)
                    assertEquals(outcome.created, outcome.closed, "created=${outcome.created} closed=${outcome.closed}")
                }
            }
        }

    @Test
    fun `concurrent runs give the sequential result and balance their own ledgers`() =
        runTest(timeout = 5.minutes) {
            withBootstrap { bootstrap ->
                val (sequential, _) = run(bootstrap, captureStdout = false)
                val concurrent = withContext(Dispatchers.Default) {
                    List(3) { async { run(bootstrap, captureStdout = false).first } }.awaitAll()
                }
                for (outcome in concurrent) {
                    assertEquals(sequential.exit, outcome.exit)
                    assertEquals(sequential.findings, outcome.findings)
                    assertEquals(outcome.created, outcome.closed)
                }
            }
        }

    private fun writeConfig(
        name: String,
        body: String
    ): String {
        val config = Path("cli/build/tmp/$name.yaml")
        SystemFileSystem.createDirectories(checkNotNull(config.parent))
        SystemFileSystem.sink(config).buffered().use { it.writeString(body) }
        return config.toString()
    }

    @Test
    fun `overlapping runs with different configs each keep their own named patterns`() =
        runTest(timeout = 5.minutes) {
            // each run defines a pattern name the other lacks; a shared compiler would leave one unresolved
            val passing = writeConfig(
                "run-scope-alpha",
                "patterns:\n  alpha-title: \"^feat: .+\"\nrules:\n  title-format:\n    pattern: \"@alpha-title\"\n",
            )
            val failing = writeConfig(
                "run-scope-beta",
                "patterns:\n  beta-title: \"^JIRA-[0-9]+: .+\"\nrules:\n  title-format:\n    pattern: \"@beta-title\"\n",
            )
            withBootstrap { bootstrap ->
                val configs = listOf(passing, failing)
                val sequential = configs.map { cfg -> run(bootstrap, captureStdout = false) { it.copy(configPathStr = cfg) }.first }
                assertTrue(sequential[0].findings.isEmpty(), "alpha passes on its own: ${sequential[0].findings}")
                assertEquals(1, sequential[1].findings.size, "beta fails on its own: ${sequential[1].findings}")
                val concurrent = withContext(Dispatchers.Default) {
                    List(6) { i ->
                        val cfg = configs[i % 2]
                        async { cfg to run(bootstrap, captureStdout = false) { it.copy(configPathStr = cfg) }.first }
                    }.awaitAll()
                }
                for ((cfg, outcome) in concurrent) {
                    val expected = sequential[configs.indexOf(cfg)]
                    assertEquals(expected.findings, outcome.findings, cfg)
                    assertEquals(expected.exit, outcome.exit, cfg)
                }
            }
        }

    @Test
    fun `a user pattern that does not compile fails the run at load even when no rule uses it`() =
        runTest(timeout = 5.minutes) {
            val bad = writeConfig("dead-16-bad-pattern", "patterns:\n  broken-one: \"(unclosed\"\nrules: {}\n")
            val good = writeConfig(
                "dead-16-good-pattern",
                "patterns:\n  fine: \"^feat: .+\"\n  composed: \"@one-or-more(@fine)\"\nrules: {}\n",
            )
            withBootstrap { bootstrap ->
                val failure = assertFailsWith<ConfigParseException> {
                    run(bootstrap, captureStdout = false) { it.copy(configPathStr = bad) }
                }
                assertTrue("broken-one" in failure.message.orEmpty(), failure.message)
                val (ok, _) = run(bootstrap, captureStdout = false) { it.copy(configPathStr = good) }
                assertEquals(0, ok.exit, ok.output)
            }
        }

    /** A SHARED rule module that records its lifecycle and whether validate ran while initialized. */
    private class ProbeRuleModule : RuleModule {
        val events = mutableListOf<String>()
        private val ready = kotlinx.coroutines.flow.MutableStateFlow(false)
        override val id = "run-lifecycle-probe"
        override val name = "Probe"
        override val version = "1.0"

        override suspend fun contributedRules(): Set<String> = setOf(PROBE_RULE)

        override suspend fun initialize(context: PluginContext): Boolean {
            ready.value = true
            events += "init"
            return true
        }

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> {
            events += if (ready.value) "validate" else "validate-after-shutdown"
            return emptyList()
        }

        override suspend fun shutdown() {
            ready.value = false
            events += "shutdown"
        }
    }

    @Test
    fun `every rule module is shut down per run and re-initialized by the next watch run`() =
        runTest(timeout = 5.minutes) {
            val config = writeConfig("run-lifecycle-probe", "rules:\n  $PROBE_RULE: {}\n")
            withBootstrap { bootstrap ->
                val probe = ProbeRuleModule()
                bootstrap.registry().register(probe)
                val (_, fresh) = run(bootstrap) { it.copy(configPathStr = config) }
                val mr = assertNotNull(fresh.mr)
                run(bootstrap) { it.copy(configPathStr = config, mrDataStr = null, mrOverride = mr) }
                assertEquals(List(2) { listOf("init", "validate", "shutdown") }.flatten(), probe.events)
            }
        }

    @Test
    fun `single flight coalesces triggers that arrive during a run`() {
        val runs = mutableListOf<Int>()
        val self = mutableListOf<SingleFlight>()
        self += SingleFlight {
            runs += runs.size
            // a burst of events while running collapses into one more run
            if (runs.size == 1) repeat(3) { self.single().trigger() }
        }
        self.single().trigger()
        assertEquals(listOf(0, 1), runs)
    }

    private companion object {
        const val MR = "tools/integration-tests/fixtures/test-mr.json"
        const val CONFIG = "${Golden.ROOT}/output/configs/failing.yaml"
        const val PROBE_RULE = "run-lifecycle-probe"
    }
}
