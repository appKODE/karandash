package io.karandash.plugins.ai.core.metrics.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.context.EnrichmentContext
import io.karandash.logger.Logger
import io.karandash.plugins.ai.core.reporters.DoraMetricsCalculator
import io.karandash.plugins.ai.core.reporters.HistoryAwareMetricModule
import io.karandash.plugins.ai.core.reporters.HistoryAwareReportModule
import io.karandash.plugins.ai.core.reporters.modules.AiPredictionReportModule
import io.karandash.plugins.ai.core.reporters.modules.CrossPlatformConsistencyReportModule
import io.karandash.plugins.ai.core.reporters.modules.DeveloperGrowthReportModule
import io.karandash.plugins.ai.core.reporters.modules.DoraMetricsReportModule
import io.karandash.plugins.ai.core.reporters.modules.FlakyRuleReportModule
import io.karandash.plugins.ai.core.reporters.modules.PolicyDriftReportModule
import io.karandash.plugins.ai.core.reporters.modules.RoiReportModule
import io.karandash.plugins.ai.core.reporters.modules.TeamScorecardReportModule
import io.karandash.plugins.ai.core.reporters.runHistory
import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every HISTORY metric and report computes from the run records carried in [ReportContext.history]. */
class HistoryMetricModulesTest {
    private val modules: List<HistoryAwareMetricModule> = listOf(
        ChangeFailureRateMetricModule(),
        CostSavedMetricModule(),
        DeployFrequencyMetricModule(),
        HoursSavedMetricModule(),
        QualityTrendMetricModule(),
        TeamScoreMetricModule(),
    )

    private val reports: List<HistoryAwareReportModule> = listOf(
        AiPredictionReportModule(),
        CrossPlatformConsistencyReportModule(),
        DeveloperGrowthReportModule(),
        DoraMetricsReportModule(),
        FlakyRuleReportModule(),
        PolicyDriftReportModule(),
        RoiReportModule(),
        TeamScorecardReportModule(),
    )

    private val records = listOf(
        record(timestamp = 1_000, errors = 4, warnings = 2),
        record(timestamp = 90_000, errors = 0, warnings = 1),
        record(timestamp = 180_000, errors = 1, warnings = 0),
    )

    private fun record(
        timestamp: Long,
        errors: Int,
        warnings: Int,
    ) = RunRecord(
        timestamp = timestamp,
        mrId = "mr-$timestamp",
        totalFindings = errors + warnings,
        errorCount = errors,
        warningCount = warnings,
        infoCount = 0,
        findingsByRule = mapOf("title-format" to errors + warnings),
        author = "alice",
    )

    private val context = ReportContext(
        findings = emptyList(),
        mr = createTestMergeRequest(),
        enrichment = EnrichmentContext.EMPTY,
        config = JsonObject(emptyMap()),
        history = runHistory(records),
    )

    @Test
    fun `every history metric requires HISTORY`() {
        modules.forEach { assertTrue(ReportInput.HISTORY in it.requiredInputs, it.metricId) }
    }

    @Test
    fun `metrics computed from the context history match the records they encode`() =
        runTest {
            modules.forEach { module ->
                assertEquals(module.compute(context, records), module.compute(context), module.metricId)
            }
        }

    @Test
    fun `reports generated from the context history match the records they encode`() =
        runTest {
            reports.forEach { module ->
                assertTrue(ReportInput.HISTORY in module.requiredInputs, module.reportId)
                assertEquals(module.generate(context, records), module.generate(context), module.reportId)
            }
        }

    @Test
    fun `savings metrics are positive once runs are recorded`() =
        runTest {
            assertTrue(HoursSavedMetricModule().compute(context).value > 0.0)
            assertTrue(CostSavedMetricModule().compute(context).value > 0.0)
            assertEquals(0.0, HoursSavedMetricModule().compute(context.copy(history = emptyList())).value)
        }

    @Test
    fun `DORA metrics and report compute from run history without a warning`() =
        runTest {
            val logger = WarnLogger()
            val deploy = DeployFrequencyMetricModule().apply { initialize(contextWith(logger)) }
            val failure = ChangeFailureRateMetricModule().apply { initialize(contextWith(logger)) }
            val report = DoraMetricsReportModule().apply { initialize(contextWith(logger)) }
            repeat(2) {
                deploy.compute(context)
                failure.compute(context)
                report.generate(context)
            }
            val expected = DoraMetricsCalculator.calculate(records)
            assertEquals(expected.deployFrequencyPerDay, deploy.compute(context).value)
            assertEquals(expected.changeFailureRate, failure.compute(context).value)
            assertTrue(logger.warnings.isEmpty(), "no warning expected: ${logger.warnings}")
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

    private fun contextWith(log: Logger): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = JsonObject(emptyMap())
            override val hostVersion: String = "test"
            override val workingDirectory: String = "/tmp"
            override val logger: Logger = log

            override fun env(name: String): String? = null
        }
}
