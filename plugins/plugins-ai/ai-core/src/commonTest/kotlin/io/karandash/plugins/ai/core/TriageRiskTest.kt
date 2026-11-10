package io.karandash.plugins.ai.core

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.config.ProcessRiskConfig
import io.karandash.plugins.ai.core.config.ProcessRiskFactors
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** the triage cluster measures the MR through one shared risk computation. */
class TriageRiskTest {
    private val mr = createTestMergeRequest(
        changes = listOf(
            FileChange("app/a/A.kt", ChangeType.MODIFIED, additions = 40, deletions = 38),
            FileChange("lib/b/B.kt", ChangeType.MODIFIED, additions = 2),
            FileChange("ci/c.yml", ChangeType.ADDED, additions = 5),
        ),
    )
    private val onlyChurn =
        ProcessRiskConfig(factors = ProcessRiskFactors(fileDiversity = false, changeDensity = false, authorFamiliarity = false))

    private fun context(config: String) =
        AiAnalyzerContext(mr, FakeAiService("{}"), TokenBudget(1_000), "m", config = KarandashJson.Default.parseToJsonElement(config))

    @Test
    fun `an analyzer sees the run risk the processRisk block configures`() {
        val factors = """{"fileDiversity":false,"changeDensity":false,"authorFamiliarity":false}"""
        val configured = TriageRisk.of(context("""{"processRisk":{"factors":$factors}}"""))

        assertEquals(TriageRisk.of(mr, onlyChurn), configured)
        assertNotEquals(TriageRisk.of(mr, null), configured)
    }

    @Test
    fun `without an enabled processRisk block every factor counts`() {
        val all = ProcessRiskCalculator.calculate(mr)

        assertEquals(all, TriageRisk.of(context("{}")))
        assertEquals(all, TriageRisk.of(mr, onlyChurn.copy(enabled = false)))
        assertEquals(mapOf(AiFindingMetadataKeys.PROCESS_RISK to all.composite.formatDecimals(2)), TriageRisk.metadata(all))
    }

    @Test
    fun `processRisk authorFamiliarity true counts the factor and false drops it`() {
        val all = ProcessRiskCalculator.calculate(mr)
        val familiarityOnly = TriageRisk.of(
            context(
                """{"processRisk":{"factors":{"changeChurn":false,"fileDiversity":false,"changeDensity":false,"authorFamiliarity":true}}}"""
            ),
        )
        val withoutFamiliarity = TriageRisk.of(context("""{"processRisk":{"factors":{"authorFamiliarity":false}}}"""))

        assertEquals(all.authorFamiliarity, familiarityOnly.composite)
        assertNotEquals(all.composite, withoutFamiliarity.composite)
    }
}
