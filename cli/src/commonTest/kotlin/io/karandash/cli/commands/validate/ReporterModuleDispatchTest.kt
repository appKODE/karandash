package io.karandash.cli.commands.validate

import io.karandash.api.Module
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.EnrichedReporterModule
import io.karandash.api.module.FormatModule
import io.karandash.api.module.OutputModule
import io.karandash.api.module.ReporterModule
import io.karandash.config.Config
import io.karandash.engine.ReportDataBuilder
import io.karandash.engine.ValidationResult
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.sdk.Logger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A third-party [ReporterModule] (no first-party module implements it since the format/output split) is
 * dispatched through the format/output planner. Dropping [ReporterModuleFormat] from the planner makes these fail.
 */
class ReporterModuleDispatchTest {
    private class RecordingReporter(
        override val formatId: String = "custom",
        override val requiresPlatformContext: Boolean = false,
    ) : ReporterModule {
        override val id = "rep-$formatId"
        override val name = id
        override val version = "1.0"
        val calls = mutableListOf<Triple<List<Finding>, Boolean, JsonElement>>()

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun report(
            findings: List<Finding>,
            passed: Boolean,
            config: JsonElement,
        ): String {
            calls += Triple(findings, passed, config)
            return "custom-body"
        }
    }

    private class EnrichedReporter : EnrichedReporterModule {
        override val formatId = "enriched"
        override val id = "rep-enriched"
        override val name = id
        override val version = "1.0"
        val enriched = mutableListOf<ValidationReport>()

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun report(
            findings: List<Finding>,
            passed: Boolean,
            config: JsonElement,
        ) = "plain"

        override suspend fun reportEnriched(
            report: ValidationReport,
            config: JsonElement,
        ): String {
            enriched += report
            return "enriched-body"
        }
    }

    private class FakeFormat(
        override val formatId: String,
    ) : FormatModule {
        override val id = "fmt-$formatId"
        override val name = id
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun format(
            report: ValidationReport,
            config: JsonElement,
        ) = FormattedReport(formatId = formatId, body = "format-body")
    }

    private class Stdout : OutputModule {
        override val outputId = "stdout"
        override val isDefaultInteractive = true
        override val id = "out-stdout"
        override val name = id
        override val version = "1.0"
        val written = mutableListOf<String>()

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun output(
            report: FormattedReport,
            config: JsonElement,
        ) {
            written += report.body
        }
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

    private val finding = Finding(rule = "r1", message = "m", severity = Severity.ERROR)
    private val report = ReportDataBuilder.build(ValidationResult(listOf(finding)))

    private fun planner(
        format: String? = null,
        hasPlatformContext: Boolean = false,
        logger: Logger = RecordingLogger(),
    ) = FormatOutputPlanner(
        format = format,
        explicitFormat = format != null,
        outputTarget = null,
        hasPlatformContext = hasPlatformContext,
        logger = logger,
    )

    private suspend fun registry(vararg modules: Module) =
        ModuleRegistryImpl().apply {
            register(Stdout())
            modules.forEach { register(it) }
        }

    @Test
    fun `--format with a reporter format id runs report and writes its body`() =
        runTest {
            val reporter = RecordingReporter()
            val registry = registry(reporter)
            val planner = planner(format = "custom")

            val pairs = planner.selectPairs(Config(), registry)
            val body = planner.runPairs(pairs, report)

            assertEquals(listOf("custom" to "stdout"), pairs.map { it.formatModule.formatId to it.outputModule.outputId })
            assertEquals("custom-body", body)
            assertEquals(listOf(Triple(listOf(finding), false, pairs.single().formatConfig)), reporter.calls)
            assertEquals(listOf("custom-body"), (registry.outputs().single() as Stdout).written)
        }

    @Test
    fun `a reporters entry passes its config to report`() =
        runTest {
            val reporter = RecordingReporter()
            val entry = JsonObject(mapOf("title" to JsonPrimitive("t")))
            val planner = planner()

            val pairs = planner.selectPairs(Config(reporters = mapOf("custom" to entry)), registry(reporter))
            planner.runPairs(pairs, report)

            assertEquals("t", (reporter.calls.single().third as JsonObject)["title"]?.let { (it as JsonPrimitive).content })
        }

    @Test
    fun `an enriched reporter gets reportEnriched`() =
        runTest {
            val reporter = EnrichedReporter()
            val planner = planner(format = "enriched")

            val body = planner.runPairs(planner.selectPairs(Config(), registry(reporter)), report)

            assertEquals("enriched-body", body)
            assertEquals(listOf(report), reporter.enriched)
        }

    @Test
    fun `a format module owning the reporter format id wins with a warning`() =
        runTest {
            val reporter = RecordingReporter()
            val logger = RecordingLogger()
            val planner = planner(format = "custom", logger = logger)

            val body = planner.runPairs(planner.selectPairs(Config(), registry(FakeFormat("custom"), reporter)), report)

            assertEquals("format-body", body)
            assertTrue(reporter.calls.isEmpty())
            assertTrue(logger.warnings.any { "rep-custom" in it && "taken by a format module" in it }, "${logger.warnings}")
        }

    @Test
    fun `a reporter requiring platform context is not run without one`() =
        runTest {
            val reporter = RecordingReporter(requiresPlatformContext = true)
            val planner = planner()
            val config = Config(reporters = mapOf("custom" to JsonObject(emptyMap())))

            val pairs = planner.selectPairs(config, registry(FakeFormat("json"), reporter))
            planner.runPairs(pairs, report)

            assertTrue(pairs.none { it.formatModule.formatId == "custom" })
            assertTrue(reporter.calls.isEmpty())
        }
}
