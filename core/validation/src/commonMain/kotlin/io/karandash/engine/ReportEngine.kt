package io.karandash.engine

import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.AiReportModule
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.support.rethrowCancellation
import io.karandash.logger.Logger
import io.karandash.logger.debug
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

/**
 * Orchestrates post-validation report generation and metric computation.
 *
 * Runs all registered [AiReportModule]s and [AiMetricModule]s in parallel.
 * Individual failures are logged and skipped so that one broken report
 * does not prevent other reports or metrics from completing.
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
class ReportEngine(
    private val reportModules: List<AiReportModule>,
    private val metricModules: List<AiMetricModule>,
    private val logger: Logger,
) {
    /**
     * Generate all reports and compute all metrics for the given context.
     *
     * @param context Report context containing findings, MR, enrichment, and config.
     * @return Aggregated results from all reports and metrics.
     */
    suspend fun generate(context: ReportContext): ReportEngineResult {
        val available = availableInputs(context)
        logger.debug {
            "ReportEngine: ${reportModules.size} report(s), ${metricModules.size} metric(s); available inputs " +
                available.joinToString { it.name.lowercase() }
        }
        val reports = runReports(context, available)
        val metrics = runMetrics(context, available)
        return ReportEngineResult(reports, metrics)
    }

    /**
     * Inputs [context] can supply; a module whose [AiReportModule.requiredInputs] / [AiMetricModule.requiredInputs]
     * are not all here is skipped with an info line instead of producing a placeholder.
     */
    private fun availableInputs(context: ReportContext): Set<ReportInput> =
        buildSet {
            add(ReportInput.FINDINGS)
            add(ReportInput.MERGE_REQUEST)
            if (context.enrichment !== EnrichmentContext.EMPTY) add(ReportInput.ENRICHMENT)
            if (context.history.isNotEmpty()) add(ReportInput.HISTORY)
        }

    private fun runnable(
        kind: String,
        id: String,
        required: Set<ReportInput>,
        available: Set<ReportInput>,
    ): Boolean {
        val missing = required - available
        if (missing.isNotEmpty()) {
            logger.info(
                "$kind '$id' skipped: needs ${missing.joinToString { it.name.lowercase() }}, not available in this run"
            )
        }
        return missing.isEmpty()
    }

    private suspend fun runReports(
        context: ReportContext,
        available: Set<ReportInput>,
    ): List<ReportResult> =
        coroutineScope {
            reportModules
                .filter { runnable("Report", it.reportId, it.requiredInputs, available) }
                .map { module ->
                    async { executeReport(module, context) }
                }.awaitAll()
                .filterNotNull()
        }

    private suspend fun runMetrics(
        context: ReportContext,
        available: Set<ReportInput>,
    ): List<MetricResult> =
        coroutineScope {
            metricModules
                .filter { runnable("Metric", it.metricId, it.requiredInputs, available) }
                .map { module ->
                    async { executeMetric(module, context) }
                }.awaitAll()
                .filterNotNull()
        }

    @Suppress("TooGenericExceptionCaught") // boundary: report module execution
    private suspend fun executeReport(
        module: AiReportModule,
        context: ReportContext,
    ): ReportResult? =
        try {
            module.generate(context)
        } catch (e: Exception) {
            // boundary: report/metric module is plugin code
            e.rethrowCancellation()
            logger.warn("Report '${module.reportId}' failed: ${e.message}")
            null
        }

    @Suppress("TooGenericExceptionCaught") // boundary: metric module execution
    private suspend fun executeMetric(
        module: AiMetricModule,
        context: ReportContext,
    ): MetricResult? =
        try {
            module.compute(context)
        } catch (e: Exception) {
            // boundary: report/metric module is plugin code
            e.rethrowCancellation()
            logger.warn("Metric '${module.metricId}' failed: ${e.message}")
            null
        }
}

/**
 * Aggregated result from [ReportEngine.generate].
 */
@Serializable
data class ReportEngineResult(
    val reports: List<ReportResult>,
    val metrics: List<MetricResult>,
) {
    companion object {
        val EMPTY = ReportEngineResult(emptyList(), emptyList())
    }
}
