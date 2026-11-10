package io.karandash.detect

import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.ProviderDetectionScore
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.platform.PlatformEnvironment

/**
 * The one CI detection: scores the registered providers through [ProviderAutoDetector], the scores `doctor`
 * prints, and returns the CI environment of the best viable score that carries one.
 *
 * Each provider declares its detection via [PlatformServiceModule.detectConfidence], which by default scores its
 * [PlatformServiceModule.detectCiEnvironment]. Equal scores keep the provider order.
 * A blank variable counts as unset, so a provider checking `env(X) != null` is not fooled by `X=`.
 */
class CIDetector(
    private val providers: List<PlatformServiceModule>,
    private val logger: Logger = NoOpLogger
) {
    suspend fun detect(envReader: (String) -> String? = PlatformEnvironment::getEnv): CIEnvironment? {
        val env: (String) -> String? = { name -> envReader(name)?.takeUnless { it.isBlank() } }
        val best = ProviderAutoDetector(providers, env, logger).detectAll().firstOrNull {
            it.ciEnvironment != null && it.score >= ProviderDetectionScore.MIN_VIABLE_SCORE
        }
        logger.debug("CI detection: ${best?.let { "matched provider '${it.providerId}'" } ?: "no provider matched CI environment"}")
        return best?.ciEnvironment
    }
}
