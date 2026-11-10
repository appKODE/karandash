package io.karandash.loader.bridge.modules

import io.karandash.api.ModuleType
import io.karandash.api.module.ReporterModule
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

internal class BridgeReporterModule(
    transport: BridgeTransport,
) : BridgePart(transport),
    ReporterModule {
    override val type: ModuleType get() = ModuleType.REPORTER
    private val json = KarandashJson.Default

    override val formatId: String get() = transport.getProperty("formatId").ifEmpty { "unknown" }

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

    private companion object {
        const val METHOD_REPORT = "report"
        const val METHOD_RUNTIME_CONFIG = "runtimeReporterConfig"
    }
}
