package io.karandash.loader.bridge.modules

import io.karandash.api.ModuleType
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.EnrichedReporterModule
import io.karandash.api.support.suspendCatching
import io.karandash.loader.bridge.BridgeConstants
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.guarded
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Shared [EnrichedReporterModule] implementation using [BridgeTransport].
 */
internal class BridgeEnrichedReporterModule(
    transport: BridgeTransport,
) : BridgePart(transport),
    EnrichedReporterModule {
    override val type: ModuleType get() = ModuleType.REPORTER
    private val json = KarandashJson.Default

    override val formatId: String get() = transport.getProperty(PROP_FORMAT_ID).ifEmpty { "unknown" }

    override suspend fun report(
        findings: List<Finding>,
        passed: Boolean,
        config: JsonElement,
    ): String? {
        if (!transport.hasCapability(METHOD_REPORT)) return null
        return transport.guarded(METHOD_REPORT, null) {
            val findingsJson = json.encodeToString(ListSerializer(Finding.serializer()), findings)
            transport
                .call(METHOD_REPORT, findingsJson, passed.toString(), config.toString())
                ?.takeIf { it != BridgeConstants.NULL_SENTINEL }
        }
    }

    override suspend fun runtimeReporterConfig(
        reportersConfig: JsonElement,
        cliParams: JsonElement,
    ): JsonElement {
        if (!transport.hasCapability(METHOD_RUNTIME_CONFIG)) return JsonObject(emptyMap())
        return transport.guarded(METHOD_RUNTIME_CONFIG, JsonObject(emptyMap())) {
            val result = transport.call(METHOD_RUNTIME_CONFIG, reportersConfig.toString(), cliParams.toString())
            result?.let { suspendCatching { json.parseToJsonElement(it) }.getOrNull() } ?: JsonObject(emptyMap())
        }
    }

    override suspend fun reportEnriched(
        report: ValidationReport,
        config: JsonElement,
    ): String? {
        // a guest without report_enriched is a plain reporter; the host dispatches only this method
        if (!transport.hasCapability(METHOD_REPORT_ENRICHED)) return report(report.findings, report.passed, config)
        return transport.guarded(METHOD_REPORT_ENRICHED, null) {
            val reportJson = KarandashJson.Report.encodeToString(ValidationReport.serializer(), report)
            transport
                .call(METHOD_REPORT_ENRICHED, reportJson, config.toString())
                ?.takeIf { it != BridgeConstants.NULL_SENTINEL }
        }
    }

    private companion object {
        const val PROP_FORMAT_ID = "formatId"
        const val METHOD_REPORT = "report"
        const val METHOD_RUNTIME_CONFIG = "runtimeReporterConfig"
        const val METHOD_REPORT_ENRICHED = "reportEnriched"
    }
}
