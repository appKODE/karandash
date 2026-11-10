package io.karandash.loader.bridge.ai

import io.karandash.api.ModuleType
import io.karandash.api.ai.AiReportModule
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportResult
import io.karandash.api.support.suspendCatching
import io.karandash.loader.bridge.BridgeContextSerializer
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.guarded
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonObject

/**
 * Shared [AiReportModule] implementation using [BridgeTransport].
 *
 * Bridges JS/WASM/native plugins that declare type `ai-report`.
 * If the plugin does not implement the `generate` capability, returns a
 * minimal empty [ReportResult] so the pipeline can continue.
 */
internal class BridgeAiReportModule(
    transport: BridgeTransport,
) : BridgePart(transport),
    AiReportModule {
    override val type: ModuleType get() = ModuleType.AI_REPORT
    private val json = KarandashJson.Default

    override val reportId: String get() = transport.getProperty(PROP_REPORT_ID).ifEmpty { id }
    override val reportName: String get() = transport.getProperty(PROP_REPORT_NAME).ifEmpty { name }

    override suspend fun generate(context: ReportContext): ReportResult {
        if (!transport.hasCapability(METHOD_GENERATE)) return emptyResult()
        return transport.guarded(METHOD_GENERATE, emptyResult()) {
            val contextJson = BridgeContextSerializer.serializeReport(context)
            val result = transport.call(METHOD_GENERATE, contextJson) ?: return emptyResult()
            suspendCatching { json.decodeFromString(ReportResult.serializer(), result) }
                .getOrElse { parseReportResultFallback(result) }
        }
    }

    private fun parseReportResultFallback(jsonStr: String): ReportResult {
        val obj = suspendCatching { json.parseToJsonElement(jsonStr) }.getOrNull()
        if (obj !is JsonObject) return emptyResult()
        val rid = obj["reportId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: reportId
        val formatted = obj["formatted"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: ""
        val data = obj["data"] ?: JsonObject(emptyMap())
        return ReportResult(reportId = rid, data = data, formatted = formatted)
    }

    private fun emptyResult(): ReportResult = ReportResult(reportId = reportId, data = JsonObject(emptyMap()), formatted = "")

    private companion object {
        const val PROP_REPORT_ID = "reportId"
        const val PROP_REPORT_NAME = "reportName"
        const val METHOD_GENERATE = "generate"
    }
}
