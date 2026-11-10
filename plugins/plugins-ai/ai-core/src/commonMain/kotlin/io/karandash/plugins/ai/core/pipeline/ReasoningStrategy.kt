package io.karandash.plugins.ai.core.pipeline

import io.karandash.plugins.ai.core.AiRuleIds
import kotlinx.serialization.Serializable

/**
 * Controls how AI analyzers structure their reasoning during code review.
 *
 * [DirectStrategy] is the default single-shot LLM call.
 * [SemiFormalStrategy] wraps prompts with a structured certificate template
 * inspired by Meta's "Agentic Code Reasoning" paper, requiring the LLM to
 * state premises, trace execution paths, cite evidence, and derive formal
 * conclusions before producing findings.
 */
sealed interface ReasoningStrategy {
    companion object {
        /** Resolves a [ReasoningStrategy] from the config string value. */
        fun fromConfig(reasoning: String): ReasoningStrategy =
            when (reasoning.lowercase().trim()) {
                "semi-formal", "semiformal" -> SemiFormalStrategy()
                else -> DirectStrategy
            }
    }
}

/** Standard single-shot LLM call - no structured reasoning wrapper. */
data object DirectStrategy : ReasoningStrategy

/**
 * Semi-formal reasoning: wraps the analyzer prompt with a certificate template
 * that forces explicit premises, execution traces, and evidence citations.
 *
 * The [certificateType] determines which template is used. When null, the type
 * is inferred from the analyzer's [AiAnalyzerModule.analyzerId] at dispatch time.
 */
@Serializable
data class SemiFormalStrategy(
    val certificateType: CertificateType? = null,
) : ReasoningStrategy

/**
 * Certificate types corresponding to different code analysis tasks.
 * Each type maps to a specific template in [CertificateTemplates].
 */
enum class CertificateType {
    /** For review-comments, code-suggestions - fault localization style. */
    CODE_REVIEW,

    /** For fix-suggestions - patch equivalence style. */
    PATCH_ANALYSIS,

    /** For description-quality, review-thread-analysis. */
    CODE_QA,

    /** For risk-scoring - risk assessment style. */
    RISK_ASSESSMENT;

    companion object {
        /** Maps analyzer IDs to their appropriate certificate type. */
        private val ANALYZER_MAPPING = mapOf(
            "review-comments" to CODE_REVIEW,
            AiRuleIds.CODE_SUGGESTIONS to CODE_REVIEW,
            "fix-suggestions" to PATCH_ANALYSIS,
            "description-quality" to CODE_QA,
            "review-thread-analysis" to CODE_QA,
            AiRuleIds.RISK_SCORING to RISK_ASSESSMENT,
        )

        /** Infers the certificate type from an analyzer ID, defaulting to [CODE_REVIEW]. */
        fun forAnalyzer(analyzerId: String): CertificateType = ANALYZER_MAPPING[analyzerId] ?: CODE_REVIEW
    }
}
