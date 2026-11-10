package io.karandash.cli.commands.validate

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.FormatModule
import io.karandash.api.module.OutputModule
import io.karandash.config.Config
import io.karandash.config.DisplayConfig
import io.karandash.config.DisplayConsoleConfig
import io.karandash.config.ReportConfig
import io.karandash.config.ReportTransparencyConfig
import io.karandash.config.TransparencyMode
import io.karandash.config.WalkthroughMode
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behaviour of the `display.console` and `report` keys: what the format planner hands to the selected format. */
class ReportDisplayKeyBehaviourTest {
    private class Format(
        override val formatId: String,
        override val isDefaultInteractive: Boolean = false,
        override val isDefaultPlatform: Boolean = false,
    ) : FormatModule {
        override val id = "fmt-$formatId"
        override val name = id
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun format(
            report: ValidationReport,
            config: JsonElement,
        ): FormattedReport = FormattedReport(formatId = formatId, body = "")
    }

    private class Sink : OutputModule {
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
        ) = Unit
    }

    private class WarningLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private suspend fun formatConfig(
        config: Config,
        format: Format,
        logger: Logger = NoOpLogger,
    ): JsonObject {
        val registry = ModuleRegistryImpl().apply {
            register(Sink())
            register(format)
        }
        val planner = FormatOutputPlanner(null, false, null, format.isDefaultPlatform, logger, JsonObject(emptyMap()), false)
        return planner
            .selectConfigured(config, registry)
            .first { it.formatId == format.formatId }
            .formatConfig.jsonObject
    }

    private suspend fun consoleConfig(display: DisplayConfig) =
        formatConfig(Config(display = display), Format("console-x", isDefaultInteractive = true))

    private suspend fun platformConfig(report: ReportConfig) =
        formatConfig(Config(report = report), Format("platform-x", isDefaultPlatform = true))

    @Test
    fun `display console successMark reaches the interactive format`() =
        runTest {
            val out = consoleConfig(DisplayConfig(console = DisplayConsoleConfig(successMark = "OK")))
            assertEquals(JsonPrimitive("OK"), out["successMark"])
        }

    @Test
    fun `display console failureMark reaches the interactive format`() =
        runTest {
            val out = consoleConfig(DisplayConfig(console = DisplayConsoleConfig(failureMark = "NO")))
            assertEquals(JsonPrimitive("NO"), out["failureMark"])
        }

    @Test
    fun `report language is set on the format config`() =
        runTest {
            assertEquals(JsonPrimitive("ru"), platformConfig(ReportConfig(language = "ru"))["language"])
        }

    @Test
    fun `report transparency reaches the platform format`() =
        runTest {
            val out = platformConfig(ReportConfig(transparency = ReportTransparencyConfig(summary = TransparencyMode.OFF)))
            assertEquals(JsonPrimitive("off"), out.getValue("transparency").jsonObject["summary"])
        }

    @Test
    fun `report transparency inlineWhy reaches the platform format`() =
        runTest {
            val out = platformConfig(ReportConfig(transparency = ReportTransparencyConfig(inlineWhy = true)))
            assertEquals(JsonPrimitive(true), out.getValue("transparency").jsonObject["inlineWhy"])
        }

    @Test
    fun `report transparency flowDiagram reaches the platform format`() =
        runTest {
            val out = platformConfig(ReportConfig(transparency = ReportTransparencyConfig(flowDiagram = true)))
            assertEquals(JsonPrimitive(true), out.getValue("transparency").jsonObject["flowDiagram"])
        }

    @Test
    fun `report walkthrough reaches the platform format`() =
        runTest {
            val out = platformConfig(ReportConfig(walkthrough = WalkthroughMode.COLLAPSED))
            assertEquals(JsonPrimitive("collapsed"), out["walkthrough"])
        }

    @Test
    fun `report style is set on the format config`() =
        runTest {
            val out = platformConfig(ReportConfig(style = "quiet"))
            assertEquals(JsonPrimitive("quiet"), out["style"])
        }

    @Test
    fun `an unknown report style is warned about and not passed on`() =
        runTest {
            val logger = WarningLogger()
            val out = formatConfig(Config(report = ReportConfig(style = "bogus")), Format("platform-x", isDefaultPlatform = true), logger)
            assertNull(out["style"])
            assertTrue(logger.warnings.any { "report.style 'bogus'" in it }, logger.warnings.toString())
        }
}
