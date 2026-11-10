package io.karandash.plugins.ai.core.pipeline

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemiFormalPromptBuilderTest {
    @Test
    fun buildPromptIncludesCertificateTemplate() {
        val result = SemiFormalPromptBuilder.buildPrompt(
            basePrompt = "You are a code reviewer.",
            responseFormatSuffix = """{"comments": []}""",
            certificateType = CertificateType.CODE_REVIEW,
        )

        assertContains(result, "SEMI-FORMAL REASONING CERTIFICATE")
        assertContains(result, "DEFINITIONS")
        assertContains(result, "PREMISES")
        assertContains(result, "EXECUTION TRACE")
        assertContains(result, "EVIDENCE")
        assertContains(result, "FINDINGS")
    }

    @Test
    fun buildPromptIncludesBasePrompt() {
        val result = SemiFormalPromptBuilder.buildPrompt(
            basePrompt = "You are a code reviewer.",
            responseFormatSuffix = null,
            certificateType = CertificateType.CODE_REVIEW,
        )

        assertContains(result, "You are a code reviewer.")
    }

    @Test
    fun buildPromptIncludesResponseFormatSuffix() {
        val result = SemiFormalPromptBuilder.buildPrompt(
            basePrompt = "Base prompt.",
            responseFormatSuffix = """{"comments": [{"file": "path"}]}""",
            certificateType = CertificateType.CODE_REVIEW,
        )

        assertContains(result, "comments")
    }

    @Test
    fun buildPromptUsesRiskAssessmentTemplate() {
        val result = SemiFormalPromptBuilder.buildPrompt(
            basePrompt = "Assess risk.",
            responseFormatSuffix = """{"riskScore": 0.5}""",
            certificateType = CertificateType.RISK_ASSESSMENT,
        )

        assertContains(result, "RISK FACTOR ANALYSIS")
        assertContains(result, "blast radius")
    }

    @Test
    fun buildPromptUsesPatchAnalysisTemplate() {
        val result = SemiFormalPromptBuilder.buildPrompt(
            basePrompt = "Analyze patch.",
            responseFormatSuffix = null,
            certificateType = CertificateType.PATCH_ANALYSIS,
        )

        assertContains(result, "BEHAVIORAL ANALYSIS")
    }

    @Test
    fun buildPromptUsesCodeQATemplate() {
        val result = SemiFormalPromptBuilder.buildPrompt(
            basePrompt = "Check quality.",
            responseFormatSuffix = null,
            certificateType = CertificateType.CODE_QA,
        )

        assertContains(result, "FUNCTION TRACE TABLE")
        assertContains(result, "DATA FLOW ANALYSIS")
    }

    @Test
    fun buildPromptAppliesExtraInstructions() {
        val result = SemiFormalPromptBuilder.buildPrompt(
            basePrompt = "Base prompt.",
            extraInstructions = "Focus on security issues.",
            responseFormatSuffix = null,
            certificateType = CertificateType.CODE_REVIEW,
        )

        assertContains(result, "Focus on security issues.")
    }

    @Test
    fun buildPromptSystemPromptOverrideReplacesBase() {
        val result = SemiFormalPromptBuilder.buildPrompt(
            basePrompt = "Original base.",
            systemPromptOverride = "Custom override prompt.",
            responseFormatSuffix = null,
            certificateType = CertificateType.CODE_REVIEW,
        )

        assertContains(result, "Custom override prompt.")
        assertFalse(result.contains("Original base."))
    }

    @Test
    fun buildPromptIncludesFindingsInstruction() {
        val result = SemiFormalPromptBuilder.buildPrompt(
            basePrompt = "Base.",
            responseFormatSuffix = """{"findings": []}""",
            certificateType = CertificateType.CODE_REVIEW,
        )

        assertContains(result, "FINDINGS section")
    }

    @Test
    fun buildPromptIncludesStructuredReasoningRules() {
        val result = SemiFormalPromptBuilder.buildPrompt(
            basePrompt = "Base.",
            responseFormatSuffix = null,
            certificateType = CertificateType.CODE_REVIEW,
        )

        assertContains(result, "STRUCTURED REASONING REQUIREMENT")
        assertContains(result, "Complete EVERY section in order")
        assertContains(result, "copied verbatim from the diff")
    }

    @Test
    fun allCertificateTypesProduceNonEmptyTemplates() {
        for (type in CertificateType.entries) {
            val template = CertificateTemplates.forType(type)
            assertTrue(
                template.isNotBlank(),
                "Template for $type should not be blank",
            )
            assertTrue(
                template.contains("Evidence", ignoreCase = false) ||
                    template.contains("EVIDENCE"),
                "Template for $type must reference evidence",
            )
            assertContains(
                template,
                "FINDINGS",
                message = "Template for $type must contain FINDINGS section",
            )
        }
    }
}
