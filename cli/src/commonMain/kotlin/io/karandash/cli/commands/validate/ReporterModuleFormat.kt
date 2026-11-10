package io.karandash.cli.commands.validate

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.EnrichedReporterModule
import io.karandash.api.module.FormatModule
import io.karandash.api.module.ModuleScope
import io.karandash.api.module.ReporterModule
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Runs a [ReporterModule] through the format/output pipeline. No first-party module implements
 * [ReporterModule] since the format/output split, so every one registered is a third-party plugin or a
 * bridged guest: [format] calls [EnrichedReporterModule.reportEnriched] or [ReporterModule.report] with the
 * reporter's `reporters.<formatId>` config, and the returned string (empty for none) is the body the paired
 * output writes.
 *
 * limit: always [ModuleScope.SHARED]; a PER_RUN reporter gets its shared instance, wire its handle if one appears.
 */
internal class ReporterModuleFormat(
    val reporter: ReporterModule,
    private val cliParams: JsonElement = JsonObject(emptyMap()),
) : FormatModule,
    Module by reporter {
    override val type: ModuleType get() = ModuleType.FORMAT
    override val formatId: String get() = reporter.formatId
    override val configKey: String get() = reporter.configKey
    override val scope: ModuleScope get() = ModuleScope.SHARED

    override suspend fun newHandle(context: PluginContext): Module = this

    override suspend fun format(
        report: ValidationReport,
        config: JsonElement,
    ): FormattedReport {
        val runtimeConfig = withRuntimeConfig(config)
        val body =
            if (reporter is EnrichedReporterModule) {
                reporter.reportEnriched(report, runtimeConfig)
            } else {
                reporter.report(report.findings, report.passed, runtimeConfig)
            }
        return FormattedReport(formatId = formatId, body = body.orEmpty())
    }

    /** [config] overlaid by [ReporterModule.runtimeReporterConfig] (`config` as the reporters block, the CLI flags as params). */
    private suspend fun withRuntimeConfig(config: JsonElement): JsonElement {
        val runtime = reporter.runtimeReporterConfig(config, cliParams) as? JsonObject
        val base = config as? JsonObject
        return if (runtime == null || runtime.isEmpty() || base == null) config else JsonObject(base + runtime)
    }
}
