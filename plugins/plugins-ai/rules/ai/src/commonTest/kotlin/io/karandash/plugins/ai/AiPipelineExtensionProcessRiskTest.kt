package io.karandash.plugins.ai

import io.karandash.api.module.PipelineEnvironment
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiPipelineExtensionProcessRiskTest {
    private val mr = createTestMergeRequest(
        changes = listOf(
            FileChange("a/A.kt", ChangeType.MODIFIED, additions = 10, deletions = 10),
            FileChange("b/B.kt", ChangeType.MODIFIED, additions = 10, deletions = 10),
        ),
    )

    private suspend fun run(config: String): AiPipelineExtension =
        AiPipelineExtension(buildTestRegistry()).also {
            it.onConfigured(
                PipelineEnvironment(
                    aiService = null,
                    rawConfig = KarandashJson.Default.parseToJsonElement(config),
                    statsDir = ".karandash/stats"
                ),
            )
            it.onPostFetch(mr, null)
        }

    @Test
    fun `ai processRisk adds an INFO finding with the factor breakdown`() =
        runTest {
            val ext = run("""{"ai": {"processRisk": {"factors": {"authorFamiliarity": false}}}}""")
            val finding = ext.beforeReporting(emptyList(), mr).single { it.rule == ProcessRiskAssessment.RULE }
            assertNotNull(finding.metadata[ProcessRiskAssessment.META_SCORE])
            assertEquals(null, finding.metadata["authorFamiliarity"])
            assertTrue(finding.explanation.orEmpty().contains("Change churn"))
        }

    @Test
    fun `without ai processRisk no finding is added`() =
        runTest {
            val ext = run("""{"ai": {}}""")
            assertTrue(ext.beforeReporting(emptyList(), mr).isEmpty())
            assertNotNull(ext.processRiskScore())
        }

    @Test
    fun `processRisk false is off`() =
        runTest {
            assertNull(run("""{"ai": {"processRisk": false}}""").beforeReporting(emptyList(), mr).firstOrNull())
        }

    @Test
    fun `the ai block of the full config reaches the pipeline thresholds`() =
        runTest {
            val ext = run("""{"ai": {"processRisk": true, "pipeline": {"thresholds": {"highRisk": "1%"}}}}""")
            val finding = ext.beforeReporting(emptyList(), mr).single()
            assertEquals("HIGH", finding.metadata[ProcessRiskAssessment.META_LEVEL])
        }
}
