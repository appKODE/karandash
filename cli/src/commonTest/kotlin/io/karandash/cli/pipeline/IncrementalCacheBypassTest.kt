package io.karandash.cli.pipeline

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.RuleModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** `--no-cache` and `--invalidate-rules` bypass an extension's fully-cached findings so rules re-run. */
class IncrementalCacheBypassTest {
    private class CachingExtension(
        private val incremental: List<Finding>? = null,
    ) : PipelineExtension {
        val invalidated = mutableListOf<Set<String>>()
        override val id = "caching-extension"
        override val name = "caching extension"
        override val version = "1.0"
        override val type = ModuleType.PIPELINE_EXTENSION

        override suspend fun initialize(context: PluginContext): Boolean = true

        override fun getCachedFindings(
            mr: MergeRequest,
            config: JsonElement?,
            logger: Logger,
        ): List<Finding> = listOf(Finding(Severity.WARNING, "cached", CACHED_RULE))

        override suspend fun invalidateRules(ruleIds: Set<String>) {
            invalidated += ruleIds
        }

        override fun incrementalCachedFindings(): List<Finding>? = incremental
    }

    private class CountingRule(
        val ruleId: String = RULE,
    ) : RuleModule {
        var calls = 0
        override val id = "$ruleId-rule"
        override val name = "bypass rule"
        override val version = "1.0"

        override suspend fun contributedRules(): Set<String> = setOf(ruleId)

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

    private suspend fun validateWith(
        configure: (ValidationOptions) -> ValidationOptions
    ): Triple<List<String>, CountingRule, CachingExtension> {
        val rule = CountingRule()
        val extension = CachingExtension()
        val rules = run(configure, listOf(rule), extension)
            .validationResult
            ?.findings
            .orEmpty()
            .map { it.rule }
        return Triple(rules, rule, extension)
    }

    // A fresh cache dir by default: the default one keeps rule results from earlier runs, which would skip the rule.
    private suspend fun run(
        configure: (ValidationOptions) -> ValidationOptions,
        rules: List<CountingRule>,
        extension: CachingExtension,
        cacheDir: Path = TestFileHelpers.createTempDir("cache"),
        mrPath: String = MR,
    ): ValidationPipelineResult {
        val config = Path("cli/build/tmp/bypass.yaml")
        SystemFileSystem.createDirectories(checkNotNull(config.parent))
        val ruleBlock = rules.joinToString("") { "  ${it.ruleId}: {}\n" }
        SystemFileSystem.sink(config).buffered().use {
            it.writeString("ai:\n  statistics:\n    dir: cli/build/tmp/stats\ncache:\n  dir: $cacheDir\nrules:\n$ruleBlock")
        }
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        rules.forEach { bootstrap.registry().register(it) }
        bootstrap.registry().register(extension)
        val options = configure(
            ValidationOptions(
                configPathStr = config.toString(),
                mrDataStr = mrPath,
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
            ),
        )
        try {
            return ValidationPipeline(options, bootstrap).execute()
        } finally {
            bootstrap.shutdown()
        }
    }

    @Test
    fun `without flags the extension cached findings are served and non-AI rules still run`() =
        runTest(timeout = 5.minutes) {
            val (findings, rule, _) = validateWith { it }
            assertEquals(listOf(CACHED_RULE), findings)
            // A full cache hit does not skip rules the extension does not own.
            assertEquals(1, rule.calls)
        }

    @Test
    fun `no-cache runs the rules instead of serving cached findings`() =
        runTest(timeout = 5.minutes) {
            val (findings, rule, _) = validateWith { it.copy(noCache = true) }
            assertTrue(CACHED_RULE !in findings, "cached findings served under --no-cache: $findings")
            assertEquals(1, rule.calls)
        }

    @Test
    fun `invalidate-rules notifies extensions and re-runs the rules`() =
        runTest(timeout = 5.minutes) {
            val (findings, rule, extension) = validateWith { it.copy(invalidateRules = " $RULE , other ") }
            assertEquals(listOf(setOf(RULE, "other")), extension.invalidated)
            assertTrue(CACHED_RULE !in findings, "cached findings served under --invalidate-rules: $findings")
            assertEquals(1, rule.calls)
        }

    @Test
    fun `invalidate-rules re-runs only the named rule and a later plain run re-runs neither`() =
        runTest(timeout = 5.minutes) {
            val cacheDir = TestFileHelpers.createTempDir("shared-cache")
            val a = CountingRule("a")
            val b = CountingRule("b")
            val rules = listOf(a, b)

            run({ it }, rules, CachingExtension(), cacheDir)
            run({ it.copy(invalidateRules = "a") }, rules, CachingExtension(), cacheDir)
            assertEquals(listOf(2, 1), rules.map { it.calls })

            run({ it }, rules, CachingExtension(), cacheDir)
            assertEquals(listOf(2, 1), rules.map { it.calls })
        }

    @Test
    fun `a cached finding of a rule disabled in the MR description is neither reported nor gated`() =
        runTest(timeout = 5.minutes) {
            val fixture = SystemFileSystem.source(Path(MR)).buffered().use { it.readString() }
            val mr = Path("cli/build/tmp/disabled-mr.json")
            SystemFileSystem.sink(mr).buffered().use {
                it.writeString(fixture.replace("\"Testing plugin runtime", "\"karandash-disable $CACHED_RULE\\nTesting plugin runtime"))
            }
            val cached = Finding(Severity.ERROR, "cached", CACHED_RULE)
            val result =
                run(
                    { it.copy(gateOnCached = true) },
                    listOf(CountingRule()),
                    CachingExtension(incremental = listOf(cached)),
                    mrPath = mr.toString(),
                )

            val report = checkNotNull(result.reportOutput) { "no report rendered" }
            assertTrue(CACHED_RULE !in report, "cached finding reported: $report")
            assertEquals(0, result.exitCode)
        }

    private companion object {
        const val MR = "tools/integration-tests/fixtures/test-mr.json"
        const val RULE = "bypass"
        const val CACHED_RULE = "cached"
    }
}
