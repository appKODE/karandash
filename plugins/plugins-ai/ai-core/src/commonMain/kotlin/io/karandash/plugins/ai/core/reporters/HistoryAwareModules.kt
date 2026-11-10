package io.karandash.plugins.ai.core.reporters

import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.AiReportModule
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.json.JsonElement

/**
 * [AiReportModule] that consumes [ReportInput.HISTORY]: [ReportContext.history] decoded into [RunRecord]s
 * (the bounded `StatisticsStore.recentRecords` window, oldest-first).
 */
interface HistoryAwareReportModule : AiReportModule {
    /** Generates the report from [context] and the recent run [history] (oldest-first). */
    suspend fun generate(
        context: ReportContext,
        history: List<RunRecord>,
    ): ReportResult

    override suspend fun generate(context: ReportContext): ReportResult = generate(context, context.runRecords())
}

/** [AiMetricModule] counterpart of [HistoryAwareReportModule]. */
interface HistoryAwareMetricModule : AiMetricModule {
    /** Computes the metric from [context] and the recent run [history] (oldest-first). */
    suspend fun compute(
        context: ReportContext,
        history: List<RunRecord>,
    ): MetricResult

    override suspend fun compute(context: ReportContext): MetricResult = compute(context, context.runRecords())
}

/** [ReportContext.history] as [RunRecord]s. */
fun ReportContext.runRecords(): List<RunRecord> =
    history.map { KarandashJson.IgnoreUnknown.decodeFromJsonElement(RunRecord.serializer(), it) }

/** [records] in the [ReportContext.history] shape. */
fun runHistory(records: List<RunRecord>): List<JsonElement> =
    records.map { KarandashJson.Default.encodeToJsonElement(RunRecord.serializer(), it) }
