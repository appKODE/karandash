package io.karandash.detect

import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.ProviderDetectionScore
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.logger.debug
import io.karandash.platform.PlatformEnvironment

/**
 * Scores all registered providers and selects the best match for the current environment.
 *
 * Uses [PlatformServiceModule.detectConfidence] to collect scored results from each
 * provider, then selects the one with the highest score above [ProviderDetectionScore.MIN_VIABLE_SCORE].
 *
 * @param providers Registered provider modules to evaluate.
 * @param envProvider Environment variable lookup function. Defaults to [PlatformEnvironment.getEnv].
 * @param logger Receives each provider's score and the pick, at debug.
 */
class ProviderAutoDetector(
    private val providers: List<PlatformServiceModule>,
    private val envProvider: (String) -> String? = { PlatformEnvironment.getEnv(it) },
    private val logger: Logger = NoOpLogger,
) {
    /**
     * Evaluate all providers and return scored results, sorted by score descending.
     */
    suspend fun detectAll(): List<ProviderDetectionScore> =
        providers
            .mapNotNull { provider -> provider.detectConfidence(envProvider) }
            .sortedByDescending { it.score }
            .also { scores -> logger.debug { "Provider auto-detect: scores ${scores.map { "${it.providerId}=${it.score}" }}" } }

    /**
     * Return the highest-scoring provider detection above [ProviderDetectionScore.MIN_VIABLE_SCORE],
     * or null if no provider meets the threshold.
     */
    suspend fun detectBest(): ProviderDetectionScore? =
        detectAll().firstOrNull { it.score >= ProviderDetectionScore.MIN_VIABLE_SCORE }.also {
            val minScore = ProviderDetectionScore.MIN_VIABLE_SCORE
            logger.debug { "Provider auto-detect: picked ${it?.providerId ?: "none"} (min score $minScore)" }
        }
}
