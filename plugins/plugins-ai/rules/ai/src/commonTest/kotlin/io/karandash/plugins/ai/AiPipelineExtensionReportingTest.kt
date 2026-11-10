@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.AiReportModule
import io.karandash.api.module.PipelineEnvironment
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.plugins.ai.core.metrics.modules.ChangeEntropyMetricModule
import io.karandash.plugins.ai.core.reporters.modules.RiskHeatmapReportModule
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Verifies [AiPipelineExtension.beforeReporting] emits report/metric findings for the enabled sections. */
class AiPipelineExtensionReportingTest {
    private val registry: ModuleRegistry =
        object : ModuleRegistry by buildTestRegistry() {
            override fun aiMetrics(): List<AiMetricModule> = listOf(ChangeEntropyMetricModule())

            override fun aiReports(): List<AiReportModule> = listOf(RiskHeatmapReportModule())
        }

    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "t",
        description = null,
        sourceBranch = "f",
        targetBranch = "main",
        author = User("1", "dev", "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = listOf(FileChange("src/Foo.kt", ChangeType.MODIFIED), FileChange("src/Bar.kt", ChangeType.ADDED)),
    )

    private suspend fun beforeReporting(config: JsonElement): List<String> {
        val statsDir = TestFiles.createTestTempDirectory("karandash-test-reporting")
        val extension = AiPipelineExtension(registry)
        extension.onConfigured(PipelineEnvironment(aiService = null, rawConfig = config, statsDir = statsDir.toString()))
        extension.onPostFetch(mr, config)
        val findings = extension.afterValidation(emptyList(), mr, config)
        return extension.beforeReporting(findings, mr).map { it.rule }
    }

    @Test
    fun `metrics-only config still emits metric findings`() =
        runTest {
            val config = buildJsonObject {
                putJsonObject("ai") { putJsonObject("metrics") { put("changeEntropy", true) } }
            }

            assertEquals(listOf("metric:${ChangeEntropyMetricModule.METRIC_ID}"), beforeReporting(config))
        }

    @Test
    fun `report served by a report module is produced once`() =
        runTest {
            val config = buildJsonObject {
                putJsonObject("ai") { putJsonObject("reports") { put("riskHeatmap", true) } }
            }

            assertEquals(listOf("report:${RiskHeatmapReportModule.REPORT_ID}"), beforeReporting(config))
        }

    @Test
    fun `reporters alias enables the AI report`() =
        runTest {
            val config = buildJsonObject {
                putJsonObject("ai") {}
                putJsonObject("reporters") { put("risk-heatmap", true) }
            }

            assertEquals(listOf("report:${RiskHeatmapReportModule.REPORT_ID}"), beforeReporting(config))
        }

    @Test
    fun `reporters alias with enabled false stays off`() =
        runTest {
            val config = buildJsonObject {
                putJsonObject("ai") {}
                putJsonObject("reporters") { putJsonObject("riskHeatmap") { put("enabled", false) } }
            }

            assertEquals(emptyList(), beforeReporting(config))
        }

    @Test
    fun `reporters alias output writes the report file`() =
        runTest {
            val out = Path(TestFiles.createTestTempDirectory("karandash-alias"), "heatmap.json")
            val config = buildJsonObject {
                putJsonObject("ai") {}
                putJsonObject("reporters") { putJsonObject("riskHeatmap") { put("output", out.toString()) } }
            }

            beforeReporting(config)

            val written = SystemFileSystem.source(out).buffered().use { it.readString() }
            assertTrue(written.isNotBlank())
        }

    @Test
    fun `reporters checkRunAnnotation adds the annotations report`() =
        runTest {
            val config = buildJsonObject {
                putJsonObject("ai") {}
                putJsonObject("reporters") { putJsonObject("checkRunAnnotation") { put("format", "gitlab") } }
            }

            assertEquals(listOf("report:check-run-annotations"), beforeReporting(config))
        }
}
