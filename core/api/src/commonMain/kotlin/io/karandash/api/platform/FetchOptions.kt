package io.karandash.api.platform

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Options passed to [io.karandash.api.module.PlatformServiceModule.fetchMergeRequest] and
 * [io.karandash.api.module.PlatformServiceModule.fetchMergeRequestByUrl].
 *
 * Added as a default-valued parameter so existing platform implementations continue to
 * compile unchanged - only services that opt in need to honour the new fields.
 *
 * @property contextLines Number of unchanged context lines requested per hunk around
 *     each change. Clamped to 1..[MAX_CONTEXT_LINES]. Platforms that cannot honour the
 *     exact value fall back to the nearest supported option and log a warning.
 */
@Serializable
public data class FetchOptions(
    @SerialName("context-lines") val contextLines: Int = DEFAULT_CONTEXT_LINES,
) {
    init {
        require(contextLines in MIN_CONTEXT_LINES..MAX_CONTEXT_LINES) {
            "contextLines must be in $MIN_CONTEXT_LINES..$MAX_CONTEXT_LINES, got: $contextLines"
        }
    }

    public companion object {
        /** Default context lines - matches the historical unified-diff default. */
        public const val DEFAULT_CONTEXT_LINES: Int = 3

        /** Minimum accepted context line count. Zero produces a `-U0` style diff. */
        public const val MIN_CONTEXT_LINES: Int = 0

        /**
         * Maximum accepted context line count. Larger values are rejected to prevent
         * pathological prompt sizes.
         */
        public const val MAX_CONTEXT_LINES: Int = 50
    }
}
