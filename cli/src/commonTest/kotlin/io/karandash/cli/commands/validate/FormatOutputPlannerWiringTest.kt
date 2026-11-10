package io.karandash.cli.commands.validate

import io.karandash.api.Module
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.FormatModule
import io.karandash.api.module.OutputModule
import io.karandash.api.module.ReporterModule
import io.karandash.config.Config
import io.karandash.engine.ReportDataBuilder
import io.karandash.engine.ValidationResult
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FindingCategory
import io.karandash.models.finding.Severity
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SDK flags and the config carriers reach a real call path; the failure flag works. */
class FormatOutputPlannerWiringTest {
    private class Reporter(
        override val formatId: String,
        override val isDefaultInteractive: Boolean = false,
        override val isDefaultCI: Boolean = false,
        override val supportsLiveMode: Boolean = false,
    ) : ReporterModule {
        override val id = "rep-$formatId"
        override val name = id
        override val version = "1.0"
        val received = mutableListOf<JsonElement>()
        var runtimeArgs: Pair<JsonElement, JsonElement>? = null

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun runtimeReporterConfig(
            reportersConfig: JsonElement,
            cliParams: JsonElement,
        ): JsonElement {
            runtimeArgs = reportersConfig to cliParams
            return JsonObject(mapOf("runtimeMarker" to JsonPrimitive(true)))
        }

        override suspend fun report(
            findings: List<Finding>,
            passed: Boolean,
            config: JsonElement,
        ): String {
            received += config
            return "body"
        }
    }

    private class Format(
        override val formatId: String,
        override val isDefaultInteractive: Boolean = false,
        private val fails: Boolean = false,
    ) : FormatModule {
        override val id = "fmt-$formatId"
        override val name = id
        override val version = "1.0"
        val received = mutableListOf<Pair<ValidationReport, JsonElement>>()

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun format(
            report: ValidationReport,
            config: JsonElement,
        ): FormattedReport {
            check(!fails) { "format broke" }
            received += report to config
            return FormattedReport(formatId = formatId, body = "")
        }
    }

    private class Sink(
        private val fails: Boolean = false,
    ) : OutputModule {
        override val outputId = "sink"
        override val isDefaultInteractive = true
        override val isDefaultPlatform = true
        override val id = "out-sink"
        override val name = id
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun output(
            report: FormattedReport,
            config: JsonElement,
        ) {
            check(!fails) { "output broke" }
        }
    }

    private class WarnLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private val report = ReportDataBuilder.build(ValidationResult(emptyList()))

    private suspend fun registry(vararg modules: Module) =
        ModuleRegistryImpl().apply {
            register(Sink())
            modules.forEach { register(it) }
        }

    private fun planner(
        hasPlatformContext: Boolean = false,
        liveMode: Boolean = false,
        logger: Logger = NoOpLogger,
        overrides: JsonObject = JsonObject(emptyMap()),
    ) = FormatOutputPlanner(null, false, null, hasPlatformContext, logger, overrides, liveMode)

    @Test
    fun `reporters entries come out of selectPairs as config carriers`() =
        runTest {
            val config = Config(reporters = mapOf("ci-fmt" to JsonObject(mapOf("title" to JsonPrimitive("t")))))
            val carriers = planner().selectPairs(config, registry(Format("ci-fmt"))).map { it.toConfigured() }

            val carrier = carriers.single()
            assertEquals("ci-fmt", carrier.formatId)
            assertEquals("sink", carrier.outputId)
            assertEquals(JsonPrimitive("t"), (carrier.formatConfig as JsonObject)["title"])
            assertEquals(carriers, planner().selectConfigured(config, registry(Format("ci-fmt"))))
        }

    @Test
    fun `a reporter module gets its runtime config merged over the entry config`() =
        runTest {
            val reporter = Reporter("custom")
            val overrides = JsonObject(mapOf("quiet" to JsonPrimitive(true)))
            val planner = planner(overrides = overrides)
            val config = Config(reporters = mapOf("custom" to JsonObject(mapOf("title" to JsonPrimitive("t")))))

            planner.runPairs(planner.selectPairs(config, registry(reporter)), report)

            val received = reporter.received.single() as JsonObject
            assertEquals(JsonPrimitive(true), received["runtimeMarker"])
            assertEquals(JsonPrimitive("t"), received["title"])
            assertEquals(overrides, reporter.runtimeArgs?.second)
        }

    @Test
    fun `live mode skips and warns for a reporter without live support`() =
        runTest {
            val logger = WarnLogger()
            val config = Config(reporters = mapOf("custom" to JsonObject(emptyMap()), "live" to JsonObject(emptyMap())))
            val registry = registry(Reporter("custom"), Reporter("live", supportsLiveMode = true))

            val live = planner(liveMode = true, logger = logger).selectPairs(config, registry)
            val once = planner(liveMode = false).selectPairs(config, registry)

            assertEquals(listOf("live"), live.map { it.formatModule.formatId })
            assertTrue(logger.warnings.any { "rep-custom" in it && "live mode" in it }, "${logger.warnings}")
            assertEquals(listOf("custom", "live"), once.map { it.formatModule.formatId })
        }

    @Test
    fun `default selection honours the reporter default flags`() =
        runTest {
            val registry = registry(
                Format("json", isDefaultInteractive = true),
                Reporter("interactive-rep", isDefaultInteractive = true),
                Reporter("ci-rep", isDefaultCI = true),
            )

            val local = planner(hasPlatformContext = false).selectPairs(Config(), registry)
            val ci = planner(hasPlatformContext = true).selectPairs(Config(), registry)

            assertEquals(listOf("interactive-rep"), local.map { it.formatModule.formatId })
            assertEquals(listOf("ci-rep"), ci.map { it.formatModule.formatId })
        }

    @Test
    fun `default selection without reporter flags keeps the format module default`() =
        runTest {
            val pairs = planner().selectPairs(Config(), registry(Format("json", isDefaultInteractive = true), Reporter("plain")))

            assertEquals(listOf("json"), pairs.map { it.formatModule.formatId })
        }

    @Test
    fun `a failing format marks the run as failed`() =
        runTest {
            val planner = planner()
            val pairs = planner.selectPairs(Config(), registry(Format("json", isDefaultInteractive = true, fails = true)))

            planner.runPairs(pairs, report)

            assertTrue(planner.outputFailed)
        }

    @Test
    fun `a failing output marks the run as failed and a clean run does not`() =
        runTest {
            val clean = planner()
            clean.runPairs(clean.selectPairs(Config(), registry(Format("json", isDefaultInteractive = true))), report)
            assertFalse(clean.outputFailed)

            val failing = planner()
            val registry = ModuleRegistryImpl().apply {
                register(Sink(fails = true))
                register(Format("json", isDefaultInteractive = true))
            }
            failing.runPairs(failing.selectPairs(Config(), registry), report)
            assertTrue(failing.outputFailed)
        }

    @Test
    fun `hideFields leaves the field out of the report the format gets and warns about an unknown name`() =
        runTest {
            val logger = WarnLogger()
            val format = Format("ci-fmt")
            val finding =
                Finding(rule = "r", severity = Severity.WARNING, message = "m", category = FindingCategory.STYLE, errorCode = "E1")
            val hide = JsonArray(listOf("category", "nope").map(::JsonPrimitive))
            val config = Config(reporters = mapOf("ci-fmt" to JsonObject(mapOf("hideFields" to hide, "title" to JsonPrimitive("t")))))
            val planner = planner(logger = logger)

            planner.runPairs(planner.selectPairs(config, registry(format)), ReportDataBuilder.build(ValidationResult(listOf(finding))))

            val (seen, seenConfig) = format.received.single()
            assertEquals(null, seen.findings.single().category)
            assertEquals("E1", seen.findings.single().errorCode)
            assertEquals(JsonObject(mapOf("title" to JsonPrimitive("t"))), seenConfig)
            assertEquals(1, logger.warnings.count { "ci-fmt" in it && "nope" in it }, "${logger.warnings}")
        }
}
