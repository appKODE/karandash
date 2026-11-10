package io.karandash.plugins.ai

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.ProcessRiskMetrics
import io.karandash.plugins.ai.core.config.ProcessRiskFactors
import io.karandash.plugins.ai.core.formatDecimals

/** `ai.processRisk`: the log breakdown and INFO finding for an MR's [ProcessRiskMetrics]. */
internal object ProcessRiskAssessment {
    const val RULE = "ai-process-risk"
    val META_SCORE = AiFindingMetadataKeys.PROCESS_RISK
    const val META_LEVEL = "processRiskLevel"
    private const val MEDIUM_THRESHOLD = 0.4

    fun level(
        composite: Double,
        highThreshold: Double,
    ): String =
        when {
            composite >= highThreshold -> "HIGH"
            composite >= MEDIUM_THRESHOLD -> "MEDIUM"
            else -> "LOW"
        }

    /** Enabled factors as (label, metadata key, score). */
    private fun factorScores(
        metrics: ProcessRiskMetrics,
        factors: ProcessRiskFactors,
    ): List<Triple<String, String, Double>> =
        listOfNotNull(
            Triple("Change churn", "changeChurn", metrics.churnRatio).takeIf { factors.changeChurn },
            Triple("File diversity", "fileDiversity", metrics.fileDiversity).takeIf { factors.fileDiversity },
            Triple("Change density", "changeDensity", metrics.commitDensity).takeIf { factors.changeDensity },
            Triple("Author familiarity", "authorFamiliarity", metrics.authorFamiliarity).takeIf { factors.authorFamiliarity },
        )

    fun headline(
        metrics: ProcessRiskMetrics,
        highThreshold: Double,
    ): String = "Process risk assessment: ${metrics.composite.formatDecimals(2)} (${level(metrics.composite, highThreshold)})"

    fun breakdown(
        metrics: ProcessRiskMetrics,
        factors: ProcessRiskFactors,
    ): String = factorScores(metrics, factors).joinToString("\n") { (label, _, score) -> "- $label: ${score.formatDecimals(2)}" }

    fun finding(
        metrics: ProcessRiskMetrics,
        factors: ProcessRiskFactors,
        highThreshold: Double,
    ): Finding =
        Finding(
            severity = Severity.INFO,
            message = headline(metrics, highThreshold),
            rule = RULE,
            explanation = breakdown(metrics, factors).ifEmpty { null },
            metadata = mapOf(
                META_SCORE to metrics.composite.formatDecimals(2),
                META_LEVEL to level(metrics.composite, highThreshold),
            ) + factorScores(metrics, factors).associate { (_, key, score) -> key to score.formatDecimals(2) },
        )
}
