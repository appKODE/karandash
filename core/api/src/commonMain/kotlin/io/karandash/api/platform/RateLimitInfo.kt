package io.karandash.api.platform

import kotlinx.serialization.Serializable

/**
 * Rate limit information from the platform API.
 *
 * Returned by [PlatformServiceModule.rateLimitStatus][io.karandash.api.module.PlatformServiceModule.rateLimitStatus]
 * to allow the pipeline to throttle requests when approaching limits.
 */
@Serializable
public data class RateLimitInfo(
    val remaining: Int,
    val limit: Int,
    val resetEpochSeconds: Long,
) {
    /** Whether remaining requests are below 10% of the total limit. */
    val isNearLimit: Boolean get() = remaining < (limit * NEAR_LIMIT_THRESHOLD).toInt()

    public companion object {
        private const val NEAR_LIMIT_THRESHOLD = 0.1
    }
}
