package io.karandash.loader.bridge.ai

import io.karandash.api.ModuleType
import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.loader.bridge.BridgeContextSerializer
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.guarded
import io.karandash.models.support.KarandashJson

/**
 * Shared [AiMetricModule] implementation using [BridgeTransport].
 *
 * Bridges JS/WASM/native plugins that declare type `ai-metric`.
 * If the plugin does not implement the `compute` capability, returns a
 * zero [MetricResult] so the pipeline can continue.
 */
internal class BridgeAiMetricModule(
    transport: BridgeTransport,
) : BridgePart(transport),
    AiMetricModule {
    override val type: ModuleType get() = ModuleType.AI_METRIC
    private val json = KarandashJson.Default

    override val metricId: String get() = transport.getProperty(PROP_METRIC_ID).ifEmpty { id }
    override val metricName: String get() = transport.getProperty(PROP_METRIC_NAME).ifEmpty { name }
    override val unit: String get() = transport.getProperty(PROP_UNIT)

    override suspend fun compute(context: ReportContext): MetricResult {
        if (!transport.hasCapability(METHOD_COMPUTE)) return zeroResult()
        return transport.guarded(METHOD_COMPUTE, zeroResult()) {
            val contextJson = BridgeContextSerializer.serializeReport(context)
            val result = transport.call(METHOD_COMPUTE, contextJson) ?: return zeroResult()
            json.decodeFromString(MetricResult.serializer(), result)
        }
    }

    private fun zeroResult(): MetricResult = MetricResult(metricId = metricId, value = 0.0, unit = unit)

    private companion object {
        const val PROP_METRIC_ID = "metricId"
        const val PROP_METRIC_NAME = "metricName"
        const val PROP_UNIT = "unit"
        const val METHOD_COMPUTE = "compute"
    }
}
