package io.karandash.engine

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.AiReportModule
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.api.context.EnrichmentContext
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportEngineTest {
    private fun makeMR(): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test MR",
            description = null,
            author = User(username = "alice", name = "Alice"),
            sourceBranch = "feature",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            changes = emptyList(),
            commits = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private fun makeContext(): ReportContext =
        ReportContext(
            findings = emptyList(),
            mr = makeMR(),
            enrichment = EnrichmentContext.EMPTY,
            config = JsonObject(emptyMap()),
        )

    private fun stubReport(
        id: String,
        result: ReportResult? = null,
        error: Throwable? = null,
    ): AiReportModule =
        object : AiReportModule {
            override val reportId = id
            override val reportName = "Report $id"
            override val id = "report-$id"
            override val name = "Report $id"
            override val version = "1.0"

            override suspend fun initialize(context: PluginContext) = true

            override suspend fun generate(context: ReportContext): ReportResult {
                if (error != null) throw error
                return result ?: ReportResult(
                    reportId = id,
                    data = JsonObject(mapOf("key" to JsonPrimitive("value"))),
                    formatted = "Report $id output",
                )
            }
        }

    private fun stubMetric(
        id: String,
        result: MetricResult? = null,
        error: Throwable? = null,
    ): AiMetricModule =
        object : AiMetricModule {
            override val metricId = id
            override val metricName = "Metric $id"
            override val unit = "score"
            override val id = "metric-$id"
            override val name = "Metric $id"
            override val version = "1.0"

            override suspend fun initialize(context: PluginContext) = true

            override suspend fun compute(context: ReportContext): MetricResult {
                if (error != null) throw error
                return result ?: MetricResult(
                    metricId = id,
                    value = 42.0,
                    unit = "score",
                    label = "Good",
                )
            }
        }

    @Test
    fun emptyModulesProduceEmptyResult() =
        runTest {
            val engine = ReportEngine(emptyList(), emptyList(), NoOpLogger)
            val result = engine.generate(makeContext())
            assertEquals(ReportEngineResult.EMPTY, result)
        }

    @Test
    fun singleReportModuleGeneratesResult() =
        runTest {
            val engine = ReportEngine(listOf(stubReport("scorecard")), emptyList(), NoOpLogger)
            val result = engine.generate(makeContext())
            assertEquals(1, result.reports.size)
            assertEquals("scorecard", result.reports.first().reportId)
            assertTrue(result.metrics.isEmpty())
        }

    @Test
    fun multipleReportsRunInParallel() =
        runTest {
            val reports = listOf(stubReport("a"), stubReport("b"), stubReport("c"))
            val engine = ReportEngine(reports, emptyList(), NoOpLogger)
            val result = engine.generate(makeContext())
            assertEquals(3, result.reports.size)
            assertEquals(setOf("a", "b", "c"), result.reports.map { it.reportId }.toSet())
        }

    @Test
    fun reportFailureSkippedOthersSucceed() =
        runTest {
            val reports = listOf(
                stubReport("ok"),
                stubReport("bad", error = RuntimeException("boom")),
                stubReport("also-ok"),
            )
            val engine = ReportEngine(reports, emptyList(), NoOpLogger)
            val result = engine.generate(makeContext())
            assertEquals(2, result.reports.size)
            assertEquals(setOf("ok", "also-ok"), result.reports.map { it.reportId }.toSet())
        }

    @Test
    fun singleMetricModuleComputesResult() =
        runTest {
            val engine = ReportEngine(emptyList(), listOf(stubMetric("risk")), NoOpLogger)
            val result = engine.generate(makeContext())
            assertTrue(result.reports.isEmpty())
            assertEquals(1, result.metrics.size)
            assertEquals("risk", result.metrics.first().metricId)
            assertEquals(42.0, result.metrics.first().value)
        }

    @Test
    fun metricFailureSkippedOthersSucceed() =
        runTest {
            val metrics = listOf(
                stubMetric("ok"),
                stubMetric("bad", error = IllegalStateException("broken")),
            )
            val engine = ReportEngine(emptyList(), metrics, NoOpLogger)
            val result = engine.generate(makeContext())
            assertEquals(1, result.metrics.size)
            assertEquals("ok", result.metrics.first().metricId)
        }

    @Test
    fun mixedReportsAndMetricsBothInResult() =
        runTest {
            val engine = ReportEngine(
                listOf(stubReport("summary")),
                listOf(stubMetric("score")),
                NoOpLogger,
            )
            val result = engine.generate(makeContext())
            assertEquals(1, result.reports.size)
            assertEquals("summary", result.reports.first().reportId)
            assertEquals(1, result.metrics.size)
            assertEquals("score", result.metrics.first().metricId)
        }

    @Test
    fun `modules whose required inputs are unavailable are skipped`() =
        runTest {
            val history = object : AiReportModule by stubReport("history") {
                override val requiredInputs = setOf(ReportInput.HISTORY)
            }
            val enrichment = object : AiMetricModule by stubMetric("enriched") {
                override val requiredInputs = setOf(ReportInput.ENRICHMENT)
            }
            val engine = ReportEngine(listOf(history, stubReport("plain")), listOf(enrichment), NoOpLogger)

            val bare = engine.generate(makeContext())
            val enriched = engine.generate(makeContext().copy(enrichment = EnrichmentContext(mapOf("x" to JsonPrimitive(1)))))

            assertEquals(listOf("plain"), bare.reports.map { it.reportId })
            assertTrue(bare.metrics.isEmpty())
            assertEquals(listOf("enriched"), enriched.metrics.map { it.metricId })
        }

    @Test
    fun `history modules run only when the context carries recorded runs`() =
        runTest {
            val history = object : AiMetricModule by stubMetric("trend") {
                override val requiredInputs = setOf(ReportInput.HISTORY)
            }
            val engine = ReportEngine(emptyList(), listOf(history), NoOpLogger)

            val without = engine.generate(makeContext())
            val with = engine.generate(makeContext().copy(history = listOf(JsonObject(mapOf("mrId" to JsonPrimitive("a"))))))

            assertTrue(without.metrics.isEmpty())
            assertEquals(listOf("trend"), with.metrics.map { it.metricId })
        }
}
