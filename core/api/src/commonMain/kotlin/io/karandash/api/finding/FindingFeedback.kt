package io.karandash.api.finding

import kotlinx.serialization.Serializable

/**
 * A reviewer's reaction to one finding karandash posted, read back from the platform
 * (`PlatformServiceModule.fetchFindingFeedback`). Only counted (the `feedback` block of `diagnostic.json`), never used
 * to suppress findings.
 *
 * @property rule Rule id of the finding, from its [InlineFindingMarker].
 * @property fingerprint The finding's anchor fingerprint ([InlineFindingRef.fpv]), or its
 *   [InlineFindingRef.semanticFingerprint] in markers written before `fpv` existed.
 * @property reaction What the reaction means: [FALSE_POSITIVE] for a thumbs-down.
 * @property author Username of whoever reacted.
 */
@Serializable
public data class FindingFeedback(
    val rule: String,
    val fingerprint: String,
    val reaction: String = FALSE_POSITIVE,
    val author: String,
) {
    public companion object {
        /** [reaction] of a thumbs-down: the reviewer marks the finding a false positive. */
        public const val FALSE_POSITIVE: String = "falsePositive"
    }
}
