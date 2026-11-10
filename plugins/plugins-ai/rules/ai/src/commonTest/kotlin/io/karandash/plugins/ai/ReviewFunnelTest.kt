package io.karandash.plugins.ai

import io.karandash.api.finding.FilterProvenance
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.dispatch.SampleAgreement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewFunnelTest {
    @Test
    fun `the funnel counts comments agreed grounded posted singletons and refuted`() {
        val agreed = comment(AiFindingMetadataKeys.AGREEMENT to SampleAgreement.AGREED, AiFindingMetadataKeys.GROUNDING to "grounded")
        val single = comment(AiFindingMetadataKeys.AGREEMENT to SampleAgreement.SINGLE)
        val refuted = comment(AiFindingMetadataKeys.AGREEMENT to SampleAgreement.AGREED, AiFindingMetadataKeys.GROUNDING to "refuted")
        val before = listOf(agreed, single, refuted, comment(), Finding(Severity.INFO, "risk", "risk-scoring"))

        val counts = ReviewFunnel.counts(before, listOf(agreed, single, refuted), listOf(FilterProvenance("grounding", 1)))
        val line = ReviewFunnel.line(counts)

        assertEquals(
            mapOf("sampled" to 4, "agreed" to 2, "grounded" to 1, "posted" to 1, "singletons" to 1, "refuted" to 2),
            counts,
        )

        assertEquals(
            "AI review: 4 comments → 2 agreed → 1 grounded → 1 posted (1 singletons as info, 2 refuted by grounding)",
            line,
        )
    }

    @Test
    fun `no review comments means no funnel line`() {
        assertNull(ReviewFunnel.line(listOf(Finding(Severity.INFO, "risk", "risk-scoring")), emptyList(), emptyList()))
    }

    private fun comment(vararg metadata: Pair<String, String>) =
        Finding(Severity.WARNING, "comment", AiRuleIds.REVIEW_COMMENTS, metadata = metadata.toMap())
}
