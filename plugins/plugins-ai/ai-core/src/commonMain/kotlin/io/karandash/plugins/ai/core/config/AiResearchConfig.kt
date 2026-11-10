package io.karandash.plugins.ai.core.config

import io.karandash.percent.KarandashPercent
import kotlinx.serialization.Serializable

/**
 * Weights for the cognitive complexity delta calculator.
 *
 * @property nestingWeight Weight multiplier per nesting depth level.
 * @property controlFlowWeight Base weight for control flow keywords.
 * @property booleanOpWeight Weight for boolean operators.
 */
@Serializable
data class CognitiveComplexityConfig(
    val nestingWeight: Int = DEFAULT_NESTING_WEIGHT,
    val controlFlowWeight: Int = DEFAULT_CONTROL_FLOW_WEIGHT,
    val booleanOpWeight: Int = DEFAULT_BOOLEAN_OP_WEIGHT,
) {
    companion object {
        const val DEFAULT_NESTING_WEIGHT = 2
        const val DEFAULT_CONTROL_FLOW_WEIGHT = 1
        const val DEFAULT_BOOLEAN_OP_WEIGHT = 1
    }
}

/**
 * Configuration for betweenness centrality analysis.
 *
 * @property bottleneckThreshold Fraction of the max centrality score above which
 *   a node is classified as a bottleneck.
 */
@Serializable
data class BetweennessConfig(
    val bottleneckThreshold: KarandashPercent = DEFAULT_BOTTLENECK_THRESHOLD,
) {
    companion object {
        private const val DEFAULT_BOTTLENECK_PERCENT = 50
        val DEFAULT_BOTTLENECK_THRESHOLD: KarandashPercent = KarandashPercent.ofPercent(DEFAULT_BOTTLENECK_PERCENT)
    }
}

/**
 * Configuration for EVT (Extreme Value Theory) auto-threshold estimation.
 *
 * @property confidence Confidence level for the threshold computation.
 * @property minSamples Minimum number of historical samples required for data-driven thresholds.
 * @property tailFraction Fraction of the distribution considered as the tail.
 */
@Serializable
data class EvtAutoThresholdConfig(
    val confidence: KarandashPercent = DEFAULT_CONFIDENCE,
    val minSamples: Int = DEFAULT_MIN_SAMPLES,
    val tailFraction: KarandashPercent = DEFAULT_TAIL_FRACTION,
) {
    companion object {
        private const val DEFAULT_CONFIDENCE_PERCENT = 99
        private const val DEFAULT_TAIL_FRACTION_PERCENT = 10
        val DEFAULT_CONFIDENCE: KarandashPercent = KarandashPercent.ofPercent(DEFAULT_CONFIDENCE_PERCENT)
        val DEFAULT_TAIL_FRACTION: KarandashPercent = KarandashPercent.ofPercent(DEFAULT_TAIL_FRACTION_PERCENT)
        const val DEFAULT_MIN_SAMPLES = 20
    }
}

/**
 * Configuration for AI research modules (complexity, centrality, EVT).
 *
 * ```yaml
 * ai:
 *   research:
 *     cognitive-complexity:
 *       nesting-weight: 3
 *     betweenness:
 *       bottleneck-threshold: "60%"
 *     evt-auto-threshold:
 *       confidence: "95%"
 *       min-samples: 50
 * ```
 */
@Serializable
data class AiResearchConfig(
    val cognitiveComplexity: CognitiveComplexityConfig = CognitiveComplexityConfig(),
    val betweenness: BetweennessConfig = BetweennessConfig(),
    /** Unset (`null`) keeps the calibration engine's default EVT parameters (95% confidence, 20 samples). */
    val evtAutoThreshold: EvtAutoThresholdConfig? = null,
)
