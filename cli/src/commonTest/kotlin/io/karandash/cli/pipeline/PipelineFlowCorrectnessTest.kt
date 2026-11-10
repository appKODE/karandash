package io.karandash.cli.pipeline

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.ModuleScope
import io.karandash.api.module.PipelineEnvironment
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.RuleModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.errors.ExitCodes
import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Extension hook boundary, onPipelineComplete in finally, cached-finding gate, full cache hit scope. */
class PipelineFlowCorrectnessTest {
    private class Extension(
        val fullHit: List<Finding>? = null,
        val partialCached: List<Finding>? = null,
        val failingHooks: Boolean = false,
        val cancelAfterValidation: Boolean = false,
        val perRun: Boolean = false,
        val appendAfterValidation: Boolean = false,
        val appendBeforeReporting: Boolean = false,
    ) : PipelineExtension {
        var completed = 0
        val seenConfigs = mutableListOf<JsonElement?>()
        var configuredConfig: JsonElement? = null
        val handles = mutableListOf<Extension>()
        override val scope get() = if (perRun) ModuleScope.PER_RUN else ModuleScope.SHARED

        override suspend fun newHandle(context: PluginContext): Module = Extension().also { handles += it }

        override val id = "p6d-extension"
        override val name = "flow-correctness extension"
        override val version = "1.0"
        override val type = ModuleType.PIPELINE_EXTENSION

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun onConfigured(env: PipelineEnvironment) {
            configuredConfig = env.rawConfig
        }

        override suspend fun onPostFetch(
            mr: MergeRequest,
            config: JsonElement?,
        ) {
            seenConfigs += config
            failIfAsked()
        }

        override suspend fun afterValidation(
            findings: List<Finding>,
            mr: MergeRequest,
            config: JsonElement?,
        ): List<Finding> {
            seenConfigs += config
            if (cancelAfterValidation) throw CancellationException("run cancelled")
            failIfAsked()
            return if (appendAfterValidation) findings + EXTRA else findings
        }

        override suspend fun beforeReporting(
            findings: List<Finding>,
            mr: MergeRequest,
        ): List<Finding> = failIfAsked().let { if (appendBeforeReporting) findings + EXTRA else findings }

        override suspend fun onPipelineComplete() {
            completed++
            failIfAsked()
        }

        override fun getCachedFindings(
            mr: MergeRequest,
            config: JsonElement?,
            logger: Logger,
        ): List<Finding>? = fullHit

        override fun incrementalCachedFindings(): List<Finding>? = partialCached

        private fun failIfAsked() {
            if (failingHooks) error("hook failure")
        }
    }

    private class CountingRule(
        override val id: String,
        private val ruleId: String,
        override val usesExtensionComputedConfig: Boolean = false,
    ) : RuleModule {
        var calls = 0
        override val name = id
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

    /** Thread-safe: the pipeline logs from worker threads while the test reads (a plain list tore on native). */
    @OptIn(ExperimentalAtomicApi::class)
    private class CapturingLogger : Logger {
        private val captured = AtomicReference(emptyList<String>())
        val messages: List<String> get() = captured.load()

        override val isDebugEnabled: Boolean get() = true

        private fun record(message: String) {
            while (true) {
                val current = captured.load()
                if (captured.compareAndSet(current, current + message)) return
            }
        }

        override fun debug(message: String) = record(message)

        override fun info(message: String) = record(message)

        override fun warn(message: String) = record(message)

        override fun error(message: String) = record(message)
    }

    private class Run(
        val result: ValidationPipelineResult,
        val rule: CountingRule,
        val owned: CountingRule,
        val logger: CapturingLogger,
    )

    private suspend fun validateWith(
        extension: Extension,
        configure: (ValidationOptions) -> ValidationOptions = { it },
    ): Run {
        val config = Path("cli/build/tmp/p6d-flow.yaml")
        SystemFileSystem.createDirectories(checkNotNull(config.parent))
        SystemFileSystem.sink(config).buffered().use {
            it.writeString(
                "cache:\n  enabled: false\nenv:\n  P6D_BUDGET: \"1234\"\nai:\n  max-token-budget: \"\${P6D_BUDGET}\"\n  statistics:\n    dir: cli/build/tmp/stats\nrules:\n  $RULE: {}\n  $OWNED_RULE: {}\n"
            )
        }
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        val rule = CountingRule("p6d-rule-module", RULE)
        val owned = CountingRule("p6d-owned-module", OWNED_RULE, usesExtensionComputedConfig = true)
        bootstrap.registry().register(rule)
        bootstrap.registry().register(owned)
        bootstrap.registry().register(extension)
        val logger = CapturingLogger()
        val options = configure(
            ValidationOptions(
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
                logger = logger,
            ),
        )
        try {
            return Run(ValidationPipeline(options, bootstrap).execute(), rule, owned, logger)
        } finally {
            bootstrap.shutdown()
        }
    }

    private fun Run.findingRules() =
        result.validationResult
            ?.findings
            .orEmpty()
            .map { it.rule }

    @Test
    fun `failing extension hooks are skipped and the run completes`() =
        runTest(timeout = 5.minutes) {
            val extension = Extension(failingHooks = true)
            val run = validateWith(extension)
            assertEquals(ExitCodes.SUCCESS, run.result.exitCode)
            assertEquals(1, run.rule.calls)
            assertEquals(1, extension.completed)
            assertTrue(run.logger.messages.any { "'p6d-extension' failed in afterValidation" in it })
        }

    @Test
    fun `onPostFetch and afterValidation get the top-level config`() =
        runTest(timeout = 5.minutes) {
            val extension = Extension()
            validateWith(extension)
            assertEquals(2, extension.seenConfigs.size, extension.seenConfigs.toString())
            extension.seenConfigs.forEach { config ->
                val obj = assertIs<JsonObject>(config)
                assertTrue("rules" in obj && "ai" in obj, obj.toString())
            }
        }

    @Test
    fun `onConfigured gets the interpolated ai block`() =
        runTest(timeout = 5.minutes) {
            val extension = Extension()
            validateWith(extension)
            val ai = assertIs<JsonObject>(assertIs<JsonObject>(extension.configuredConfig)["ai"])
            assertEquals("1234", ai["maxTokenBudget"]?.jsonPrimitive?.content, ai.toString())
        }

    @Test
    fun `onPipelineComplete runs once even when the run is aborted after configuration`() =
        runTest(timeout = 5.minutes) {
            val extension = Extension(cancelAfterValidation = true)
            assertFailsWith<CancellationException> { validateWith(extension) }
            assertEquals(1, extension.completed)
        }

    @Test
    fun `cached report findings gate the exit code only with gate-on-cached`() =
        runTest(timeout = 5.minutes) {
            val cachedError = listOf(Finding(Severity.ERROR, "cached error", CACHED_RULE))
            val default = validateWith(Extension(partialCached = cachedError))
            assertEquals(ExitCodes.SUCCESS, default.result.exitCode)

            val gated = validateWith(Extension(partialCached = cachedError)) { it.copy(gateOnCached = true) }
            assertEquals(ExitCodes.VALIDATION_FAILED, gated.result.exitCode)
        }

    @Test
    fun `full cache hit reuses cached findings for extension-owned rules and still runs the others`() =
        runTest(timeout = 5.minutes) {
            val cached = listOf(
                Finding(Severity.WARNING, "cached owned", OWNED_RULE),
                Finding(Severity.WARNING, "stale non-owned", RULE),
            )
            val run = validateWith(Extension(fullHit = cached))
            assertEquals(1, run.rule.calls)
            assertEquals(0, run.owned.calls)
            assertEquals(listOf(OWNED_RULE), run.findingRules())
        }

    @Test
    fun `a PER_RUN extension runs its hooks on the run handle and leaves the registry prototype untouched`() =
        runTest(timeout = 5.minutes) {
            val prototype = Extension(perRun = true)
            validateWith(prototype)
            assertEquals(0, prototype.completed)
            assertEquals(1, prototype.handles.size)
            assertEquals(1, prototype.handles.single().completed)
        }

    @Test
    fun `findings rewritten after validation keep the rule execution results`() =
        runTest(timeout = 5.minutes) {
            val kept = validateWith(Extension()).result.validationResult
            val rewritten = validateWith(Extension(appendAfterValidation = true)).result.validationResult
            assertTrue(checkNotNull(kept).ruleExecutionResults.isNotEmpty())
            assertEquals(
                kept.ruleExecutionResults.map { it.ruleId }.toSet(),
                checkNotNull(rewritten).ruleExecutionResults.map { it.ruleId }.toSet()
            )
            assertTrue(rewritten.findings.any { it.rule == EXTRA.rule })
        }

    @Test
    fun `findings rewritten before reporting keep the rule execution results`() =
        runTest(timeout = 5.minutes) {
            val kept = validateWith(Extension()).result.validationResult
            val rewritten = validateWith(Extension(appendBeforeReporting = true)).result.validationResult
            assertTrue(checkNotNull(kept).ruleExecutionResults.isNotEmpty())
            assertEquals(
                kept.ruleExecutionResults.map { it.ruleId }.toSet(),
                checkNotNull(rewritten).ruleExecutionResults.map { it.ruleId }.toSet()
            )
        }

    @Test
    fun `a finding appended before reporting is reported but stays out of the validation result`() =
        runTest(timeout = 5.minutes) {
            val run = validateWith(Extension(appendBeforeReporting = true))

            assertTrue(EXTRA.rule in run.result.reportOutput.orEmpty(), run.result.reportOutput)
            assertTrue(EXTRA.rule !in run.findingRules(), run.findingRules().toString())
        }

    private companion object {
        val EXTRA = Finding(Severity.WARNING, "extra", "p6d-extra")
        const val MR = "tools/integration-tests/fixtures/test-mr.json"
        const val RULE = "p6d-rule"
        const val OWNED_RULE = "p6d-owned"
        const val CACHED_RULE = "p6d-cached"
    }
}
