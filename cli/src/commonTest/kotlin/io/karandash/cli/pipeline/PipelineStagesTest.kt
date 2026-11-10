package io.karandash.cli.pipeline

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.module.FormatModule
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.RuleModule
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.errors.ExitCodes
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.sdk.Logger
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * [PipelinePreparationStage] and [PipelineExecutionStage] driven through [ValidationPipeline]: config-level and
 * policy gating decide which rules run, findings decide the exit code, and modules first initialized mid-run
 * (formats, outputs) are shut down when the run closes.
 */
class PipelineStagesTest {
    /** Emits one finding of [severity]; records its lifecycle. */
    private class ProbeRule(
        private val severity: Severity,
    ) : RuleModule {
        val events = mutableListOf<String>()
        override val id = PROBE_RULE
        override val name = "Probe"
        override val version = "1.0"

        override suspend fun contributedRules(): Set<String> = setOf(PROBE_RULE)

        override suspend fun initialize(context: PluginContext): Boolean {
            events += "init"
            return true
        }

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> {
            events += "validate"
            return listOf(Finding(severity = severity, message = "probe", rule = ruleId))
        }

        override suspend fun shutdown() {
            events += "shutdown"
        }
    }

    /** A format first initialized when the report is rendered; delegates rendering to [delegate]. */
    private class ProbeFormat(
        private val delegate: FormatModule,
    ) : FormatModule {
        val events = mutableListOf<String>()
        override val id = "probe-format"
        override val name = "Probe format"
        override val version = "1.0"
        override val formatId = "probe-format"

        override suspend fun initialize(context: PluginContext): Boolean {
            events += "init"
            return true
        }

        override suspend fun format(
            report: ValidationReport,
            config: JsonElement,
        ): FormattedReport = delegate.format(report, config)

        override suspend fun shutdown() {
            events += "shutdown"
        }
    }

    /** An enrichment provider that records its lifecycle; [initResult] null makes `initialize` throw. */
    private class ProbeEnrichmentProvider(
        private val initResult: Boolean? = true,
        override val defaultEnabled: Boolean = true,
    ) : EnrichmentProviderModule {
        val events = mutableListOf<String>()
        override val id = PROBE_PROVIDER
        override val name = "Probe enrichment"
        override val version = "1.0"
        override val providedEnrichment = setOf("$PROBE_PROVIDER:data")

        override suspend fun initialize(context: PluginContext): Boolean {
            events += "init"
            return initResult ?: error("probe init failure")
        }

        override suspend fun enrich(
            mr: MergeRequest,
            config: JsonElement,
            upstreamContext: EnrichmentContext,
        ): Map<String, JsonElement> = emptyMap()

        override suspend fun shutdown() {
            events += "shutdown"
        }
    }

    /** Validates a copy of the MR through the run's [RunServiceKeys.RULE_VALIDATOR] once, recording what it returned. */
    private class ValidatorProbeRule : RuleModule {
        var nestedFindings: List<Finding>? = null
        override val id = VALIDATOR_PROBE_RULE
        override val name = "Validator probe"
        override val version = "1.0"

        override suspend fun contributedRules(): Set<String> = setOf(VALIDATOR_PROBE_RULE)

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun shutdown() = Unit

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> {
            if (mr.title == NESTED_TITLE) return listOf(Finding(Severity.INFO, "nested", ruleId))
            val validator = checkNotNull(RunScopedServices.current()?.get(RunServiceKeys.RULE_VALIDATOR)) {
                "no rule validator in the run"
            }
            nestedFindings = validator.validate(mr.copy(title = NESTED_TITLE))
            return emptyList()
        }
    }

    /** Drops every finding in `afterValidation`. */
    private class DroppingExtension : PipelineExtension {
        override val id = "pipeline-stages-dropping-extension"
        override val name = "Dropping extension"
        override val version = "1.0"
        override val type = ModuleType.PIPELINE_EXTENSION

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun afterValidation(
            findings: List<Finding>,
            mr: MergeRequest,
            config: JsonElement?,
        ): List<Finding> = emptyList()
    }

    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private suspend fun run(
        bootstrap: PluginBootstrap,
        configBody: String,
        format: String = "json",
        strict: Boolean = false,
        logger: Logger = RecordingLogger(),
    ): ValidationPipelineResult {
        val options = ValidationOptions(
            configPathStr = writeConfig(configBody),
            mrDataStr = MR,
            mrId = null,
            platform = null,
            project = null,
            prUrl = null,
            format = format,
            explicitFormat = true,
            outputPathStr = null,
            quiet = true,
            verbose = false,
            strict = strict,
            dryRun = false,
            explain = false,
            fix = false,
            yes = false,
            noAutoDetect = true,
            outputWriter = {},
            sharedBootstrap = bootstrap,
            noCache = true,
            logger = logger,
        )
        return ValidationPipeline(options, bootstrap).execute()
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

    private fun writeConfig(body: String): String {
        val config = Path("cli/build/tmp/pipeline-stages-${body.hashCode()}.yaml")
        SystemFileSystem.createDirectories(checkNotNull(config.parent))
        SystemFileSystem.sink(config).buffered().use { it.writeString("${body}ai:\n  statistics:\n    dir: cli/build/tmp/stats\n") }
        return config.toString()
    }

    @Test
    fun `error finding fails the run and warning passes unless strict`() =
        runTest(timeout = 5.minutes) {
            val rules = "rules:\n  $PROBE_RULE: {}\n"
            withBootstrap { bootstrap ->
                bootstrap.registry().register(ProbeRule(Severity.ERROR))
                assertEquals(ExitCodes.VALIDATION_FAILED, run(bootstrap, rules).exitCode)
            }
            withBootstrap { bootstrap ->
                bootstrap.registry().register(ProbeRule(Severity.WARNING))
                assertEquals(ExitCodes.SUCCESS, run(bootstrap, rules).exitCode)
                assertEquals(ExitCodes.VALIDATION_WARNING, run(bootstrap, rules, strict = true).exitCode)
            }
        }

    @Test
    fun `policy matched at preparation keeps a disabled rule from validating`() =
        runTest(timeout = 5.minutes) {
            withBootstrap { bootstrap ->
                val probe = ProbeRule(Severity.ERROR)
                bootstrap.registry().register(probe)
                // the fixture MR's source branch is feature/runtime-test
                val config = "rules:\n  $PROBE_RULE: {}\npolicies:\n" +
                    "  - when: 'branch_matches(\"^feature/\")'\n    modules: [$PROBE_RULE]\n    enabled: false\n"

                val result = run(bootstrap, config)

                assertEquals(ExitCodes.SUCCESS, result.exitCode)
                assertTrue("validate" !in probe.events, "disabled by policy, got ${probe.events}")
            }
        }

    @Test
    fun `setUp warns when no rule is configured`() =
        runTest(timeout = 5.minutes) {
            withBootstrap { bootstrap ->
                val logger = RecordingLogger()

                run(bootstrap, "rules: {}\n", logger = logger)

                assertTrue("No validation rules configured" in logger.warnings, "warnings: ${logger.warnings}")
            }
        }

    @Test
    fun `format module first initialized mid-run is shut down when the run closes`() =
        runTest(timeout = 5.minutes) {
            withBootstrap { bootstrap ->
                val json = bootstrap.registry().formats().single { it.formatId == "json" }
                val probe = ProbeFormat(json)
                bootstrap.registry().register(probe)
                val config = "rules:\n  $PROBE_RULE: {}\n"
                bootstrap.registry().register(ProbeRule(Severity.WARNING))

                run(bootstrap, config, format = probe.formatId)
                run(bootstrap, config, format = probe.formatId)

                assertEquals(List(2) { listOf("init", "shutdown") }.flatten(), probe.events)
            }
        }

    @Test
    fun `enrichment provider initialized by a run is shut down when the run closes`() =
        runTest(timeout = 5.minutes) {
            withBootstrap { bootstrap ->
                val probe = ProbeEnrichmentProvider()
                bootstrap.registry().register(probe)
                bootstrap.registry().register(ProbeRule(Severity.WARNING))
                val config = "rules:\n  $PROBE_RULE: {}\n"

                run(bootstrap, config)
                run(bootstrap, config)

                assertEquals(List(2) { listOf("init", "shutdown") }.flatten(), probe.events)
            }
        }

    @Test
    fun `enrichment provider whose initialize throws is skipped and still shut down`() =
        runTest(timeout = 5.minutes) {
            withBootstrap { bootstrap ->
                val probe = ProbeEnrichmentProvider(initResult = null)
                bootstrap.registry().register(probe)
                bootstrap.registry().register(ProbeRule(Severity.WARNING))
                val logger = RecordingLogger()

                val result = run(bootstrap, "rules:\n  $PROBE_RULE: {}\n", logger = logger)

                assertEquals(ExitCodes.SUCCESS, result.exitCode)
                assertEquals(listOf("init", "shutdown"), probe.events)
                assertTrue(logger.warnings.any { "'$PROBE_PROVIDER' init failed" in it }, "warnings: ${logger.warnings}")
            }
        }

    @Test
    fun `opt-in enrichment provider runs only when configured under its kebab-case id`() =
        runTest(timeout = 5.minutes) {
            withBootstrap { bootstrap ->
                val probe = ProbeEnrichmentProvider(defaultEnabled = false)
                bootstrap.registry().register(probe)
                bootstrap.registry().register(ProbeRule(Severity.WARNING))
                val rules = "rules:\n  $PROBE_RULE: {}\n"

                run(bootstrap, rules)
                assertTrue(probe.events.isEmpty(), "not configured, got ${probe.events}")

                run(bootstrap, rules + "enrichment:\n  $PROBE_PROVIDER: {}\n")
                assertEquals(listOf("init", "shutdown"), probe.events)
            }
        }

    @Test
    fun `enrichment provider disabled by config or by policy is never initialized`() =
        runTest(timeout = 5.minutes) {
            withBootstrap { bootstrap ->
                val probe = ProbeEnrichmentProvider()
                bootstrap.registry().register(probe)
                bootstrap.registry().register(ProbeRule(Severity.WARNING))
                val rules = "rules:\n  $PROBE_RULE: {}\n"

                run(bootstrap, rules + "enrichment:\n  $PROBE_PROVIDER:\n    enabled: false\n")
                assertFalse("init" in probe.events, "disabled by config, got ${probe.events}")
                run(
                    bootstrap,
                    rules + "policies:\n  - when: 'branch_matches(\"^feature/\")'\n" +
                        "    modules: [$PROBE_PROVIDER]\n    enabled: false\n",
                )

                assertFalse("init" in probe.events, "disabled, got ${probe.events}")
            }
        }

    @Test
    fun `policy naming a kebab-case format id keeps that format from rendering`() =
        runTest(timeout = 5.minutes) {
            withBootstrap { bootstrap ->
                val json = bootstrap.registry().formats().single { it.formatId == "json" }
                val probe = ProbeFormat(json)
                bootstrap.registry().register(probe)
                bootstrap.registry().register(ProbeRule(Severity.WARNING))
                val config = "rules:\n  $PROBE_RULE: {}\npolicies:\n" +
                    "  - when: 'branch_matches(\"^feature/\")'\n    modules: [${probe.id}]\n    enabled: false\n"

                run(bootstrap, config, format = probe.formatId)

                assertTrue(probe.events.isEmpty(), "disabled by policy, got ${probe.events}")
            }
        }

    @Test
    fun `execution stage provides a rule validator that re-runs the configured rules`() =
        runTest(timeout = 5.minutes) {
            withBootstrap { bootstrap ->
                val probe = ValidatorProbeRule()
                bootstrap.registry().register(probe)

                run(bootstrap, "rules:\n  $VALIDATOR_PROBE_RULE: {}\n")

                assertEquals(listOf("nested"), probe.nestedFindings?.map { it.message })
            }
        }

    @Test
    fun `findings an extension drops after validation do not gate the exit code`() =
        runTest(timeout = 5.minutes) {
            withBootstrap { bootstrap ->
                bootstrap.registry().register(ProbeRule(Severity.ERROR))
                bootstrap.registry().register(DroppingExtension())

                val result = run(bootstrap, "rules:\n  $PROBE_RULE: {}\n")

                assertEquals(ExitCodes.SUCCESS, result.exitCode)
                assertEquals(emptyList(), result.validationResult?.findings.orEmpty())
            }
        }

    @Test
    fun `scripts stage runs unless a matching policy switches it off`() =
        runTest(timeout = 5.minutes) {
            val script = Path("cli/build/tmp/pipeline-stages-fail.js")
            SystemFileSystem.sink(script).buffered().use { it.writeString("karandash.fail(\"script failure\");\n") }
            // The script module resolves `path` against the working directory (the repo root in tests).
            val config = "rules: {}\nscripts:\n  - path: $script\n"
            val gated = config + "policies:\n  - when: 'branch_matches(\"^feature/\")'\n    scripts: false\n"
            withBootstrap { bootstrap ->
                val logger = RecordingLogger()
                val ungated = run(bootstrap, config, logger = logger)
                assertEquals(
                    ExitCodes.VALIDATION_FAILED,
                    ungated.exitCode,
                    "findings: ${ungated.validationResult?.findings}, warnings: ${logger.warnings}",
                )
                assertEquals(ExitCodes.SUCCESS, run(bootstrap, gated).exitCode)
            }
        }

    @Test
    fun `rule-enrichment binding naming an unknown rule or enrichment id warns`() =
        runTest(timeout = 5.minutes) {
            withBootstrap { bootstrap ->
                bootstrap.registry().register(ProbeRule(Severity.WARNING))
                val logger = RecordingLogger()
                val config = "rules:\n  $PROBE_RULE: {}\nrule-enrichment:\n  ghostrule:\n    enrich: [\"no-such:data\"]\n"

                run(bootstrap, config, logger = logger)

                assertTrue(
                    logger.warnings.any { "unknown rule id 'ghostrule'" in it } &&
                        logger.warnings.any { "'no-such:data' matches no known enrichment ID" in it },
                    "warnings: ${logger.warnings}",
                )
            }
        }

    @Test
    fun `rule-enrichment binding in another notation binds and a typo gets a suggestion`() =
        runTest(timeout = 5.minutes) {
            withBootstrap { bootstrap ->
                bootstrap.registry().register(ProbeRule(Severity.WARNING))
                val logger = RecordingLogger()
                val config = "rules:\n  $PROBE_RULE: {}\nrule-enrichment:\n" +
                    "  pipeline_stages_probe:\n    enrich: [\"*\"]\n  pipeline-stages-prob:\n    enrich: [\"*\"]\n"

                run(bootstrap, config, logger = logger)

                val unknown = logger.warnings.filter { "unknown rule id" in it }
                assertEquals(1, unknown.size, "warnings: ${logger.warnings}")
                assertTrue("did you mean '$PROBE_RULE'" in unknown.single(), "warnings: ${logger.warnings}")
            }
        }

    private companion object {
        const val MR = "tools/integration-tests/fixtures/test-mr.json"
        const val PROBE_RULE = "pipeline-stages-probe"
        const val PROBE_PROVIDER = "probe-enrichment"
        const val VALIDATOR_PROBE_RULE = "pipeline-stages-validator-probe"
        const val NESTED_TITLE = "nested validation"
    }
}
