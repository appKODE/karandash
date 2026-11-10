package io.karandash.plugins.ai.core.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReasoningStrategyTest {
    @Test
    fun fromConfigDirectReturnsDirectStrategy() {
        val strategy = ReasoningStrategy.fromConfig("direct")
        assertIs<DirectStrategy>(strategy)
    }

    @Test
    fun fromConfigSemiFormalReturnsSemiFormalStrategy() {
        val strategy = ReasoningStrategy.fromConfig("semi-formal")
        assertIs<SemiFormalStrategy>(strategy)
    }

    @Test
    fun fromConfigSemiFormalCaseInsensitive() {
        val strategy = ReasoningStrategy.fromConfig("Semi-Formal")
        assertIs<SemiFormalStrategy>(strategy)
    }

    @Test
    fun fromConfigSemiformalWithoutHyphen() {
        val strategy = ReasoningStrategy.fromConfig("semiformal")
        assertIs<SemiFormalStrategy>(strategy)
    }

    @Test
    fun fromConfigUnknownValueDefaultsToDirect() {
        val strategy = ReasoningStrategy.fromConfig("unknown")
        assertIs<DirectStrategy>(strategy)
    }

    @Test
    fun fromConfigEmptyStringDefaultsToDirect() {
        val strategy = ReasoningStrategy.fromConfig("")
        assertIs<DirectStrategy>(strategy)
    }

    @Test
    fun fromConfigWithWhitespace() {
        val strategy = ReasoningStrategy.fromConfig("  semi-formal  ")
        assertIs<SemiFormalStrategy>(strategy)
    }

    @Test
    fun certificateTypeForAnalyzerReviewComments() {
        val type = CertificateType.forAnalyzer("review-comments")
        assertEquals(CertificateType.CODE_REVIEW, type)
    }

    @Test
    fun certificateTypeForAnalyzerCodeSuggestions() {
        val type = CertificateType.forAnalyzer("code-suggestions")
        assertEquals(CertificateType.CODE_REVIEW, type)
    }

    @Test
    fun certificateTypeForAnalyzerRiskScoring() {
        val type = CertificateType.forAnalyzer("risk-scoring")
        assertEquals(CertificateType.RISK_ASSESSMENT, type)
    }

    @Test
    fun certificateTypeForAnalyzerFixSuggestions() {
        val type = CertificateType.forAnalyzer("fix-suggestions")
        assertEquals(CertificateType.PATCH_ANALYSIS, type)
    }

    @Test
    fun certificateTypeForAnalyzerDescriptionQuality() {
        val type = CertificateType.forAnalyzer("description-quality")
        assertEquals(CertificateType.CODE_QA, type)
    }

    @Test
    fun certificateTypeForUnknownAnalyzerDefaultsToCodeReview() {
        val type = CertificateType.forAnalyzer("ai-unknown-analyzer")
        assertEquals(CertificateType.CODE_REVIEW, type)
    }

    @Test
    fun semiFormalStrategyDefaultCertificateTypeIsNull() {
        val strategy = SemiFormalStrategy()
        assertEquals(null, strategy.certificateType)
    }

    @Test
    fun semiFormalStrategyWithExplicitCertificateType() {
        val strategy = SemiFormalStrategy(CertificateType.RISK_ASSESSMENT)
        assertEquals(CertificateType.RISK_ASSESSMENT, strategy.certificateType)
    }
}
