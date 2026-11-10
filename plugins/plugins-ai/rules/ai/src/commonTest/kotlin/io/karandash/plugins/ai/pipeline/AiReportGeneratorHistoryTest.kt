package io.karandash.plugins.ai.pipeline

import io.karandash.api.ModuleRegistry
import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.AiReportModule
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.config.AiPipelineConfig
import io.karandash.plugins.ai.core.metrics.modules.HoursSavedMetricModule
import io.karandash.plugins.ai.core.reporters.modules.RoiReportModule
import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies HISTORY-requiring report/metric modules receive recorded runs and are skipped without them. */
class AiReportGeneratorHistoryTest {
    private val registry: ModuleRegistry =
        object : ModuleRegistry by buildTestRegistry() {
            override fun aiReports(): List<AiReportModule> = listOf(RoiReportModule())

            override fun aiMetrics(): List<AiMetricModule> = listOf(HoursSavedMetricModule())
        }

    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "t",
        description = "d",
        sourceBranch = "f",
        targetBranch = "main",
        author = User("u1", "dev", "Developer"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = emptyList(),
    )

    private val history = listOf(
        RunRecord(
            timestamp = 1L,
            mrId = "a",
            totalFindings = 3,
            errorCount = 3,
            warningCount = 0,
            infoCount = 0,
            findingsByRule = emptyMap()
        ),
        RunRecord(
            timestamp = 2L,
            mrId = "b",
            totalFindings = 2,
            errorCount = 2,
            warningCount = 0,
            infoCount = 0,
            findingsByRule = emptyMap()
        ),
    )

    private suspend fun run(history: List<RunRecord>) =
        AiReportGenerator(AiPipelineConfig(), NoOpLogger, history).runReportEngine(
            registry = registry,
            enabledReports = setOf(RoiReportModule.REPORT_ID),
            enabledMetrics = setOf(HoursSavedMetricModule.METRIC_ID),
            mr = mr,
            config = null,
            findings = emptyList(),
        )

    @Test
    fun `history modules are skipped when no runs are recorded`() =
        runTest {
            assertTrue(run(emptyList()).isEmpty())
        }

    @Test
    fun `history modules compute from recorded runs`() =
        runTest {
            val findings = run(history)

            assertEquals(setOf("report:roi", "metric:hours-saved"), findings.map { it.rule }.toSet())
            val hoursSaved = findings.single { it.rule == "metric:hours-saved" }.metadata["value"]?.toDouble() ?: 0.0
            assertTrue(hoursSaved > 0.0, "hours-saved should come from history, got $hoursSaved")
        }
}
