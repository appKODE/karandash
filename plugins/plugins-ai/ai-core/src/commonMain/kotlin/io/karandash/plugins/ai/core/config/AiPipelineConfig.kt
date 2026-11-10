package io.karandash.plugins.ai.core.config

import io.karandash.percent.KarandashPercent
import kotlinx.serialization.Serializable

/**
 * Thresholds used by the AI pipeline extension to classify
 * risk levels and entropy scores.
 */
@Serializable
data class AiPipelineThresholdsConfig(
    val highRisk: KarandashPercent = DEFAULT_HIGH_RISK,
    val highEntropy: KarandashPercent = DEFAULT_HIGH_ENTROPY,
    val highSurpriseTokens: Int = DEFAULT_HIGH_SURPRISE_TOKENS,
) {
    companion object {
        private const val DEFAULT_PERCENT = 70
        val DEFAULT_HIGH_RISK: KarandashPercent = KarandashPercent.ofPercent(DEFAULT_PERCENT)
        val DEFAULT_HIGH_ENTROPY: KarandashPercent = KarandashPercent.ofPercent(DEFAULT_PERCENT)
        const val DEFAULT_HIGH_SURPRISE_TOKENS = 5
    }
}

/**
 * Clone detection limits in the AI pipeline extension.
 */
@Serializable
data class AiCloneDetectionConfig(
    val maxFindings: Int = DEFAULT_MAX_FINDINGS,
) {
    companion object {
        const val DEFAULT_MAX_FINDINGS = 3
    }
}

/**
 * Report generation thresholds for minimum record counts.
 */
@Serializable
data class AiReportsConfig(
    val minRecordsForCrossPlatform: Int = DEFAULT_MIN_RECORDS_CROSS_PLATFORM,
    val minRecordsRequired: Int = DEFAULT_MIN_RECORDS_REQUIRED,
) {
    companion object {
        const val DEFAULT_MIN_RECORDS_CROSS_PLATFORM = 2
        const val DEFAULT_MIN_RECORDS_REQUIRED = 5
    }
}

/**
 * Configuration for AI pipeline extension behaviour.
 *
 * ```yaml
 * ai:
 *   pipeline:
 *     thresholds:
 *       high-risk: "80%"
 *       high-surprise-tokens: 3
 *     clone-detection:
 *       max-findings: 5
 *     reports:
 *       min-records-required: 10
 *     prior-findings:
 *       enabled: true
 *       max-per-file: 20
 *     temporal-escalation: true   # opt-in, needs the state cache
 * ```
 *
 * @param temporalEscalation Escalate severity of rules an MR author keeps violating across MRs
 *   (history kept in the AI state store). Off by default.
 */
@Serializable
data class AiPipelineConfig(
    val thresholds: AiPipelineThresholdsConfig = AiPipelineThresholdsConfig(),
    val cloneDetection: AiCloneDetectionConfig = AiCloneDetectionConfig(),
    val reports: AiReportsConfig = AiReportsConfig(),
    val priorFindings: AiPriorFindingsConfig = AiPriorFindingsConfig(),
    val temporalEscalation: Boolean = false,
)
