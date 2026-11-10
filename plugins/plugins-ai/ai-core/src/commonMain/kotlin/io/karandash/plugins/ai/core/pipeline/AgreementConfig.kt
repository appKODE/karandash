package io.karandash.plugins.ai.core.pipeline

import io.karandash.api.exception.ConfigParseException
import kotlinx.serialization.Serializable

/**
 * `ai.review-comments.samples` / `.sample-temperature`: how many times each review-comments call runs.
 *
 * @param samples Calls per chunk, 1..3; sample 1 is the normal call, the others are compared with it.
 * @param sampleTemperature Temperature of samples 2 and later, 0..2.
 */
@Serializable
data class ReviewSamplingConfig(
    val samples: Int = 2,
    val sampleTemperature: Double = 0.5,
) {
    /** This config, or a [ConfigParseException] naming the bad key. */
    fun validated(): ReviewSamplingConfig {
        if (samples !in 1..MAX_SAMPLES) {
            throw ConfigParseException("ai.review-comments.samples: $samples is not in 1..$MAX_SAMPLES")
        }
        if (sampleTemperature !in 0.0..MAX_TEMPERATURE) {
            throw ConfigParseException("ai.review-comments.sample-temperature: $sampleTemperature is not in 0..2")
        }
        return this
    }

    private companion object {
        const val MAX_SAMPLES = 3
        const val MAX_TEMPERATURE = 2.0
    }
}

/**
 * `ai.agreement`: how two samples of review-comments are matched.
 *
 * @param lineWindow Max line distance of a matching pair; not negative.
 * @param minSimilarity Overlap coefficient of the messages' words (4+ letters) a pair needs, 0..1.
 * @param singleton What happens to a finding only one sample reported: `demote` (INFO, file level, no cap slot),
 *   `drop` or `keep`.
 */
@Serializable
data class AgreementConfig(
    val lineWindow: Int = 3,
    val minSimilarity: Double = 0.3,
    val singleton: String = DEMOTE,
) {
    /** This config, or a [ConfigParseException] naming the bad `ai.agreement` key. */
    fun validated(): AgreementConfig {
        if (lineWindow < 0) throw ConfigParseException("ai.agreement.lineWindow: $lineWindow must not be negative")
        if (minSimilarity !in 0.0..1.0) {
            throw ConfigParseException("ai.agreement.minSimilarity: $minSimilarity is not in 0..1")
        }
        if (singleton !in setOf(DEMOTE, DROP, KEEP)) {
            throw ConfigParseException("ai.agreement.singleton: '$singleton' is not $DEMOTE, $DROP or $KEEP")
        }
        return this
    }

    companion object {
        const val DEMOTE = "demote"
        const val DROP = "drop"
        const val KEEP = "keep"
    }
}

/**
 * `ai.hints`: the deterministic hint block prepended to the review-comments prompt.
 *
 * @param enabled Build the block at all.
 * @param maxTokens Upper bound of the block, in tokens; positive.
 */
@Serializable
data class HintsConfig(
    val enabled: Boolean = true,
    val maxTokens: Int = 400,
) {
    /** This config, or a [ConfigParseException] naming the bad `ai.hints` key. */
    fun validated(): HintsConfig {
        if (maxTokens <= 0) throw ConfigParseException("ai.hints.maxTokens: $maxTokens must be positive")
        return this
    }
}
