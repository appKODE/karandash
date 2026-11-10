package io.karandash.api.platform

import kotlinx.serialization.Serializable

/**
 * Scored detection result from a provider's environment analysis.
 *
 * Each provider can produce a score indicating how confident it is that
 * the current environment belongs to its platform. Higher scores indicate
 * stronger evidence. The [ProviderAutoDetector][io.karandash.detect.ProviderAutoDetector]
 * selects the provider with the highest score.
 *
 * @property providerId The provider module ID.
 * @property score Confidence score (0.0 = no evidence, 1.0 = certain).
 * @property source Human-readable source of detection (e.g. "ci:github-actions", "git-remote:github").
 * @property ciEnvironment Resolved CI environment data, if available.
 */
@Serializable
public data class ProviderDetectionScore(
    val providerId: String,
    val score: Double,
    val source: String,
    val ciEnvironment: CIEnvironment? = null,
) {
    public companion object {
        /** Score threshold below which a detection is considered too weak to act on. */
        public const val MIN_VIABLE_SCORE: Double = 0.3
    }
}
