package io.karandash.validators

import io.karandash.api.RuleConfig
import io.karandash.percent.KarandashPercent
import kotlinx.serialization.Serializable

/**
 * Sub-config controlling the severity of size limit violations
 * and an optional warning threshold.
 *
 * @property onViolation Severity when a limit is reached: `"error"` or `"warning"`.
 * @property warnThreshold Optional early-warning threshold as a percentage of the limit.
 *   When set, a warning finding is emitted if usage exceeds `limit * warnThreshold`
 *   but is below the hard limit. Must be between 50% and 99%; an out-of-range value is
 *   ignored and reported as a warning finding instead of failing config decoding.
 */
@Serializable
data class SizeLimitsSeverityConfig(
    val onViolation: String = "error",
    val warnThreshold: KarandashPercent? = null,
) {
    /** [warnThreshold] when it lies within 50%..99%, otherwise null. */
    val validWarnThreshold: KarandashPercent?
        get() = warnThreshold?.takeIf { it.fraction in MIN_WARN_FRACTION..MAX_WARN_FRACTION }

    companion object {
        private const val MIN_WARN_FRACTION = 0.5
        private const val MAX_WARN_FRACTION = 0.99
    }
}

@Serializable
data class SizeLimitsConfig(
    val maxFiles: Int? = null,
    val maxLines: Int? = null,
    val maxCommits: Int? = null,
    val excludeRenames: Boolean = false,
    val excludeDeleted: Boolean = false,
    /** Not `severity:`, which is the scalar meta severity. */
    val severities: SizeLimitsSeverityConfig = SizeLimitsSeverityConfig(),
) : RuleConfig
